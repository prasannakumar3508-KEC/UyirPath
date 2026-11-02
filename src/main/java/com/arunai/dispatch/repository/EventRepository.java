package com.arunai.dispatch.repository;

import com.arunai.dispatch.model.EmergencyEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Append-only emergency timeline/audit log. */
public class EventRepository {
    private final Database db;

    public EventRepository(Database db) {
        this.db = db;
    }

    public void append(String emergencyId, String status, String note) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO emergency_events(emergency_id,status,note,created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, emergencyId);
            ps.setString(2, status);
            ps.setString(3, note);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot append emergency event", ex);
        }
    }

    public List<EmergencyEvent> forEmergency(String emergencyId) {
        List<EmergencyEvent> result = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM emergency_events WHERE emergency_id=? ORDER BY id")) {
            ps.setString(1, emergencyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new EmergencyEvent(rs.getLong("id"), rs.getString("emergency_id"),
                            rs.getString("status"), rs.getString("note"), rs.getString("created_at")));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read emergency events", ex);
        }
        return result;
    }

    public List<EmergencyEvent> recent(int limit) {
        List<EmergencyEvent> result = new ArrayList<>();
        String sql = "SELECT e.*, em.severity FROM emergency_events e " +
                "JOIN emergencies em ON em.id=e.emergency_id ORDER BY e.id DESC LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new EmergencyEvent(rs.getLong("id"), rs.getString("emergency_id"),
                            rs.getString("status"), rs.getString("note"), rs.getString("created_at")));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read recent events", ex);
        }
        return result;
    }
}
