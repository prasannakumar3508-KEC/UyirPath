package com.arunai.dispatch.service;

import com.arunai.dispatch.graph.RouteResult;
import com.arunai.dispatch.model.Ambulance;
import com.arunai.dispatch.model.AmbulanceStatus;
import com.arunai.dispatch.model.Dispatch;
import com.arunai.dispatch.model.Emergency;
import com.arunai.dispatch.model.EmergencyStatus;
import com.arunai.dispatch.model.Hospital;
import com.arunai.dispatch.repository.AmbulanceRepository;
import com.arunai.dispatch.repository.DispatchRepository;
import com.arunai.dispatch.repository.EmergencyRepository;
import com.arunai.dispatch.repository.EventRepository;
import com.arunai.dispatch.repository.HospitalRepository;
import com.arunai.dispatch.util.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coordinates automatic dispatch:
 * register -> priority queue -> ambulance comparison -> hospital comparison ->
 * transactional assignment. Only one thread may assign at a time, and SQLite
 * conditional updates + partial unique indexes enforce the same invariant.
 */
public class DispatchService {
    public record IntakeResult(Emergency emergency, Dispatch dispatch, SelectionResult selection,
                               String message) {}

    private final AmbulanceRepository ambulances;
    private final HospitalRepository hospitals;
    private final EmergencyRepository emergencies;
    private final DispatchRepository dispatches;
    private final EventRepository events;
    private final GraphService graph;
    private final DispatchQueue queue = new DispatchQueue();
    private final DispatchPlanner planner = new DispatchPlanner();
    private SimulationService simulation;

    public DispatchService(AmbulanceRepository ambulances, HospitalRepository hospitals,
                           EmergencyRepository emergencies, DispatchRepository dispatches,
                           EventRepository events, GraphService graph) {
        this.ambulances = ambulances;
        this.hospitals = hospitals;
        this.emergencies = emergencies;
        this.dispatches = dispatches;
        this.events = events;
        this.graph = graph;
        for (Emergency queued : emergencies.findQueue()) queue.add(queued);
    }

    public void setSimulation(SimulationService simulation) { this.simulation = simulation; }

    public int queuedCount() { return queue.size(); }

    public synchronized void resetQueueState() {
        queue.clear();
        for (Emergency queued : emergencies.findQueue()) queue.add(queued);
    }

    public List<Emergency> queueSnapshot() { return queue.snapshot(); }

    /** Register an emergency and immediately run the automatic dispatch pass. */
    public synchronized IntakeResult register(Emergency emergency) {
        if (!graph.insideOperatingArea(emergency.getLat(), emergency.getLon())) {
            throw new IllegalArgumentException("Incident location is outside the configured Erode operating area");
        }
        if (emergency.getDescription() != null && emergency.getDescription().length() > 500) {
            throw new IllegalArgumentException("Description must be 500 characters or fewer");
        }
        var snapped = graph.snap(emergency.getLat(), emergency.getLon());
        if (snapped.isEmpty()) {
            throw new IllegalArgumentException("No road network is loaded; import OSM data first");
        }
        emergency.setSnappedNodeId(snapped.get().id());
        emergencies.insert(emergency);
        events.append(emergency.getId(), "REPORTED", "Emergency registered via intake form");
        events.append(emergency.getId(), "QUEUED", "Added to the " + emergency.getSeverity()
                + " priority queue (FIFO within severity)");
        queue.add(emergency);

        Dispatch assigned = processQueueInternal();
        Emergency current = emergencies.findById(emergency.getId()).orElse(emergency);
        if (assigned != null) {
            return new IntakeResult(current, assigned,
                    selectionFromDispatch(assigned), "Emergency registered and dispatched automatically.");
        }
        SelectionResult selection = planFor(current);
        String reason = current.getDispatchNote() != null ? current.getDispatchNote()
                : "No eligible ambulance/hospital right now. The emergency remains queued.";
        return new IntakeResult(current, null, selection, reason);
    }

    /** Process queued emergencies in priority order. Returns the last assignment made. */
    public synchronized Dispatch processQueue() {
        return processQueueInternal();
    }

