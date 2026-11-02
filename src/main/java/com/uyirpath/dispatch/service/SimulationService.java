package com.uyirpath.dispatch.service;

import com.uyirpath.dispatch.graph.GeoMath;
import com.uyirpath.dispatch.graph.RouteResult;
import com.uyirpath.dispatch.model.Ambulance;
import com.uyirpath.dispatch.model.AmbulanceStatus;
import com.uyirpath.dispatch.model.Dispatch;
import com.uyirpath.dispatch.model.Emergency;
import com.uyirpath.dispatch.model.EmergencyStatus;
import com.uyirpath.dispatch.model.GeoPoint;
import com.uyirpath.dispatch.model.Hospital;
import com.uyirpath.dispatch.model.TripPhase;
import com.uyirpath.dispatch.repository.AmbulanceRepository;
import com.uyirpath.dispatch.repository.DispatchRepository;
import com.uyirpath.dispatch.repository.EmergencyRepository;
import com.uyirpath.dispatch.repository.EventRepository;
import com.uyirpath.dispatch.repository.HospitalRepository;
import com.uyirpath.dispatch.repository.SettingsRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Demonstration ambulance movement simulator.
 *
 * <p>There is no physical GPS feed. Positions are interpolated along the
 * geometry returned by our Dijkstra engine, and every API response carries the
 * SIMULATED flag. Phase transitions update the emergency lifecycle, persist
 * ambulance positions and finally release the ambulance at its base.</p>
 */
public class SimulationService {
    private static final long SCENE_DWELL_MS = 7_000;
    private static final long HANDOVER_DWELL_MS = 5_000;

