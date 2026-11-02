package com.arunai.dispatch.repository;

import com.arunai.dispatch.model.Dispatch;
import com.arunai.dispatch.model.GeoPoint;
import com.arunai.dispatch.model.TripPhase;
import com.arunai.dispatch.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for dispatch records and simulator progress. */
public class DispatchRepository {
    private static final TypeReference<List<GeoPoint>> POINTS = new TypeReference<>() {};
    private final Database db;

    public DispatchRepository(Database db) {
        this.db = db;
    }

    public Dispatch insert(Dispatch d) {
        return db.transaction(c -> {
            int seq;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)+1 FROM dispatches");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getInt(1);
            }
            String id = String.format("DSP-%04d", seq);
            d.setId(id);
            String now = Instant.now().toString();
            d.setAssignedAt(now);
            d.setStatus("ACTIVE");
            if (d.getPhaseStartedAt() == 0) d.setPhaseStartedAt(System.currentTimeMillis());
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dispatches(id,emergency_id,ambulance_id,hospital_id,assigned_at," +
                            "ambulance_eta_seconds,hospital_eta_seconds,ambulance_distance_m,hospital_distance_m," +
                            "status,selection_reason,alternatives_json,ambulance_route,hospital_route,return_route," +
                            "phase,phase_started_at,phase_duration_seconds,route_warning) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, d.getEmergencyId());
                ps.setString(3, d.getAmbulanceId());
                ps.setString(4, d.getHospitalId());
                ps.setString(5, now);
                ps.setInt(6, d.getAmbulanceEtaSeconds());
                ps.setInt(7, d.getHospitalEtaSeconds());
                ps.setDouble(8, d.getAmbulanceDistanceMeters());
                ps.setDouble(9, d.getHospitalDistanceMeters());
                ps.setString(10, d.getStatus());
                ps.setString(11, d.getSelectionReason());
                ps.setString(12, d.getAlternativesJson());
                ps.setString(13, Json.write(d.getAmbulanceRoute()));
                ps.setString(14, Json.write(d.getHospitalRoute()));
                ps.setString(15, d.getReturnRoute() == null ? null : Json.write(d.getReturnRoute()));
                ps.setString(16, d.getPhase().name());
                ps.setLong(17, d.getPhaseStartedAt());
                ps.setDouble(18, d.getPhaseDurationSeconds());
                ps.setString(19, d.getRouteWarning());
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot insert dispatch", ex);
            }
            return d;
        });
    }

    /**
     * Atomically reserves the ambulance, inserts the dispatch and marks the
     * emergency ASSIGNED. The conditional ambulance update and the partial
     * unique indexes make duplicate assignment impossible even under races.
     */
    public Dispatch createAssignment(Dispatch d, String destinationLabel) {
        return db.transaction(c -> {
            int seq;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)+1 FROM dispatches");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getInt(1);
            }
            String id = String.format("DSP-%04d", seq);
            String now = Instant.now().toString();
            d.setId(id);
            d.setAssignedAt(now);
            d.setStatus("ACTIVE");
            d.setPhase(TripPhase.TO_SCENE);
            d.setPhaseStartedAt(System.currentTimeMillis());
            if (d.getPhaseDurationSeconds() <= 0) d.setPhaseDurationSeconds(d.getAmbulanceEtaSeconds());

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ambulances SET status='EN_ROUTE',assigned_emergency_id=?,destination=?," +
                            "eta_seconds=?,trip_status='EN_ROUTE_TO_SCENE',updated_at=? " +
                            "WHERE id=? AND status='AVAILABLE'")) {
                ps.setString(1, d.getEmergencyId());
                ps.setString(2, destinationLabel);
                ps.setInt(3, d.getAmbulanceEtaSeconds());
                ps.setString(4, now);
                ps.setString(5, d.getAmbulanceId());
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("Ambulance is no longer available: " + d.getAmbulanceId());
                }
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dispatches(id,emergency_id,ambulance_id,hospital_id,assigned_at," +
                            "ambulance_eta_seconds,hospital_eta_seconds,ambulance_distance_m,hospital_distance_m," +
                            "status,selection_reason,alternatives_json,ambulance_route,hospital_route,return_route," +
                            "phase,phase_started_at,phase_duration_seconds,route_warning) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, d.getEmergencyId());
                ps.setString(3, d.getAmbulanceId());
                ps.setString(4, d.getHospitalId());
                ps.setString(5, now);
                ps.setInt(6, d.getAmbulanceEtaSeconds());
                ps.setInt(7, d.getHospitalEtaSeconds());
                ps.setDouble(8, d.getAmbulanceDistanceMeters());
                ps.setDouble(9, d.getHospitalDistanceMeters());
                ps.setString(10, d.getStatus());
                ps.setString(11, d.getSelectionReason());
                ps.setString(12, d.getAlternativesJson());
                ps.setString(13, Json.write(d.getAmbulanceRoute()));
                ps.setString(14, Json.write(d.getHospitalRoute()));
                ps.setString(15, d.getReturnRoute() == null ? null : Json.write(d.getReturnRoute()));
                ps.setString(16, d.getPhase().name());
                ps.setLong(17, d.getPhaseStartedAt());
                ps.setDouble(18, d.getPhaseDurationSeconds());
                ps.setString(19, d.getRouteWarning());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE emergencies SET status='ASSIGNED',assigned_ambulance_id=?,assigned_hospital_id=?," +
                            "updated_at=? WHERE id=? AND status='QUEUED'")) {
                ps.setString(1, d.getAmbulanceId());
                ps.setString(2, d.getHospitalId());
                ps.setString(3, now);
                ps.setString(4, d.getEmergencyId());
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("Emergency is no longer queued: " + d.getEmergencyId());
                }
            }
            return d;
        });
    }

    public Optional<Dispatch> findById(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM dispatches WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read dispatch", ex);
        }
    }

    public List<Dispatch> findActive() {
        return query("SELECT * FROM dispatches WHERE status='ACTIVE' ORDER BY assigned_at", null);
    }

    public Optional<Dispatch> findActiveByEmergency(String emergencyId) {
        return query("SELECT * FROM dispatches WHERE emergency_id=? AND status='ACTIVE' LIMIT 1", emergencyId)
                .stream().findFirst();
    }

    public Optional<Dispatch> findActiveByAmbulance(String ambulanceId) {
        return query("SELECT * FROM dispatches WHERE ambulance_id=? AND status='ACTIVE' LIMIT 1", ambulanceId)
                .stream().findFirst();
    }

    public List<Dispatch> findRecent(int limit) {
        List<Dispatch> result = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM dispatches ORDER BY assigned_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read dispatches", ex);
        }
        return result;
    }

    public void updateSimulation(Dispatch d) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE dispatches SET phase=?,phase_started_at=?,phase_duration_seconds=?,ambulance_route=?,hospital_route=?," +
                             "return_route=?,ambulance_eta_seconds=?,hospital_eta_seconds=?," +
                             "ambulance_distance_m=?,hospital_distance_m=?,route_warning=? WHERE id=?")) {
            ps.setString(1, d.getPhase().name());
            ps.setLong(2, d.getPhaseStartedAt());
            ps.setDouble(3, d.getPhaseDurationSeconds());
            ps.setString(4, Json.write(d.getAmbulanceRoute() == null ? List.of() : d.getAmbulanceRoute()));
            ps.setString(5, Json.write(d.getHospitalRoute() == null ? List.of() : d.getHospitalRoute()));
            ps.setString(6, d.getReturnRoute() == null ? null : Json.write(d.getReturnRoute()));
            ps.setInt(7, d.getAmbulanceEtaSeconds());
            ps.setInt(8, d.getHospitalEtaSeconds());
            ps.setDouble(9, d.getAmbulanceDistanceMeters());
            ps.setDouble(10, d.getHospitalDistanceMeters());
            ps.setString(11, d.getRouteWarning());
            ps.setString(12, d.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update dispatch simulation", ex);
        }
    }

    public void complete(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE dispatches SET status='COMPLETED',phase='COMPLETED',completed_at=? WHERE id=?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot complete dispatch", ex);
        }
    }

    public int countAll() {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM dispatches");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }

    private List<Dispatch> query(String sql, String param) {
        List<Dispatch> result = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot query dispatches", ex);
        }
        return result;
    }

    private Dispatch map(ResultSet rs) throws SQLException {
        Dispatch d = new Dispatch();
        d.setId(rs.getString("id"));
        d.setEmergencyId(rs.getString("emergency_id"));
        d.setAmbulanceId(rs.getString("ambulance_id"));
        d.setHospitalId(rs.getString("hospital_id"));
        d.setAssignedAt(rs.getString("assigned_at"));
        d.setAmbulanceEtaSeconds(rs.getInt("ambulance_eta_seconds"));
        d.setHospitalEtaSeconds(rs.getInt("hospital_eta_seconds"));
        d.setAmbulanceDistanceMeters(rs.getDouble("ambulance_distance_m"));
        d.setHospitalDistanceMeters(rs.getDouble("hospital_distance_m"));
        d.setStatus(rs.getString("status"));
        d.setSelectionReason(rs.getString("selection_reason"));
        d.setAlternativesJson(rs.getString("alternatives_json"));
        d.setAmbulanceRoute(readPoints(rs.getString("ambulance_route")));
        d.setHospitalRoute(readPoints(rs.getString("hospital_route")));
        d.setReturnRoute(readPoints(rs.getString("return_route")));
        String phase = rs.getString("phase");
        d.setPhase(phase == null ? TripPhase.TO_SCENE : TripPhase.valueOf(phase));
        d.setPhaseStartedAt(rs.getLong("phase_started_at"));
        d.setPhaseDurationSeconds(rs.getDouble("phase_duration_seconds"));
        d.setRouteWarning(rs.getString("route_warning"));
        d.setCompletedAt(rs.getString("completed_at"));
        return d;
    }

    private List<GeoPoint> readPoints(String json) {
        if (json == null || json.isBlank()) return List.of();
        return Json.readList(json, POINTS);
    }
}