    private Dispatch processQueueInternal() {
        Dispatch last = null;
        int guard = 0;
        while (guard++ < 25) {
            List<Emergency> snapshot = queue.snapshot();
            if (snapshot.isEmpty()) break;

            List<Ambulance> allAmbulances = ambulances.findAll();
            List<Hospital> allHospitals = hospitals.findAll();
            boolean assignedSomething = false;
            boolean noAmbulanceAtAll = false;

            for (Emergency queued : snapshot) {
                Optional<Emergency> fresh = emergencies.findById(queued.getId());
                if (fresh.isEmpty() || fresh.get().getStatus() != EmergencyStatus.QUEUED) {
                    queue.remove(queued.getId());
                    continue;
                }
                Emergency emergency = fresh.get();
                SelectionResult selection = planner.plan(emergency, allAmbulances, allHospitals, graph.graph());

                if (selection.selectedAmbulance() == null) {
                    if ("NO_AMBULANCE".equals(selection.failureReason())
                            || "NO_AMBULANCE_AND_HOSPITAL".equals(selection.failureReason())) {
                        String note = "Queued: no available ambulance. Auto-retry runs when a trip completes.";
                        setQueueNote(emergency, note);
                        noAmbulanceAtAll = true;
                        break;
                    }
                    setQueueNote(emergency, selection.explanation());
                    continue;
                }
                if (selection.selectedHospital() == null) {
                    setQueueNote(emergency, "Queued: no eligible hospital. " + selection.explanation());
                    continue;
                }

                Ambulance ambulance = allAmbulances.stream()
                        .filter(a -> a.getId().equals(selection.selectedAmbulance().ambulanceId()))
                        .findFirst().orElse(null);
                Hospital hospital = allHospitals.stream()
                        .filter(h -> h.getId().equals(selection.selectedHospital().hospitalId()))
                        .findFirst().orElse(null);
                if (ambulance == null || hospital == null) continue;

                RouteResult toScene = graph.route(ambulance.getCurrentLat(), ambulance.getCurrentLon(),
                        emergency.getLat(), emergency.getLon());
                RouteResult toHospital = graph.route(emergency.getLat(), emergency.getLon(),
                        hospital.getLat(), hospital.getLon());
                if (!toScene.found() || !toHospital.found()) {
                    setQueueNote(emergency, "Queued: a route disappeared during assignment. Recalculate.");
                    continue;
                }

                Dispatch dispatch = buildDispatch(emergency, ambulance, hospital, toScene, toHospital,
                        selection.explanation(), selection, false);
                try {
                    Dispatch saved = dispatches.createAssignment(dispatch,
                            "Scene / " + emergency.getId());
                    afterAssignment(emergency, ambulance, hospital, saved);
                    queue.remove(emergency.getId());
                    setQueueNote(emergency, null);
                    last = saved;
                    assignedSomething = true;
                    break; // rebuild ambulance list before processing the next queued case
                } catch (IllegalStateException ex) {
                    // Another thread won a race or the emergency is no longer queued.
                    System.err.println("[dispatch] assignment rejected: " + ex.getMessage());
                    Optional<Emergency> check = emergencies.findById(emergency.getId());
                    if (check.isEmpty() || check.get().getStatus() != EmergencyStatus.QUEUED) {
                        queue.remove(emergency.getId());
                    } else {
                        setQueueNote(emergency, "Queued: assignment conflicted, will retry.");
                    }
                }
            }

            if (!assignedSomething) {
                if (noAmbulanceAtAll) break;
                // If every queued case lacked a hospital, stop to avoid a hot loop.
                boolean anyStillQueued = queue.snapshot().stream()
                        .anyMatch(e -> emergencies.findById(e.getId())
                                .map(x -> x.getStatus() == EmergencyStatus.QUEUED).orElse(false));
                if (anyStillQueued) break;
            }
        }
        return last;
    }

    private void afterAssignment(Emergency emergency, Ambulance ambulance, Hospital hospital, Dispatch saved) {
        events.append(emergency.getId(), "ASSIGNED",
                saved.getAmbulanceId() + " + " + hospital.getName() + " assigned automatically");
        events.append(emergency.getId(), "AMBULANCE_EN_ROUTE",
                "Dijkstra route to scene: " + formatDistance(saved.getAmbulanceDistanceMeters())
                        + ", ETA " + saved.getAmbulanceEtaSeconds() + " s (simulated movement)");
    }

