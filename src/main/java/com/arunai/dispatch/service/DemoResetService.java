package com.arunai.dispatch.service;

import com.arunai.dispatch.repository.Database;
import com.arunai.dispatch.repository.SeedData;
import com.arunai.dispatch.repository.SettingsRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repeatable demonstration reset.
 *
 * <p>Deletes operational records, restores the five ambulances/hospitals and
 * clears road closures and simulated traffic. The imported OSM road network is
 * deliberately preserved (re-importing it would be slow). Optional demo history
 * is explicitly labelled as simulated in every description and note.</p>
 */
public class DemoResetService {
    private final Database db;
    private final SeedData seed;
    private final GraphService graph;
    private final DispatchService dispatch;
    private final SettingsRepository settings;
    private final SimulationService simulation;

    public DemoResetService(Database db, SeedData seed, GraphService graph,
                            DispatchService dispatch, SettingsRepository settings,
                            SimulationService simulation) {
        this.db = db;
        this.seed = seed;
        this.graph = graph;
        this.dispatch = dispatch;
        this.settings = settings;
        this.simulation = simulation;
    }

    public Map<String, Object> reset(boolean seedHistory) {
        db.transaction(c -> {
            delete(c, "emergency_events");
            delete(c, "dispatches");
            delete(c, "emergencies");
            delete(c, "road_closures");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE road_edges SET closed=0, traffic_multiplier=1.0")) {
                ps.executeUpdate();
            }
            delete(c, "ambulances");
            delete(c, "hospitals");
            return null;
        });
        seed.seedIfEmpty();
        graph.reload();
        dispatch.resetQueueState();
        if (seedHistory) seedHistory();
        settings.put("simulation_speed", "8");
        settings.put("simulation_paused", "false");
        settings.put("demo_reset_at", Instant.now().toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reset", true);
        result.put("networkPreserved", graph.source());
        result.put("historySeeded", seedHistory);
        result.put("resetAt", Instant.now().toString());
        result.put("message", "Operational data reset. Imported road network was preserved.");
        return result;
    }

    /** Insert clearly-labelled completed demonstration history for analytics. */
    public void seedHistory() {
        db.transaction(c -> {
            long now = System.currentTimeMillis();
            insertHistoryEmergency(c, "EMG-0001", 1, "ROAD_TRAFFIC_ACCIDENT", "CRITICAL", "TRAUMA",
                    11.3470, 77.7200, "DEMO HISTORY — simulated road traffic accident near Erode bus stand",
                    "AMB-01", "HOSP-01", now - 7_200_000L, now - 6_900_000L, 64, 3100, 420);
            insertHistoryEmergency(c, "EMG-0002", 2, "CARDIAC_EMERGENCY", "SERIOUS", "CARDIAC",
                    11.2766, 77.5836, "DEMO HISTORY — simulated cardiac emergency in Perundurai",
                    "AMB-03", "HOSP-04", now - 5_400_000L, now - 5_100_000L, 58, 2100, 360);
            insertHistoryEmergency(c, "EMG-0003", 3, "GENERAL_MEDICAL", "MINOR", "GENERAL",
                    11.3955, 77.6581, "DEMO HISTORY — simulated general medical call in Chithode",
                    "AMB-05", "HOSP-03", now - 3_600_000L, now - 3_300_000L, 72, 4800, 540);
            return null;
        });
    }

    private void insertHistoryEmergency(Connection c, String id, int seq, String type, String severity,
                                        String facility, double lat, double lon, String description,
                                        String ambulanceId, String hospitalId,
                                        long createdAt, long completedAt, int responseSeconds,
                                        double distance, int hospitalEta) throws SQLException {
        String created = Instant.ofEpochMilli(createdAt).toString();
        String completed = Instant.ofEpochMilli(completedAt).toString();
        String arrived = Instant.ofEpochMilli(createdAt + responseSeconds * 1000L).toString();
        String dispatchId = "DSP-000" + seq;

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO emergencies(id,seq,type,severity,required_facility,lat,lon,description,status," +
                        "created_at,updated_at,queued_at,assigned_ambulance_id,assigned_hospital_id," +
                        "snapped_node_id,dispatch_note,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setInt(2, seq);
            ps.setString(3, type);
            ps.setString(4, severity);
            ps.setString(5, facility);
            ps.setDouble(6, lat);
            ps.setDouble(7, lon);
            ps.setString(8, description);
            ps.setString(9, "COMPLETED");
            ps.setString(10, created);
            ps.setString(11, completed);
            ps.setString(12, created);
            ps.setString(13, ambulanceId);
            ps.setString(14, hospitalId);
            ps.setNull(15, java.sql.Types.INTEGER);
            ps.setString(16, "DEMO HISTORY — completed dispatch");
            ps.setString(17, completed);
            ps.executeUpdate();
        }

        String[][] timeline = {
                {"REPORTED", created, "DEMO HISTORY — call received"},
                {"QUEUED", created, "Added to priority queue"},
                {"ASSIGNED", Instant.ofEpochMilli(createdAt + 15_000).toString(), ambulanceId + " assigned"},
                {"AMBULANCE_EN_ROUTE", Instant.ofEpochMilli(createdAt + 20_000).toString(), "Demo route started"},
                {"ARRIVED_AT_SCENE", arrived, "Response interval recorded for analytics"},
                {"TRANSPORTING", Instant.ofEpochMilli(createdAt + (responseSeconds + 90) * 1000L).toString(), "Demo transport"},
                {"ARRIVED_AT_HOSPITAL", Instant.ofEpochMilli(createdAt + (responseSeconds + 300) * 1000L).toString(), "Demo handover"},
                {"COMPLETED", completed, "DEMO HISTORY — dispatch completed"}
        };
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO emergency_events(emergency_id,status,note,created_at) VALUES(?,?,?,?)")) {
            for (String[] row : timeline) {
                ps.setString(1, id);
                ps.setString(2, row[0]);
                ps.setString(3, row[2]);
                ps.setString(4, row[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dispatches(id,emergency_id,ambulance_id,hospital_id,assigned_at," +
                        "ambulance_eta_seconds,hospital_eta_seconds,ambulance_distance_m,hospital_distance_m," +
                        "status,selection_reason,alternatives_json,ambulance_route,hospital_route,return_route," +
                        "phase,phase_started_at,phase_duration_seconds,route_warning,completed_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, dispatchId);
            ps.setString(2, id);
            ps.setString(3, ambulanceId);
            ps.setString(4, hospitalId);
            ps.setString(5, created);
            ps.setInt(6, responseSeconds);
            ps.setInt(7, hospitalEta);
            ps.setDouble(8, distance);
            ps.setDouble(9, distance * 0.8);
            ps.setString(10, "COMPLETED");
            ps.setString(11, "DEMO HISTORY — simulated automatic dispatch");
            ps.setString(12, "{\"demoHistory\":true}");
            ps.setString(13, "[]");
            ps.setString(14, "[]");
            ps.setNull(15, java.sql.Types.VARCHAR);
            ps.setString(16, "COMPLETED");
            ps.setLong(17, createdAt);
            ps.setDouble(18, responseSeconds);
            ps.setNull(19, java.sql.Types.VARCHAR);
            ps.setString(20, completed);
            ps.executeUpdate();
        }
    }

    private void delete(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table)) {
            ps.executeUpdate();
        }
    }
}
