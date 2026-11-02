package com.arunai.dispatch.service;

import com.arunai.dispatch.model.Ambulance;
import com.arunai.dispatch.model.Hospital;
import com.arunai.dispatch.repository.AmbulanceRepository;
import com.arunai.dispatch.repository.Database;
import com.arunai.dispatch.repository.EventRepository;
import com.arunai.dispatch.repository.HospitalRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL-backed operational analytics. No fabricated or decorative metrics. */
public class AnalyticsService {
    private final Database db;
    private final AmbulanceRepository ambulances;
    private final HospitalRepository hospitals;
    private final EventRepository events;

    public AnalyticsService(Database db, AmbulanceRepository ambulances,
                            HospitalRepository hospitals, EventRepository events) {
        this.db = db;
        this.ambulances = ambulances;
        this.hospitals = hospitals;
        this.events = events;
    }

    public Map<String, Object> analytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", Instant.now().toString());
        result.put("totals", totals());
        result.put("response", responseTimes());
        result.put("priorityDistribution", groupCount(
                "SELECT severity k, COUNT(*) c FROM emergencies GROUP BY severity", "CRITICAL", "SERIOUS", "MINOR"));
        result.put("statusDistribution", groupCount(
                "SELECT status k, COUNT(*) c FROM emergencies GROUP BY status",
                "REPORTED", "QUEUED", "ASSIGNED", "AMBULANCE_EN_ROUTE", "ARRIVED_AT_SCENE",
                "TRANSPORTING", "ARRIVED_AT_HOSPITAL", "COMPLETED", "CANCELLED"));
        result.put("ambulanceUtilization", ambulanceUtilization());
        result.put("hospitalUtilization", hospitalUtilization());
        result.put("routePerformance", routePerformance());
        result.put("recentEvents", events.recent(12));
        result.put("definitions", Map.of(
                "responseTime", "Seconds from emergency registration to first ARRIVED_AT_SCENE audit event.",
                "hospitalBeds", "Demonstration occupancy, not live hospital data.",
                "utilisation", "Share of completed dispatches handled by each demonstration ambulance."));
        return result;
    }

    private Map<String, Object> totals() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("emergencies", count("SELECT COUNT(*) FROM emergencies"));
        t.put("activeEmergencies", count("SELECT COUNT(*) FROM emergencies " +
                "WHERE status NOT IN ('COMPLETED','CANCELLED')"));
        t.put("completedEmergencies", count("SELECT COUNT(*) FROM emergencies WHERE status='COMPLETED'"));
        t.put("queued", count("SELECT COUNT(*) FROM emergencies WHERE status='QUEUED'"));
        t.put("dispatches", count("SELECT COUNT(*) FROM dispatches"));
        t.put("activeDispatches", count("SELECT COUNT(*) FROM dispatches WHERE status='ACTIVE'"));
        t.put("closedEdges", count("SELECT COUNT(*) FROM road_edges WHERE closed=1"));
        t.put("networkEdges", count("SELECT COUNT(*) FROM road_edges"));
        return t;
    }

    /** Average registration-to-scene interval using the first arrival audit event. */
    private Map<String, Object> responseTimes() {
        String sql = "SELECT e.id, e.created_at, ev.created_at arrived FROM emergencies e " +
                "JOIN emergency_events ev ON ev.id = (" +
                "  SELECT MIN(id) FROM emergency_events WHERE emergency_id=e.id AND status='ARRIVED_AT_SCENE') " +
                "WHERE e.status='COMPLETED'";
        double total = 0;
        int n = 0;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    double seconds = java.time.Duration.between(
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("arrived"))).toMillis() / 1000.0;
                    if (seconds >= 0) { total += seconds; n++; }
                } catch (Exception ignored) { }
            }
        } catch (SQLException ignored) { }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("averageSeconds", n == 0 ? null : Math.round(total / n));
        r.put("sampleSize", n);
        r.put("note", "Measured from stored audit events; simulation speed affects this value.");
        return r;
    }

    private List<Map<String, Object>> ambulanceUtilization() {
        Map<String, int[]> counts = new LinkedHashMap<>();
        String sql = "SELECT ambulance_id, COUNT(*) total, " +
                "SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END) active, " +
                "SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) completed " +
                "FROM dispatches GROUP BY ambulance_id";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString(1), new int[]{rs.getInt(2), rs.getInt(3), rs.getInt(4)});
            }
        } catch (SQLException ignored) { }
        int completedTotal = counts.values().stream().mapToInt(v -> v[2]).sum();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ambulance a : ambulances.findAll()) {
            int[] values = counts.getOrDefault(a.getId(), new int[]{0, 0, 0});
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("baseLocation", a.getBaseLocation());
            row.put("status", a.getStatus());
            row.put("trips", values[0]);
            row.put("active", values[1]);
            row.put("completed", values[2]);
            row.put("sharePercent", completedTotal == 0 ? 0
                    : Math.round(values[2] * 1000.0 / completedTotal) / 10.0);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> hospitalUtilization() {
        Map<String, Integer> dispatchCounts = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT hospital_id, COUNT(*) FROM dispatches GROUP BY hospital_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) dispatchCounts.put(rs.getString(1), rs.getInt(2));
        } catch (SQLException ignored) { }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Hospital h : hospitals.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.getId());
            row.put("name", h.getName());
            row.put("totalBeds", h.getTotalBeds());
            row.put("occupiedBeds", h.getOccupiedBeds());
            row.put("availableBeds", h.getAvailableBeds());
            row.put("utilizationPercent", h.getTotalBeds() == 0 ? 0
                    : Math.round(h.getOccupiedBeds() * 1000.0 / h.getTotalBeds()) / 10.0);
            row.put("dispatches", dispatchCounts.getOrDefault(h.getId(), 0));
            row.put("simulated", true);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> routePerformance() {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = "SELECT AVG(ambulance_distance_m), AVG(ambulance_eta_seconds), " +
                "AVG(hospital_distance_m), AVG(hospital_eta_seconds) FROM dispatches";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                p.put("avgAmbulanceDistanceMeters", round(rs.getDouble(1)));
                p.put("avgAmbulanceEtaSeconds", round(rs.getDouble(2)));
                p.put("avgHospitalDistanceMeters", round(rs.getDouble(3)));
                p.put("avgHospitalEtaSeconds", round(rs.getDouble(4)));
            }
        } catch (SQLException ignored) { }
        p.put("weights", "Travel-time seconds from the Java Dijkstra engine; class-based speeds.");
        return p;
    }

    private Map<String, Integer> groupCount(String sql, String... keys) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String key : keys) values.put(key, 0);
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) values.put(rs.getString("k"), rs.getInt("c"));
        } catch (SQLException ignored) { }
        return values;
    }

    private int count(String sql) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
