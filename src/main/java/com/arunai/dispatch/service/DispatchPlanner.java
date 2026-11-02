package com.arunai.dispatch.service;

import com.arunai.dispatch.graph.RoadGraph;
import com.arunai.dispatch.graph.RouteResult;
import com.arunai.dispatch.model.Ambulance;
import com.arunai.dispatch.model.Emergency;
import com.arunai.dispatch.model.Hospital;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure matching logic: compares available ambulances and eligible hospitals.
 *
 * <p>Keeping this separate from database writes makes the rule easy to test and
 * lets the UI explain why a particular resource was selected.</p>
 */
public class DispatchPlanner {

    public SelectionResult plan(Emergency emergency, List<Ambulance> ambulances,
                                List<Hospital> hospitals, RoadGraph graph) {
        if (emergency.getSnappedNodeId() == null) {
            return new SelectionResult(null, null, List.of(), List.of(),
                    "Incident could not be snapped to the road graph.",
                    "NO_SNAP: incident location is not near any imported road");
        }

        long incidentNode = emergency.getSnappedNodeId();
        List<AmbulanceCandidate> ambulanceCandidates = new ArrayList<>();
        List<Ambulance> selectable = new ArrayList<>();

        for (Ambulance ambulance : ambulances) {
            if (ambulance.getStatus() == null || !ambulance.getStatus().isAvailable()) {
                ambulanceCandidates.add(new AmbulanceCandidate(ambulance.getId(), ambulance.getBaseLocation(),
                        false, false, -1, 0,
                        "EXCLUDED: status " + (ambulance.getStatus() == null ? "UNKNOWN" : ambulance.getStatus())));
                continue;
            }
            long source = graph.nearestNode(ambulance.getCurrentLat(), ambulance.getCurrentLon())
                    .map(n -> n.id()).orElse(-1L);
            if (source < 0) {
                ambulanceCandidates.add(new AmbulanceCandidate(ambulance.getId(), ambulance.getBaseLocation(),
                        true, false, -1, 0, "EXCLUDED: no nearby road node"));
                continue;
            }
            RouteResult route = graph.route(source, incidentNode);
            if (!route.found()) {
                ambulanceCandidates.add(new AmbulanceCandidate(ambulance.getId(), ambulance.getBaseLocation(),
                        true, false, -1, 0, "EXCLUDED: no route to scene"));
                continue;
            }
            int eta = (int) Math.ceil(route.travelTimeSeconds());
            ambulanceCandidates.add(new AmbulanceCandidate(ambulance.getId(), ambulance.getBaseLocation(),
                    true, true, eta, route.distanceMeters(), "ELIGIBLE"));
            selectable.add(ambulance);
        }

        ambulanceCandidates.sort(Comparator
                .comparing((AmbulanceCandidate c) -> !c.available())
                .thenComparing(c -> !c.routeFound())
                .thenComparingInt(c -> c.etaSeconds() < 0 ? Integer.MAX_VALUE : c.etaSeconds()));

        AmbulanceCandidate selectedAmbulance = ambulanceCandidates.stream()
                .filter(c -> c.available() && c.routeFound())
                .findFirst().orElse(null);

        List<HospitalCandidate> hospitalCandidates = new ArrayList<>();
        for (Hospital hospital : hospitals) {
            boolean operational = hospital.isOperational();
            boolean hasLocation = hospital.hasLocation();
            boolean facility = hospital.supports(emergency.getRequiredFacility());
            int beds = hospital.getAvailableBeds();
            String reason;
            boolean eligible = true;

            if (!operational) { eligible = false; reason = "EXCLUDED: not operational"; }
            else if (!hasLocation) { eligible = false; reason = "EXCLUDED: location not verified/configured"; }
            else if (!facility) { eligible = false; reason = "EXCLUDED: does not offer " + emergency.getRequiredFacility(); }
            else if (beds <= 0) { eligible = false; reason = "EXCLUDED: no demonstration beds available"; }
            else { reason = "ELIGIBLE"; }

            RouteResult route = null;
            if (eligible) {
                long hospitalNode = graph.nearestNode(hospital.getLat(), hospital.getLon())
                        .map(n -> n.id()).orElse(-1L);
                route = hospitalNode < 0 ? RouteResult.notFound(-1, -1)
                        : graph.route(incidentNode, hospitalNode);
                if (!route.found()) {
                    eligible = false;
                    reason = "EXCLUDED: no road route from incident";
                }
            }

            int eta = route != null && route.found() ? (int) Math.ceil(route.travelTimeSeconds()) : -1;
            double distance = route != null && route.found() ? route.distanceMeters() : 0;
            hospitalCandidates.add(new HospitalCandidate(hospital.getId(), hospital.getName(), operational,
                    hasLocation, facility, beds, route != null && route.found(), eta, distance,
                    eligible, reason));
        }

        hospitalCandidates.sort(Comparator
                .comparing((HospitalCandidate c) -> !c.eligible())
                .thenComparingInt(c -> c.etaSeconds() < 0 ? Integer.MAX_VALUE : c.etaSeconds()));

        HospitalCandidate selectedHospital = hospitalCandidates.stream()
                .filter(HospitalCandidate::eligible)
                .findFirst().orElse(null);

        String failure = null;
        if (selectedAmbulance == null && selectedHospital == null) {
            failure = "NO_AMBULANCE_AND_HOSPITAL";
        } else if (selectedAmbulance == null) {
            failure = "NO_AMBULANCE";
        } else if (selectedHospital == null) {
            failure = "NO_HOSPITAL";
        }

        String explanation;
        if (selectedAmbulance != null && selectedHospital != null) {
            long availableCount = ambulanceCandidates.stream().filter(c -> c.available() && c.routeFound()).count();
            explanation = selectedAmbulance.ambulanceId() + " has the lowest estimated response time ("
                    + format(selectedAmbulance.etaSeconds()) + ") among " + availableCount
                    + " available ambulance(s). " + selectedHospital.name() + " is the fastest eligible "
                    + emergency.getRequiredFacility() + " hospital with " + selectedHospital.availableBeds()
                    + " demonstration beds (ETA " + format(selectedHospital.etaSeconds()) + "). "
                    + "Selection is based on our Java Dijkstra travel-time calculation, not straight-line distance.";
        } else if (selectedAmbulance == null) {
            long others = ambulanceCandidates.stream().filter(c -> !c.available()).count();
            explanation = "No available ambulance can be routed to this incident. "
                    + others + " ambulance(s) are busy or out of service. The emergency stays queued and will be retried.";
        } else {
            explanation = "An ambulance is available, but no hospital currently meets the facility, "
                    + "operational, bed and road-route requirements. The emergency stays queued; "
                    + "review demonstration hospital data or reopen closed roads.";
        }

        return new SelectionResult(selectedAmbulance, selectedHospital, ambulanceCandidates,
                hospitalCandidates, explanation, failure);
    }

    private String format(int seconds) {
        if (seconds < 60) return seconds + " s";
        return String.format("%d min %02d s", seconds / 60, seconds % 60);
    }
}