    private Dispatch buildDispatch(Emergency emergency, Ambulance ambulance, Hospital hospital,
                                   RouteResult toScene, RouteResult toHospital, String explanation,
                                   SelectionResult selection, boolean manual) {
        Dispatch d = new Dispatch();
        d.setEmergencyId(emergency.getId());
        d.setAmbulanceId(ambulance.getId());
        d.setHospitalId(hospital.getId());
        d.setAmbulanceEtaSeconds((int) Math.ceil(toScene.travelTimeSeconds()));
        d.setHospitalEtaSeconds((int) Math.ceil(toHospital.travelTimeSeconds()));
        d.setAmbulanceDistanceMeters(toScene.distanceMeters());
        d.setHospitalDistanceMeters(toHospital.distanceMeters());
        d.setSelectionReason(explanation);
        d.setAmbulanceRoute(toScene.geometry());
        d.setHospitalRoute(toHospital.geometry());
        Map<String, Object> alternatives = new LinkedHashMap<>();
        alternatives.put("ambulances", selection.ambulanceCandidates());
        alternatives.put("hospitals", selection.hospitalCandidates());
        alternatives.put("manualOverride", manual);
        d.setAlternativesJson(Json.write(alternatives));
        return d;
    }

    /** Manual override requires an explicit reason and validates every eligibility rule. */
    public synchronized Dispatch manualDispatch(String emergencyId, String ambulanceId,
                                                String hospitalId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for a manual override");
        }
        Emergency emergency = emergencies.findById(emergencyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown emergency: " + emergencyId));
        if (emergency.getStatus() != EmergencyStatus.QUEUED) {
            throw new IllegalStateException("Manual override is only possible while the emergency is QUEUED");
        }
        if (dispatches.findActiveByEmergency(emergencyId).isPresent()) {
            throw new IllegalStateException("Emergency already has an active dispatch");
        }

        List<Ambulance> allAmbulances = ambulances.findAll();
        List<Hospital> allHospitals = hospitals.findAll();
        SelectionResult comparison = planner.plan(emergency, allAmbulances, allHospitals, graph.graph());
        Ambulance ambulance = allAmbulances.stream().filter(a -> a.getId().equals(ambulanceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown ambulance: " + ambulanceId));
        Hospital hospital = allHospitals.stream().filter(h -> h.getId().equals(hospitalId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown hospital: " + hospitalId));

        if (ambulance.getStatus() != AmbulanceStatus.AVAILABLE) {
            throw new IllegalStateException("Selected ambulance is not AVAILABLE");
        }
        if (!hospital.isOperational() || !hospital.hasLocation() || !hospital.supports(emergency.getRequiredFacility())
                || hospital.getAvailableBeds() <= 0) {
            throw new IllegalStateException("Selected hospital is not eligible for this emergency");
        }
        RouteResult toScene = graph.route(ambulance.getCurrentLat(), ambulance.getCurrentLon(),
                emergency.getLat(), emergency.getLon());
        RouteResult toHospital = graph.route(emergency.getLat(), emergency.getLon(),
                hospital.getLat(), hospital.getLon());
        if (!toScene.found()) throw new IllegalStateException("No route from ambulance to incident");
        if (!toHospital.found()) throw new IllegalStateException("No route from incident to hospital");

        String explanation = "MANUAL OVERRIDE: " + reason.trim()
                + ". Auto comparison would have chosen " + (comparison.selectedAmbulance() == null ? "none"
                : comparison.selectedAmbulance().ambulanceId()) + " and "
                + (comparison.selectedHospital() == null ? "none" : comparison.selectedHospital().hospitalId()) + ".";
        Dispatch dispatch = buildDispatch(emergency, ambulance, hospital, toScene, toHospital,
                explanation, comparison, true);
        Dispatch saved = dispatches.createAssignment(dispatch, "Scene / " + emergency.getId());
        events.append(emergencyId, "ASSIGNED", explanation);
        events.append(emergencyId, "AMBULANCE_EN_ROUTE", "Manual dispatch; Dijkstra ETA "
                + saved.getAmbulanceEtaSeconds() + " s");
        queue.remove(emergencyId);
        return saved;
    }

    /** Plan (no writes) for the dispatch-intelligence screen. */
    public SelectionResult selectionFor(Emergency emergency) {
        return planner.plan(emergency, ambulances.findAll(), hospitals.findAll(), graph.graph());
    }

    public SelectionResult planFor(Emergency emergency) {
        return selectionFor(emergency);
    }

    public SelectionResult selectionFor(String emergencyId) {
        Emergency emergency = emergencies.findById(emergencyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown emergency: " + emergencyId));
        return selectionFor(emergency);
    }

    public Dispatch retry(String emergencyId) {
        Emergency emergency = emergencies.findById(emergencyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown emergency: " + emergencyId));
        if (emergency.getStatus() == EmergencyStatus.QUEUED && !queue.contains(emergencyId)) {
            queue.add(emergency);
        }
        return processQueue();
    }

    public Dispatch completeTrip(String dispatchId) {
        if (simulation == null) throw new IllegalStateException("Simulator is not running");
        return simulation.completeTrip(dispatchId);
    }

    public void setAmbulanceAvailability(String ambulanceId, boolean available) {
        Ambulance ambulance = ambulances.findById(ambulanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ambulance: " + ambulanceId));
        Optional<Dispatch> active = dispatches.findActiveByAmbulance(ambulanceId);
        if (active.isPresent()) {
            throw new IllegalStateException("Cannot change availability while " + active.get().getId()
                    + " is active. Complete the trip first.");
        }
        AmbulanceStatus status = available ? AmbulanceStatus.AVAILABLE : AmbulanceStatus.OUT_OF_SERVICE;
        ambulance.setStatus(status);
        ambulance.setAssignedEmergencyId(null);
        ambulance.setEtaSeconds(null);
        ambulance.setDestination(null);
        ambulance.setTripStatus(null);
        ambulances.update(ambulance);
        if (available) processQueue();
    }

    public void updateAmbulanceBase(String ambulanceId, String label, double lat, double lon) {
        Ambulance ambulance = ambulances.findById(ambulanceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ambulance: " + ambulanceId));
        ambulance.setBaseLocation(label);
        ambulance.setBaseLat(lat);
        ambulance.setBaseLon(lon);
        if (ambulance.getStatus() == AmbulanceStatus.AVAILABLE) {
            ambulance.setCurrentLocation(label);
            ambulance.setCurrentLat(lat);
            ambulance.setCurrentLon(lon);
        }
        ambulances.update(ambulance);
    }

    public void updateHospital(Hospital hospital) {
        hospitals.update(hospital);
    }

    public SelectionResult getSelection(String emergencyId) {
        return selectionFor(emergencyId);
    }

    private SelectionResult selectionFromDispatch(Dispatch d) {
        try {
            var alternatives = Json.MAPPER.readTree(d.getAlternativesJson());
            var mapper = Json.MAPPER;
            var ambulanceType = mapper.getTypeFactory()
                    .constructCollectionType(List.class, AmbulanceCandidate.class);
            var hospitalType = mapper.getTypeFactory()
                    .constructCollectionType(List.class, HospitalCandidate.class);
            List<AmbulanceCandidate> ambulanceCandidates =
                    mapper.convertValue(alternatives.path("ambulances"), ambulanceType);
            List<HospitalCandidate> hospitalCandidates =
                    mapper.convertValue(alternatives.path("hospitals"), hospitalType);
            AmbulanceCandidate selectedAmbulance = ambulanceCandidates.stream()
                    .filter(c -> c.ambulanceId().equals(d.getAmbulanceId())).findFirst().orElse(null);
            HospitalCandidate selectedHospital = hospitalCandidates.stream()
                    .filter(c -> c.hospitalId().equals(d.getHospitalId())).findFirst().orElse(null);
            return new SelectionResult(selectedAmbulance, selectedHospital, ambulanceCandidates,
                    hospitalCandidates, d.getSelectionReason(), null);
        } catch (Exception ex) {
            return selectionFor(emergencies.findById(d.getEmergencyId()).orElseThrow());
        }
    }

    private void setQueueNote(Emergency emergency, String note) {
        if (note == null || !note.equals(emergency.getDispatchNote())) {
            emergencies.resetQueueNote(emergency.getId(), note);
            emergency.setDispatchNote(note);
        }
    }

    private String formatDistance(double metres) {
        return metres >= 1000 ? String.format("%.1f km", metres / 1000) : Math.round(metres) + " m";
    }
}
