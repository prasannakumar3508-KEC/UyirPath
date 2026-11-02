package com.arunai.dispatch.repository;

import com.arunai.dispatch.TestGraphs;
import com.arunai.dispatch.graph.RoadGraph;
import com.arunai.dispatch.model.Dispatch;
import com.arunai.dispatch.model.EmergencyStatus;
import com.arunai.dispatch.model.RoadEdge;
import com.arunai.dispatch.model.RoadNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceInvariantTest {

    @TempDir
    Path tempDir;

    private Database database(String name) {
        Database db = new Database(tempDir.resolve(name));
        db.initialise();
        return db;
    }

    @Test
    @DisplayName("A road closure persists, is skipped by routing, and reopening clears the active closure")
    void closurePersistsAndReopens() {
        Database db = database("closure.db");
        RoadRepository roads = new RoadRepository(db);
        List<RoadNode> nodes = List.of(
                TestGraphs.node(TestGraphs.N1, 11.3400, 77.7000),
                TestGraphs.node(TestGraphs.N2, 11.3400, 77.7100),
                TestGraphs.node(TestGraphs.N3, 11.3400, 77.7200),
                TestGraphs.node(TestGraphs.N4, 11.3500, 77.7100));
        List<RoadEdge> edges = List.of(
                TestGraphs.edge("E12", 1, 2, "Main Road", "primary", 1000, 100,
                        List.of(TestGraphs.point(1), TestGraphs.point(2))),
                TestGraphs.edge("E23", 2, 3, "Market Road", "secondary", 1000, 100,
                        List.of(TestGraphs.point(2), TestGraphs.point(3))),
                TestGraphs.edge("E24", 2, 4, "Link Road", "tertiary", 800, 90,
                        List.of(TestGraphs.point(2), TestGraphs.point(4))),
                TestGraphs.edge("E43", 4, 3, "Hospital Road", "tertiary", 700, 80,
                        List.of(TestGraphs.point(4), TestGraphs.point(3))));
        roads.saveNetwork(nodes, edges);

        roads.setClosed("E23", true, "Test closure");
        List<Map<String, Object>> active = roads.closures();
        assertEquals(1, active.size());
        assertEquals("E23", active.get(0).get("edgeId"));

        RoadGraph closedGraph = roads.loadGraph();
        assertTrue(closedGraph.edge("E23").isClosed());
        assertEquals(List.of(1L, 2L, 4L, 3L), closedGraph.route(1, 3).nodeIds());

        roads.setClosed("E23", false, "Reopened by test");
        assertTrue(roads.closures().isEmpty(), "Reopening must deactivate the previous closure row");
        assertFalse(roads.loadGraph().edge("E23").isClosed());
        assertEquals(List.of(1L, 2L, 3L), roads.loadGraph().route(1, 3).nodeIds());

        assertEquals(2, count(db, "SELECT COUNT(*) FROM road_closures"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM road_closures WHERE active=1"));
    }

    @Test
    @DisplayName("The database refuses a second active dispatch for the same ambulance")
    void preventsDuplicateActiveAmbulanceAssignment() throws Exception {
        Database db = database("dispatch.db");
        new SeedData(db).seedIfEmpty();
        insertEmergency(db, "EMG-9001", 9001, "11.3400", "77.7200");
        insertEmergency(db, "EMG-9002", 9002, "11.3410", "77.7210");

        DispatchRepository dispatches = new DispatchRepository(db);
        EmergencyRepository emergencies = new EmergencyRepository(db);

        Dispatch first = dispatches.createAssignment(dispatch("EMG-9001", "AMB-01"), "Scene / EMG-9001");
        assertEquals("DSP-0001", first.getId());
        assertEquals("ASSIGNED", emergencies.findById("EMG-9001").orElseThrow().getStatus().name());

        assertThrows(IllegalStateException.class,
                () -> dispatches.createAssignment(dispatch("EMG-9002", "AMB-01"), "Scene / EMG-9002"));

        assertEquals(1, count(db, "SELECT COUNT(*) FROM dispatches WHERE status='ACTIVE'"));
        assertTrue(dispatches.findActiveByAmbulance("AMB-01").isPresent());
        assertEquals(EmergencyStatus.QUEUED,
                emergencies.findById("EMG-9002").orElseThrow().getStatus());
    }

    private static Dispatch dispatch(String emergencyId, String ambulanceId) {
        Dispatch d = new Dispatch();
        d.setEmergencyId(emergencyId);
        d.setAmbulanceId(ambulanceId);
        d.setHospitalId("HOSP-01");
        d.setAmbulanceEtaSeconds(120);
        d.setHospitalEtaSeconds(180);
        d.setAmbulanceDistanceMeters(1500);
        d.setHospitalDistanceMeters(2200);
        d.setSelectionReason("Unit test assignment");
        d.setAlternativesJson("{}");
        d.setAmbulanceRoute(List.of(TestGraphs.point(1), TestGraphs.point(2)));
        d.setHospitalRoute(List.of(TestGraphs.point(2), TestGraphs.point(3)));
        return d;
    }

    private static void insertEmergency(Database db, String id, int seq, String lat, String lon)
            throws Exception {
        String now = Instant.now().toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO emergencies(id,seq,type,severity,required_facility,lat,lon,description," +
                             "status,created_at,updated_at,queued_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setInt(2, seq);
            ps.setString(3, "GENERAL_MEDICAL");
            ps.setString(4, "CRITICAL");
            ps.setString(5, "GENERAL");
            ps.setDouble(6, Double.parseDouble(lat));
            ps.setDouble(7, Double.parseDouble(lon));
            ps.setString(8, "Unit test emergency");
            ps.setString(9, "QUEUED");
            ps.setString(10, now);
            ps.setString(11, now);
            ps.setString(12, now);
            ps.executeUpdate();
        }
    }

    private static int count(Database db, String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
