package com.arunai.dispatch.service;

import com.arunai.dispatch.TestGraphs;
import com.arunai.dispatch.graph.RoadGraph;
import com.arunai.dispatch.model.Ambulance;
import com.arunai.dispatch.model.AmbulanceStatus;
import com.arunai.dispatch.model.Emergency;
import com.arunai.dispatch.model.EmergencyStatus;
import com.arunai.dispatch.model.Facility;
import com.arunai.dispatch.model.Hospital;
import com.arunai.dispatch.model.IncidentType;
import com.arunai.dispatch.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DispatchPlannerTest {

    private final DispatchPlanner planner = new DispatchPlanner();

    private static Emergency emergencyAt(long nodeId, double lat, double lon, Facility facility) {
        Emergency e = new Emergency();
        e.setId("EMG-TEST");
        e.setSeq(1);
        e.setType(IncidentType.GENERAL_MEDICAL);
        e.setSeverity(Severity.CRITICAL);
        e.setRequiredFacility(facility);
        e.setLat(lat);
        e.setLon(lon);
        e.setStatus(EmergencyStatus.QUEUED);
        e.setSnappedNodeId(nodeId);
        return e;
    }

    private static Ambulance ambulance(String id, String label, long nodeId, AmbulanceStatus status) {
        double lat = TestGraphs.point(nodeId).lat();
        double lon = TestGraphs.point(nodeId).lon();
        return new Ambulance(id, label, lat, lon, label, lat, lon, status, "2026-01-01T00:00:00Z");
    }

    private static Hospital hospital(String id, String name, Double lat, Double lon,
                                     List<Facility> facilities, int total, int occupied,
                                     boolean operational) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setName(name);
        h.setLat(lat);
        h.setLon(lon);
        h.setFacilities(facilities);
        h.setTotalBeds(total);
        h.setOccupiedBeds(occupied);
        h.setOperational(operational);
        return h;
    }

    @Test
    @DisplayName("Planner selects the available ambulance with the lowest Dijkstra ETA")
    void selectsFastestAvailableAmbulance() {
        RoadGraph graph = TestGraphs.fork();
        Emergency emergency = emergencyAt(TestGraphs.N3, 11.3400, 77.7200, Facility.GENERAL);

        List<Ambulance> ambulances = List.of(
                ambulance("AMB-FAR", "Far base", TestGraphs.N1, AmbulanceStatus.AVAILABLE),
                ambulance("AMB-NEAR", "Near base", TestGraphs.N2, AmbulanceStatus.AVAILABLE),
                ambulance("AMB-BUSY", "Busy unit", TestGraphs.N1, AmbulanceStatus.EN_ROUTE),
                ambulance("AMB-OOS", "Out of service", TestGraphs.N1, AmbulanceStatus.OUT_OF_SERVICE));

        SelectionResult result = planner.plan(emergency, ambulances,
                List.of(hospital("HOSP-1", "General Hospital", 11.3400, 77.7000,
                        List.of(Facility.GENERAL), 20, 0, true)), graph);

        assertNotNull(result.selectedAmbulance());
        assertEquals("AMB-NEAR", result.selectedAmbulance().ambulanceId());
        assertEquals(100, result.selectedAmbulance().etaSeconds());
        AmbulanceCandidate busy = result.ambulanceCandidates().stream()
                .filter(c -> c.ambulanceId().equals("AMB-BUSY")).findFirst().orElseThrow();
        assertFalse(busy.available());
        assertTrue(busy.rankReason().startsWith("EXCLUDED: status"));
        AmbulanceCandidate oos = result.ambulanceCandidates().stream()
                .filter(c -> c.ambulanceId().equals("AMB-OOS")).findFirst().orElseThrow();
        assertFalse(oos.available());
    }

    @Test
    @DisplayName("Planner excludes unverified, incompatible, closed and full hospitals")
    void excludesIneligibleHospitals() {
        RoadGraph graph = TestGraphs.fork();
        Emergency emergency = emergencyAt(TestGraphs.N3, 11.3400, 77.7200, Facility.GENERAL);

        List<Hospital> hospitals = List.of(
                hospital("HOSP-NOLOC", "Unverified Hospital", null, null,
                        List.of(Facility.GENERAL), 50, 0, true),
                hospital("HOSP-CARDIAC", "Cardiac Only", 11.3400, 77.7000,
                        List.of(Facility.CARDIAC), 20, 0, true),
                hospital("HOSP-FULL", "Full Hospital", 11.3400, 77.7100,
                        List.of(Facility.GENERAL), 10, 10, true),
                hospital("HOSP-CLOSED", "Closed Hospital", 11.3400, 77.7000,
                        List.of(Facility.GENERAL), 20, 0, false),
                hospital("HOSP-GOOD", "Eligible General Hospital", 11.3400, 77.7000,
                        List.of(Facility.GENERAL, Facility.TRAUMA), 12, 2, true));

        SelectionResult result = planner.plan(emergency,
                List.of(ambulance("AMB-01", "Base", TestGraphs.N1, AmbulanceStatus.AVAILABLE)),
                hospitals, graph);

        assertNotNull(result.selectedHospital());
        assertEquals("HOSP-GOOD", result.selectedHospital().hospitalId());
        assertEquals(10, result.selectedHospital().availableBeds());
        assertEquals(200, result.selectedHospital().etaSeconds());

        HospitalCandidate noLoc = candidate(result, "HOSP-NOLOC");
        assertFalse(noLoc.eligible());
        assertTrue(noLoc.reason().contains("location not verified"));
        assertFalse(candidate(result, "HOSP-CARDIAC").facilityMatch());
        assertFalse(candidate(result, "HOSP-FULL").eligible());
        assertFalse(candidate(result, "HOSP-CLOSED").eligible());
    }

    @Test
    @DisplayName("Planner reports NO_HOSPITAL when no facility can accept the patient")
    void reportsMissingHospital() {
        RoadGraph graph = TestGraphs.fork();
        Emergency emergency = emergencyAt(TestGraphs.N3, 11.3400, 77.7200, Facility.TRAUMA);

        SelectionResult result = planner.plan(emergency,
                List.of(ambulance("AMB-01", "Base", TestGraphs.N1, AmbulanceStatus.AVAILABLE)),
                List.of(hospital("HOSP-GEN", "General Only", 11.3400, 77.7000,
                        List.of(Facility.GENERAL), 20, 0, true)), graph);

        assertNull(result.selectedHospital());
        assertEquals("NO_HOSPITAL", result.failureReason());
        assertFalse(result.success());
        assertTrue(result.explanation().contains("no hospital"));
    }

    private static HospitalCandidate candidate(SelectionResult result, String id) {
        return result.hospitalCandidates().stream()
                .filter(c -> c.hospitalId().equals(id))
                .findFirst().orElseThrow();
    }
}