    private final DispatchRepository dispatches;
    private final AmbulanceRepository ambulances;
    private final EmergencyRepository emergencies;
    private final HospitalRepository hospitals;
    private final EventRepository events;
    private final GraphService graph;
    private final SettingsRepository settings;
    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "uyirpath-simulator");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Runnable onTripCompleted;
    private boolean started;

    public SimulationService(DispatchRepository dispatches, AmbulanceRepository ambulances,
                             EmergencyRepository emergencies, HospitalRepository hospitals,
                             EventRepository events, GraphService graph, SettingsRepository settings) {
        this.dispatches = dispatches;
        this.ambulances = ambulances;
        this.emergencies = emergencies;
        this.hospitals = hospitals;
        this.events = events;
        this.graph = graph;
        this.settings = settings;
    }

    public void setOnTripCompleted(Runnable callback) { this.onTripCompleted = callback; }

    public void start() {
        synchronized (lock) {
            if (started) return;
            started = true;
            resumeActiveTrips();
        }
        scheduler.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public boolean isPaused() {
        return Boolean.parseBoolean(settings.get("simulation_paused", "false"));
    }

    public double speed() {
        try {
            return Math.max(1, Math.min(120, Double.parseDouble(settings.get("simulation_speed", "8"))));
        } catch (NumberFormatException ex) {
            return 8;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("simulated", true);
        status.put("paused", isPaused());
        status.put("speed", speed());
        status.put("activeTrips", dispatches.findActive().size());
        status.put("notice", "SIMULATED movement along the Java Dijkstra route. No live GPS or traffic feed.");
        return status;
    }

    public void setPaused(boolean paused) {
        settings.put("simulation_paused", String.valueOf(paused));
    }

    public void setSpeed(double speed) {
        settings.put("simulation_speed", String.valueOf(Math.max(1, Math.min(120, speed))));
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception ex) {
            System.err.println("[sim] tick failed: " + ex.getMessage());
        }
    }

    private void tick() {
        if (isPaused()) return;
        boolean retryQueue = false;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Dispatch dispatch : dispatches.findActive()) {
                try {
                    if (dispatch.getRouteWarning() != null && !dispatch.getRouteWarning().isBlank()) {
                        continue;
                    }
                    boolean completed = advance(dispatch, now);
                    retryQueue |= completed;
                } catch (Exception ex) {
                    System.err.println("[sim] dispatch " + dispatch.getId() + ": " + ex.getMessage());
                }
            }
        }
        if (retryQueue && onTripCompleted != null) {
            onTripCompleted.run();
        }
    }

    /** @return true when a trip completed and the dispatch queue should be retried. */
    private boolean advance(Dispatch d, long now) {
        Optional<Emergency> emergencyOpt = emergencies.findById(d.getEmergencyId());
        Optional<Ambulance> ambulanceOpt = ambulances.findById(d.getAmbulanceId());
        if (emergencyOpt.isEmpty() || ambulanceOpt.isEmpty()) return false;
        Emergency emergency = emergencyOpt.get();
        Ambulance ambulance = ambulanceOpt.get();
        double speed = speed();

        switch (d.getPhase()) {
            case TO_SCENE -> {
                double fraction = fraction(d, now, speed);
                GeoPoint p = pointAt(d.getAmbulanceRoute(), fraction);
                int remaining = Math.max(0, (int) Math.ceil(d.getPhaseDurationSeconds() * (1 - fraction)));
                if (p != null) updatePosition(ambulance, p, remaining, "EN_ROUTE_TO_SCENE",
                        "Simulated: en route to scene");
                ambulance.setStatus(AmbulanceStatus.EN_ROUTE);
                if (fraction >= 1) {
                    atScene(d, emergency, ambulance, now);
                }
            }
            case AT_SCENE -> {
                if (now - d.getPhaseStartedAt() >= SCENE_DWELL_MS) {
                    toHospital(d, emergency, ambulance, now);
                }
            }
            case TO_HOSPITAL -> {
                double fraction = fraction(d, now, speed);
                GeoPoint p = pointAt(d.getHospitalRoute(), fraction);
                int remaining = Math.max(0, (int) Math.ceil(d.getPhaseDurationSeconds() * (1 - fraction)));
                if (p != null) updatePosition(ambulance, p, remaining, "TRANSPORTING_TO_HOSPITAL",
                        "Simulated: transporting to hospital");
                ambulance.setStatus(AmbulanceStatus.TRANSPORTING);
                if (fraction >= 1) {
                    atHospital(d, emergency, ambulance, now);
                }
            }
            case AT_HOSPITAL -> {
                if (now - d.getPhaseStartedAt() >= HANDOVER_DWELL_MS) {
                    returnToBase(d, emergency, ambulance, now);
                }
            }
            case RETURNING -> {
                double fraction = fraction(d, now, speed);
                GeoPoint p = pointAt(d.getReturnRoute(), fraction);
                int remaining = Math.max(0, (int) Math.ceil(d.getPhaseDurationSeconds() * (1 - fraction)));
                if (p != null) updatePosition(ambulance, p, remaining, "RETURNING_TO_BASE",
                        "Simulated: returning to base");
                ambulance.setStatus(AmbulanceStatus.RETURNING);
                if (fraction >= 1) {
                    complete(d, emergency, ambulance, true, "Simulation: returned to base");
                    return true;
                }
            }
            default -> { }
        }
        dispatches.updateSimulation(d);
        return false;
    }

    private void atScene(Dispatch d, Emergency emergency, Ambulance ambulance, long now) {
        d.setPhase(TripPhase.AT_SCENE);
        d.setPhaseStartedAt(now);
        d.setPhaseDurationSeconds(0);
        GeoPoint scene = pointAt(d.getAmbulanceRoute(), 1);
        if (scene != null) {
            ambulance.setCurrentLat(scene.lat());
            ambulance.setCurrentLon(scene.lon());
            ambulance.setCurrentLocation("Simulated: at incident scene");
        }
        ambulance.setStatus(AmbulanceStatus.AT_SCENE);
        ambulance.setEtaSeconds(0);
        ambulance.setTripStatus("AT_SCENE");
        ambulances.update(ambulance);

        if (emergency.getStatus().canTransitionTo(EmergencyStatus.ARRIVED_AT_SCENE)) {
            emergencies.updateStatus(emergency.getId(), EmergencyStatus.ARRIVED_AT_SCENE,
                    "Ambulance arrived at scene (simulation)");
            events.append(emergency.getId(), "ARRIVED_AT_SCENE", "Simulated ambulance arrival at scene");
        }
    }

    private void toHospital(Dispatch d, Emergency emergency, Ambulance ambulance, long now) {
        Optional<Hospital> hospitalOpt = hospitals.findById(d.getHospitalId());
        Hospital hospital = hospitalOpt.orElse(null);
        d.setPhase(TripPhase.TO_HOSPITAL);
        d.setPhaseStartedAt(now);
        d.setPhaseDurationSeconds(Math.max(1, d.getHospitalEtaSeconds()));
        ambulance.setStatus(AmbulanceStatus.TRANSPORTING);
        ambulance.setTripStatus("TRANSPORTING_TO_HOSPITAL");
        ambulance.setDestination(hospital == null ? "Assigned hospital" : hospital.getName());
        ambulance.setEtaSeconds(d.getHospitalEtaSeconds());
        ambulances.update(ambulance);

        if (emergency.getStatus().canTransitionTo(EmergencyStatus.TRANSPORTING)) {
            emergencies.updateStatus(emergency.getId(), EmergencyStatus.TRANSPORTING,
                    "Transporting patient (simulation)");
            events.append(emergency.getId(), "TRANSPORTING",
                    "Simulated transport to " + (hospital == null ? "hospital" : hospital.getName()));
        }
    }

    private void atHospital(Dispatch d, Emergency emergency, Ambulance ambulance, long now) {
        d.setPhase(TripPhase.AT_HOSPITAL);
        d.setPhaseStartedAt(now);
        d.setPhaseDurationSeconds(0);
        GeoPoint hospitalPoint = pointAt(d.getHospitalRoute(), 1);
        if (hospitalPoint != null) {
            ambulance.setCurrentLat(hospitalPoint.lat());
            ambulance.setCurrentLon(hospitalPoint.lon());
            ambulance.setCurrentLocation("Simulated: at hospital handover");
        }
        ambulance.setStatus(AmbulanceStatus.TRANSPORTING);
        ambulance.setTripStatus("AT_HOSPITAL_HANDOVER");
        ambulance.setEtaSeconds(0);
        ambulances.update(ambulance);

        if (emergency.getStatus().canTransitionTo(EmergencyStatus.ARRIVED_AT_HOSPITAL)) {
            emergencies.updateStatus(emergency.getId(), EmergencyStatus.ARRIVED_AT_HOSPITAL,
                    "Arrived at hospital (simulation)");
            events.append(emergency.getId(), "ARRIVED_AT_HOSPITAL",
                    "Simulated arrival at hospital handover");
        }
        if (Boolean.parseBoolean(settings.get("demo_admission", "true"))) {
            hospitals.admitOne(d.getHospitalId());
        }
    }

    private void returnToBase(Dispatch d, Emergency emergency, Ambulance ambulance, long now) {
        boolean alwaysReturn = !"RELEASE_AFTER_HANDOVER".equalsIgnoreCase(
                settings.get("return_policy", "ALWAYS_RETURN"));
        if (!alwaysReturn) {
            complete(d, emergency, ambulance, false, "Return policy: released after handover");
            return;
        }
        Optional<Hospital> hospital = hospitals.findById(d.getHospitalId());
        double fromLat = hospital.map(Hospital::getLat).orElse(ambulance.getCurrentLat());
        double fromLon = hospital.map(Hospital::getLon).orElse(ambulance.getCurrentLon());
        if (hospital.isEmpty() || !hospital.get().hasLocation()) {
            fromLat = ambulance.getCurrentLat();
            fromLon = ambulance.getCurrentLon();
        }
        RouteResult route = graph.route(fromLat, fromLon, ambulance.getBaseLat(), ambulance.getBaseLon());
        if (!route.found()) {
            d.setRouteWarning("No return route to base. Ambulance remains at hospital handover. " +
                    "Reopen closed roads or use manual completion.");
            d.setPhaseStartedAt(now);
            dispatches.updateSimulation(d);
            return;
        }
        d.setReturnRoute(route.geometry());
        d.setPhase(TripPhase.RETURNING);
        d.setPhaseStartedAt(now);
        d.setPhaseDurationSeconds(Math.max(1, route.travelTimeSeconds()));
        d.setRouteWarning(null);
        ambulance.setStatus(AmbulanceStatus.RETURNING);
        ambulance.setDestination(ambulance.getBaseLocation());
        ambulance.setTripStatus("RETURNING_TO_BASE");
        ambulance.setEtaSeconds((int) Math.ceil(route.travelTimeSeconds()));
        ambulances.update(ambulance);
        events.append(emergency.getId(), "RETURNING", "Ambulance returning to base (simulation)");
    }

    private void complete(Dispatch d, Emergency emergency, Ambulance ambulance,
                          boolean atBase, String note) {
        if (atBase) {
            ambulances.release(ambulance.getId(), ambulance.getBaseLat(), ambulance.getBaseLon(),
                    ambulance.getBaseLocation());
        } else {
            ambulance.setStatus(AmbulanceStatus.AVAILABLE);
            ambulance.setAssignedEmergencyId(null);
            ambulance.setDestination(null);
            ambulance.setEtaSeconds(null);
            ambulance.setTripStatus(null);
            ambulances.update(ambulance);
        }
        d.setPhase(TripPhase.COMPLETED);
        dispatches.complete(d.getId());
        if (!emergency.getStatus().isTerminal()) {
            emergencies.updateStatus(emergency.getId(), EmergencyStatus.COMPLETED, note);
            emergencies.complete(emergency.getId());
            events.append(emergency.getId(), "COMPLETED", note);
        }
    }

    /** Force completion is only allowed after hospital handover so lifecycle order is preserved. */
    public Dispatch completeTrip(String dispatchId) {
        boolean retry;
        synchronized (lock) {
            Dispatch d = dispatches.findActive().stream()
                    .filter(x -> x.getId().equals(dispatchId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No active dispatch: " + dispatchId));
            Emergency emergency = emergencies.findById(d.getEmergencyId())
                    .orElseThrow(() -> new IllegalArgumentException("Emergency not found"));
            if (emergency.getStatus() != EmergencyStatus.ARRIVED_AT_HOSPITAL) {
                throw new IllegalStateException(
                        "Manual completion is allowed only after ARRIVED_AT_HOSPITAL. Current status: "
                                + emergency.getStatus());
            }
            Ambulance ambulance = ambulances.findById(d.getAmbulanceId())
                    .orElseThrow(() -> new IllegalArgumentException("Ambulance not found"));
            complete(d, emergency, ambulance, false, "Trip completed manually by operator");
            retry = true;
        }
        if (retry && onTripCompleted != null) onTripCompleted.run();
        return dispatches.findById(dispatchId).orElseThrow();
    }

    /**
     * Recompute every active trip after a closure or simulated traffic change.
     * Routes are recalculated from the ambulance's current position so the ETA
     * and drawn geometry reflect the new network. If no path exists the trip is
     * held and an explicit warning is persisted.
     */
    public List<Map<String, Object>> recalculateActiveRoutes() {
        List<Map<String, Object>> results = new ArrayList<>();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Dispatch d : dispatches.findActive()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dispatchId", d.getId());
                row.put("emergencyId", d.getEmergencyId());
                Optional<Emergency> emergencyOpt = emergencies.findById(d.getEmergencyId());
                Optional<Ambulance> ambulanceOpt = ambulances.findById(d.getAmbulanceId());
                if (emergencyOpt.isEmpty() || ambulanceOpt.isEmpty()) {
                    row.put("ok", false);
                    row.put("reason", "Related record missing");
                    results.add(row);
                    continue;
                }
                Emergency emergency = emergencyOpt.get();
                Ambulance ambulance = ambulanceOpt.get();
                row.put("phase", d.getPhase().name());

                if (d.getPhase() == TripPhase.AT_SCENE || d.getPhase() == TripPhase.AT_HOSPITAL) {
                    // These are fixed dwell phases; still validate the next leg.
                    boolean ok = validateNextLegs(d, emergency);
                    row.put("ok", ok);
                    row.put("reason", ok ? "Dwell phase unchanged; onward routes verified"
                            : d.getRouteWarning());
                    dispatches.updateSimulation(d);
                    results.add(row);
                    continue;
                }

                RouteResult currentLeg;
                String targetLabel;
                if (d.getPhase() == TripPhase.TO_HOSPITAL) {
                    Optional<Hospital> hospital = hospitals.findById(d.getHospitalId());
                    if (hospital.isEmpty() || !hospital.get().hasLocation()) {
                        d.setRouteWarning("Assigned hospital has no verified location");
                        dispatches.updateSimulation(d);
                        row.put("ok", false);
                        row.put("reason", d.getRouteWarning());
                        results.add(row);
                        continue;
                    }
                    currentLeg = graph.route(ambulance.getCurrentLat(), ambulance.getCurrentLon(),
                            hospital.get().getLat(), hospital.get().getLon());
                    targetLabel = "hospital";
                } else if (d.getPhase() == TripPhase.RETURNING) {
                    currentLeg = graph.route(ambulance.getCurrentLat(), ambulance.getCurrentLon(),
                            ambulance.getBaseLat(), ambulance.getBaseLon());
                    targetLabel = "base";
                } else {
                    currentLeg = graph.route(ambulance.getCurrentLat(), ambulance.getCurrentLon(),
                            emergency.getLat(), emergency.getLon());
                    targetLabel = "scene";
                }

                if (!currentLeg.found()) {
                    d.setRouteWarning("NO ALTERNATIVE ROUTE to " + targetLabel
                            + " after the road-network change. Trip held; reopen a road and recalculate.");
                    dispatches.updateSimulation(d);
                    row.put("ok", false);
                    row.put("reason", d.getRouteWarning());
                    results.add(row);
                    continue;
                }

                if (d.getPhase() == TripPhase.TO_HOSPITAL) {
                    d.setHospitalRoute(currentLeg.geometry());
                    d.setHospitalEtaSeconds((int) Math.ceil(currentLeg.travelTimeSeconds()));
                    d.setHospitalDistanceMeters(currentLeg.distanceMeters());
                } else if (d.getPhase() == TripPhase.RETURNING) {
                    d.setReturnRoute(currentLeg.geometry());
                } else {
                    d.setAmbulanceRoute(currentLeg.geometry());
                    d.setAmbulanceEtaSeconds((int) Math.ceil(currentLeg.travelTimeSeconds()));
                    d.setAmbulanceDistanceMeters(currentLeg.distanceMeters());
                }
                d.setPhaseStartedAt(now);
                d.setPhaseDurationSeconds(Math.max(1, currentLeg.travelTimeSeconds()));
                d.setRouteWarning(null);
                boolean nextLegsOk = validateNextLegs(d, emergency);
                dispatches.updateSimulation(d);
                row.put("ok", nextLegsOk);
                row.put("etaSeconds", (int) Math.ceil(currentLeg.travelTimeSeconds()));
                row.put("distanceMeters", currentLeg.distanceMeters());
                row.put("reason", nextLegsOk ? "Route recalculated" : d.getRouteWarning());
                results.add(row);
            }
        }
        return results;
    }

    private boolean validateNextLegs(Dispatch d, Emergency emergency) {
        // Validate the scene->hospital leg; return leg is validated when it starts.
        Optional<Hospital> hospital = hospitals.findById(d.getHospitalId());
        if (hospital.isEmpty() || !hospital.get().hasLocation()) {
            d.setRouteWarning("Assigned hospital has no verified location");
            return false;
        }
        RouteResult hospitalRoute = graph.route(emergency.getLat(), emergency.getLon(),
                hospital.get().getLat(), hospital.get().getLon());
        if (!hospitalRoute.found()) {
            d.setRouteWarning("NO ALTERNATIVE ROUTE from scene to the assigned hospital. Trip held.");
            return false;
        }
        d.setHospitalRoute(hospitalRoute.geometry());
        d.setHospitalEtaSeconds((int) Math.ceil(hospitalRoute.travelTimeSeconds()));
        d.setHospitalDistanceMeters(hospitalRoute.distanceMeters());
        d.setRouteWarning(null);
        return true;
    }

    /** Restart active trips from the current stored position after a server restart. */
    public void resumeActiveTrips() {
        long now = System.currentTimeMillis();
        for (Dispatch d : dispatches.findActive()) {
            try {
                Optional<Emergency> emergency = emergencies.findById(d.getEmergencyId());
                Optional<Ambulance> ambulance = ambulances.findById(d.getAmbulanceId());
                if (emergency.isEmpty() || ambulance.isEmpty()) continue;
                d.setPhaseStartedAt(now);
                switch (d.getPhase()) {
                    case TO_SCENE -> {
                        RouteResult route = graph.route(ambulance.get().getCurrentLat(),
                                ambulance.get().getCurrentLon(), emergency.get().getLat(), emergency.get().getLon());
                        if (route.found()) {
                            d.setAmbulanceRoute(route.geometry());
                            d.setAmbulanceEtaSeconds((int) Math.ceil(route.travelTimeSeconds()));
                            d.setPhaseDurationSeconds(Math.max(1, route.travelTimeSeconds()));
                            d.setRouteWarning(null);
                            ambulances.updateAssignment(ambulance.get().getId(), AmbulanceStatus.EN_ROUTE,
                                    d.getEmergencyId(), "Scene / " + d.getEmergencyId(),
                                    d.getAmbulanceEtaSeconds(), "EN_ROUTE_TO_SCENE");
                        } else {
                            d.setRouteWarning("No route to scene on restart. Review road closures.");
                        }
                    }
                    case TO_HOSPITAL -> {
                        Optional<Hospital> hospital = hospitals.findById(d.getHospitalId());
                        if (hospital.isPresent() && hospital.get().hasLocation()) {
                            RouteResult route = graph.route(ambulance.get().getCurrentLat(),
                                    ambulance.get().getCurrentLon(), hospital.get().getLat(), hospital.get().getLon());
                            if (route.found()) {
                                d.setHospitalRoute(route.geometry());
                                d.setHospitalEtaSeconds((int) Math.ceil(route.travelTimeSeconds()));
                                d.setPhaseDurationSeconds(Math.max(1, route.travelTimeSeconds()));
                                d.setRouteWarning(null);
                            } else {
                                d.setRouteWarning("No route to hospital on restart.");
                            }
                        }
                    }
                    case RETURNING -> {
                        RouteResult route = graph.route(ambulance.get().getCurrentLat(),
                                ambulance.get().getCurrentLon(), ambulance.get().getBaseLat(),
                                ambulance.get().getBaseLon());
                        if (route.found()) {
                            d.setReturnRoute(route.geometry());
                            d.setPhaseDurationSeconds(Math.max(1, route.travelTimeSeconds()));
                            d.setRouteWarning(null);
                        } else {
                            d.setRouteWarning("No return route on restart.");
                        }
                    }
                    default -> { }
                }
                dispatches.updateSimulation(d);
            } catch (Exception ex) {
                System.err.println("[sim] resume " + d.getId() + ": " + ex.getMessage());
            }
        }
    }

    public double progress(Dispatch d) {
        if (d == null) return 0;
        if (d.getPhase() == TripPhase.AT_SCENE || d.getPhase() == TripPhase.AT_HOSPITAL
                || d.getPhase() == TripPhase.COMPLETED) return 1;
        if (d.getPhase() == TripPhase.ROUTE_BLOCKED) return 0;
        if (d.getPhaseDurationSeconds() <= 0) return 0;
        double elapsed = (System.currentTimeMillis() - d.getPhaseStartedAt()) / 1000.0 * speed();
        return Math.max(0, Math.min(1, elapsed / d.getPhaseDurationSeconds()));
    }

    public Map<String, Object> simulationInfo(Dispatch d) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("simulated", true);
        info.put("phase", d.getPhase().name());
        info.put("progress", progress(d));
        info.put("phaseStartedAt", d.getPhaseStartedAt());
        info.put("phaseDurationSeconds", d.getPhaseDurationSeconds());
        info.put("paused", isPaused());
        info.put("speed", speed());
        info.put("warning", d.getRouteWarning());
        return info;
    }

    private void updatePosition(Ambulance a, GeoPoint p, int etaSeconds,
                                String tripStatus, String location) {
        a.setCurrentLat(p.lat());
        a.setCurrentLon(p.lon());
        a.setCurrentLocation(location);
        a.setEtaSeconds(etaSeconds);
        a.setTripStatus(tripStatus);
        a.setUpdatedAt(Instant.now().toString());
        ambulances.updatePosition(a.getId(), p.lat(), p.lon(), location, etaSeconds, tripStatus);
    }

    private double fraction(Dispatch d, long now, double speed) {
        if (d.getPhaseDurationSeconds() <= 0) return 1;
        double elapsed = (now - d.getPhaseStartedAt()) / 1000.0 * speed;
        return Math.max(0, Math.min(1, elapsed / d.getPhaseDurationSeconds()));
    }

    /** Interpolate a position by fraction of total route length. */
    static GeoPoint pointAt(List<GeoPoint> route, double fraction) {
        if (route == null || route.isEmpty()) return null;
        if (route.size() == 1 || fraction <= 0) return route.get(0);
        if (fraction >= 1) return route.get(route.size() - 1);
        double total = 0;
        double[] cumulative = new double[route.size()];
        for (int i = 1; i < route.size(); i++) {
            GeoPoint a = route.get(i - 1);
            GeoPoint b = route.get(i);
            total += GeoMath.haversine(a.lat(), a.lon(), b.lat(), b.lon());
            cumulative[i] = total;
        }
        double target = total * fraction;
        for (int i = 1; i < route.size(); i++) {
            if (cumulative[i] >= target) {
                GeoPoint a = route.get(i - 1);
                GeoPoint b = route.get(i);
                double segment = cumulative[i] - cumulative[i - 1];
                double t = segment <= 0 ? 0 : (target - cumulative[i - 1]) / segment;
                return new GeoPoint(a.lat() + (b.lat() - a.lat()) * t,
                        a.lon() + (b.lon() - a.lon()) * t);
            }
        }
        return route.get(route.size() - 1);
    }
}
