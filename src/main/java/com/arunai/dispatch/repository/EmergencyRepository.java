package com.arunai.dispatch.repository;

import com.arunai.dispatch.model.Emergency;
import com.arunai.dispatch.model.EmergencyStatus;
import com.arunai.dispatch.model.Facility;
import com.arunai.dispatch.model.IncidentType;
import com.arunai.dispatch.model.Severity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for emergencies, including the durable priority queue view. */
public class EmergencyRepository {
    private final Database db;

    public EmergencyRepository(Database db) {
        this.db = db;
    }

    /** Allocates a sequence number and readable ID atomically. */
    public Emergency insert(Emergency emergency) {
        return db.transaction(c -> {
            int seq;
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM emergencies");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getInt(1);
            }
            String id = String.format("EMG-%04d", seq);
            String now = Instant.now().toString();
            emergency.setId(id);
            emergency.setSeq(seq);
            emergency.setCreatedAt(now);
            emergency.setUpdatedAt(now);
            emergency.setQueuedAt(now);
            emergency.setStatus(EmergencyStatus.QUEUED);

            String sql = "INSERT INTO emergencies(id,seq,type,severity,required_facility,lat,lon,description," +
                    "status,created_at,updated_at,queued_at,snapped_node_id,dispatch_note) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setInt(2, seq);
                ps.setString(3, emergency.getType().name());
                ps.setString(4, emergency.getSeverity().name());
                ps.setString(5, emergency.getRequiredFacility().name());
                ps.setDouble(6, emergency.getLat());
                ps.setDouble(7, emergency.getLon());
                ps.setString(8, emergency.getDescription());
                ps.setString(9, emergency.getStatus().name());
                ps.setString(10, now);
                ps.setString(11, now);
                ps.setString(12, now);
                if (emergency.getSnappedNodeId() == null) ps.setNull(13, java.sql.Types.INTEGER);
                else ps.setLong(13, emergency.getSnappedNodeId());
                ps.setString(14, emergency.getDispatchNote());
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("Cannot insert emergency", ex);
            }
            return emergency;
        });
    }

    public Optional<Emergency> findById(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM emergencies WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read emergency", ex);
        }
    }

    public List<Emergency> findAll(int limit) {
        return query("SELECT * FROM emergencies ORDER BY seq DESC LIMIT ?", ps -> ps.setInt(1, limit));
    }

    public List<Emergency> findActive() {
        return query("SELECT * FROM emergencies WHERE status NOT IN ('COMPLETED','CANCELLED') " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'SERIOUS' THEN 2 ELSE 3 END, seq", null);
    }

    public List<Emergency> findQueue() {
        return query("SELECT * FROM emergencies WHERE status='QUEUED' " +
                "ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'SERIOUS' THEN 2 ELSE 3 END, seq", null);
    }

    public List<Emergency> findCompleted(int limit) {
        return query("SELECT * FROM emergencies WHERE status='COMPLETED' ORDER BY seq DESC LIMIT ?",
                ps -> ps.setInt(1, limit));
    }

    public void updateStatus(String id, EmergencyStatus status, String note) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE emergencies SET status=?,dispatch_note=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, note);
            ps.setString(3, Instant.now().toString());
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update emergency status", ex);
        }
    }

    public void updateAssignment(String id, EmergencyStatus status, String ambulanceId, String hospitalId,
                                 String note) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE emergencies SET status=?,assigned_ambulance_id=?,assigned_hospital_id=?," +
                             "dispatch_note=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, ambulanceId);
            ps.setString(3, hospitalId);
            ps.setString(4, note);
            ps.setString(5, Instant.now().toString());
            ps.setString(6, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot assign emergency", ex);
        }
    }

    public void complete(String id) {
        String now = Instant.now().toString();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE emergencies SET status='COMPLETED',completed_at=?,updated_at=? WHERE id=?")) {
            ps.setString(1, now);
            ps.setString(2, now);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot complete emergency", ex);
        }
    }

    public void resetQueueNote(String id, String note) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE emergencies SET dispatch_note=?,updated_at=? WHERE id=?")) {
            ps.setString(1, note);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update dispatch note", ex);
        }
    }

    public int countAll() {
        return scalar("SELECT COUNT(*) FROM emergencies");
    }

    public int countByStatus(EmergencyStatus status) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM emergencies WHERE status=?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            return 0;
        }
    }

    public int countActive() {
        return scalar("SELECT COUNT(*) FROM emergencies WHERE status NOT IN ('COMPLETED','CANCELLED')");
    }

    private int scalar(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }

    private List<Emergency> query(String sql, SqlBinder binder) {
        List<Emergency> result = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (binder != null) binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot query emergencies", ex);
        }
        return result;
    }

    private Emergency map(ResultSet rs) throws SQLException {
        Emergency e = new Emergency();
        e.setId(rs.getString("id"));
        e.setSeq(rs.getInt("seq"));
        e.setType(IncidentType.from(rs.getString("type")));
        e.setSeverity(Severity.valueOf(rs.getString("severity")));
        e.setRequiredFacility(Facility.valueOf(rs.getString("required_facility")));
        e.setLat(rs.getDouble("lat"));
        e.setLon(rs.getDouble("lon"));
        e.setDescription(rs.getString("description"));
        e.setStatus(EmergencyStatus.from(rs.getString("status")));
        e.setCreatedAt(rs.getString("created_at"));
        e.setUpdatedAt(rs.getString("updated_at"));
        e.setQueuedAt(rs.getString("queued_at"));
        e.setAssignedAmbulanceId(rs.getString("assigned_ambulance_id"));
        e.setAssignedHospitalId(rs.getString("assigned_hospital_id"));
        long snapped = rs.getLong("snapped_node_id");
        e.setSnappedNodeId(rs.wasNull() ? null : snapped);
        e.setDispatchNote(rs.getString("dispatch_note"));
        e.setCompletedAt(rs.getString("completed_at"));
        return e;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
