package com.uyirpath.dispatch.repository;

import com.uyirpath.dispatch.model.Ambulance;
import com.uyirpath.dispatch.model.AmbulanceStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for the five ambulance records. */
public class AmbulanceRepository {
    private final Database db;

    public AmbulanceRepository(Database db) {
        this.db = db;
    }

    public List<Ambulance> findAll() {
        List<Ambulance> result = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM ambulances ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read ambulances", ex);
        }
        return result;
    }

    public Optional<Ambulance> findById(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM ambulances WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read ambulance " + id, ex);
        }
    }

    public void update(Ambulance a) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET base_location=?,base_lat=?,base_lon=?,current_location=?," +
                             "current_lat=?,current_lon=?,status=?,assigned_emergency_id=?,destination=?," +
                             "eta_seconds=?,trip_status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, a.getBaseLocation());
            ps.setDouble(2, a.getBaseLat());
            ps.setDouble(3, a.getBaseLon());
            ps.setString(4, a.getCurrentLocation());
            ps.setDouble(5, a.getCurrentLat());
            ps.setDouble(6, a.getCurrentLon());
            ps.setString(7, a.getStatus().name());
            ps.setString(8, a.getAssignedEmergencyId());
            ps.setString(9, a.getDestination());
            if (a.getEtaSeconds() == null) ps.setNull(10, java.sql.Types.INTEGER);
            else ps.setInt(10, a.getEtaSeconds());
            ps.setString(11, a.getTripStatus());
            ps.setString(12, Instant.now().toString());
            ps.setString(13, a.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update ambulance " + a.getId(), ex);
        }
    }

    public void updatePosition(String id, double lat, double lon, String location,
                               Integer etaSeconds, String tripStatus) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET current_lat=?,current_lon=?,current_location=?,eta_seconds=?," +
                             "trip_status=?,updated_at=? WHERE id=?")) {
            ps.setDouble(1, lat);
            ps.setDouble(2, lon);
            ps.setString(3, location);
            if (etaSeconds == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, etaSeconds);
            ps.setString(5, tripStatus);
            ps.setString(6, Instant.now().toString());
            ps.setString(7, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update ambulance position", ex);
        }
    }

    /**
     * Conditional update is the first line of defence against double booking.
     * The partial unique index on dispatches is the second.
     */
    public boolean reserveIfAvailable(String id, String emergencyId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET status='RESERVED',assigned_emergency_id=?,updated_at=? " +
                             "WHERE id=? AND status='AVAILABLE'")) {
            ps.setString(1, emergencyId);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot reserve ambulance " + id, ex);
        }
    }

    public void updateStatus(String id, AmbulanceStatus status) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, Instant.now().toString());
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot change ambulance status", ex);
        }
    }

    public void release(String id, double baseLat, double baseLon, String baseLocation) {
        try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET status='AVAILABLE',assigned_emergency_id=NULL," +
                             "destination=NULL,eta_seconds=NULL,trip_status=NULL,current_lat=?," +
                             "current_lon=?,current_location=?,updated_at=? WHERE id=?")) {
            ps.setDouble(1, baseLat);
            ps.setDouble(2, baseLon);
            ps.setString(3, baseLocation);
            ps.setString(4, Instant.now().toString());
            ps.setString(5, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot release ambulance", ex);
        }
    }

    public void updateAssignment(String id, AmbulanceStatus status, String emergencyId, String destination,
                                 Integer etaSeconds, String tripStatus) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ambulances SET status=?,assigned_emergency_id=?,destination=?,eta_seconds=?," +
                             "trip_status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, emergencyId);
            ps.setString(3, destination);
            if (etaSeconds == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, etaSeconds);
            ps.setString(5, tripStatus);
            ps.setString(6, Instant.now().toString());
            ps.setString(7, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update ambulance assignment", ex);
        }
    }

    private Ambulance map(ResultSet rs) throws SQLException {
        Ambulance a = new Ambulance();
        a.setId(rs.getString("id"));
        a.setBaseLocation(rs.getString("base_location"));
        a.setBaseLat(rs.getDouble("base_lat"));
        a.setBaseLon(rs.getDouble("base_lon"));
        a.setCurrentLocation(rs.getString("current_location"));
        a.setCurrentLat(rs.getDouble("current_lat"));
        a.setCurrentLon(rs.getDouble("current_lon"));
        a.setStatus(AmbulanceStatus.from(rs.getString("status")));
        a.setAssignedEmergencyId(rs.getString("assigned_emergency_id"));
        a.setDestination(rs.getString("destination"));
        int eta = rs.getInt("eta_seconds");
        a.setEtaSeconds(rs.wasNull() ? null : eta);
        a.setTripStatus(rs.getString("trip_status"));
        a.setUpdatedAt(rs.getString("updated_at"));
        return a;
    }
}
