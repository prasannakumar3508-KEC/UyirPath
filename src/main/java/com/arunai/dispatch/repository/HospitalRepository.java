package com.arunai.dispatch.repository;

import com.arunai.dispatch.model.Facility;
import com.arunai.dispatch.model.Hospital;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Persistence and demonstration bed management for hospitals. */
public class HospitalRepository {
    private final Database db;

    public HospitalRepository(Database db) {
        this.db = db;
    }

    public List<Hospital> findAll() {
        List<Hospital> result = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hospitals ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read hospitals", ex);
        }
        return result;
    }

    public Optional<Hospital> findById(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hospitals WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read hospital " + id, ex);
        }
    }

    public void update(Hospital h) {
        if (h.getOccupiedBeds() < 0 || h.getOccupiedBeds() > h.getTotalBeds()) {
            throw new IllegalArgumentException("Occupied beds must be between 0 and total beds");
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE hospitals SET name=?,lat=?,lon=?,address=?,facilities=?,total_beds=?," +
                             "occupied_beds=?,operational=?,verification_status=?,osm_ref=?,note=? WHERE id=?")) {
            ps.setString(1, h.getName());
            if (h.getLat() == null) ps.setNull(2, java.sql.Types.REAL); else ps.setDouble(2, h.getLat());
            if (h.getLon() == null) ps.setNull(3, java.sql.Types.REAL); else ps.setDouble(3, h.getLon());
            ps.setString(4, h.getAddress());
            ps.setString(5, facilitiesToDb(h.getFacilities()));
            ps.setInt(6, h.getTotalBeds());
            ps.setInt(7, h.getOccupiedBeds());
            ps.setInt(8, h.isOperational() ? 1 : 0);
            ps.setString(9, h.getVerificationStatus());
            ps.setString(10, h.getOsmRef());
            ps.setString(11, h.getNote());
            ps.setString(12, h.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update hospital " + h.getId(), ex);
        }
    }

    public void updateBeds(String id, int totalBeds, int occupiedBeds) {
        if (totalBeds < 0 || occupiedBeds < 0 || occupiedBeds > totalBeds) {
            throw new IllegalArgumentException("Invalid demonstration bed counts");
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE hospitals SET total_beds=?,occupied_beds=? WHERE id=?")) {
            ps.setInt(1, totalBeds);
            ps.setInt(2, occupiedBeds);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update demonstration beds", ex);
        }
    }

    public void updateOperational(String id, boolean operational) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE hospitals SET operational=? WHERE id=?")) {
            ps.setInt(1, operational ? 1 : 0);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update hospital operational state", ex);
        }
    }

    /** Demonstration admission: never described as a real bed count. */
    public void admitOne(String id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE hospitals SET occupied_beds=occupied_beds+1 " +
                             "WHERE id=? AND occupied_beds < total_beds")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update demonstration occupancy", ex);
        }
    }

    private Hospital map(ResultSet rs) throws SQLException {
        Hospital h = new Hospital();
        h.setId(rs.getString("id"));
        h.setName(rs.getString("name"));
        double lat = rs.getDouble("lat");
        h.setLat(rs.wasNull() ? null : lat);
        double lon = rs.getDouble("lon");
        h.setLon(rs.wasNull() ? null : lon);
        h.setAddress(rs.getString("address"));
        h.setFacilities(facilitiesFromDb(rs.getString("facilities")));
        h.setTotalBeds(rs.getInt("total_beds"));
        h.setOccupiedBeds(rs.getInt("occupied_beds"));
        h.setOperational(rs.getInt("operational") == 1);
        h.setVerificationStatus(rs.getString("verification_status"));
        h.setOsmRef(rs.getString("osm_ref"));
        h.setNote(rs.getString("note"));
        return h;
    }

    public static String facilitiesToDb(List<Facility> facilities) {
        return facilities == null || facilities.isEmpty() ? "GENERAL"
                : String.join(",", facilities.stream().map(Enum::name).toList());
    }

    public static List<Facility> facilitiesFromDb(String value) {
        if (value == null || value.isBlank()) return List.of(Facility.GENERAL);
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .map(Facility::valueOf)
                .toList();
    }
}
