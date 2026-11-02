package com.arunai.dispatch.repository;

import com.arunai.dispatch.model.AmbulanceStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Seed records for the five ambulances and five hospitals.
 *
 * <p>Coordinates were taken from OpenStreetMap features (see osm_ref and
 * docs/verification.md). They are demonstration starting points, NOT claims
 * that ambulances are stationed there. Bed counts and facilities are
 * DEMONSTRATION data and must never be presented as live hospital capacity.</p>
 */
public class SeedData {
    private final Database db;

    public SeedData(Database db) {
        this.db = db;
    }

    public void seedIfEmpty() {
        try (Connection c = db.open()) {
            if (count(c, "ambulances") == 0) seedAmbulances(c);
            if (count(c, "hospitals") == 0) seedHospitals(c);
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot seed demonstration data", ex);
        }
    }

    private int count(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void seedAmbulances(Connection c) throws SQLException {
        String sql = "INSERT INTO ambulances(id,base_location,base_lat,base_lon,current_location," +
                "current_lat,current_lon,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?)";
        String now = Instant.now().toString();
        Object[][] rows = {
                // OSM: Erode Bus Stand / Central Bus Stand, way 30877083
                {"AMB-01", "Erode Central Bus Stand", 11.3470648, 77.7199695},
                // OSM: Erode Junction railway station, node 288370387
                {"AMB-02", "Erode Junction Railway Station", 11.3286258, 77.7257896},
                // OSM: Perundurai town node 655090824
                {"AMB-03", "Perundurai", 11.2764669, 77.5836267},
                // OSM: Bhavani town node 245592587
                {"AMB-04", "Bhavani", 11.4405993, 77.6824386},
                // OSM: Chithode town node 4997758492
                {"AMB-05", "Chithode", 11.3954841, 77.6580812}
        };
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] row : rows) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setDouble(3, (Double) row[2]);
                ps.setDouble(4, (Double) row[3]);
                ps.setString(5, (String) row[1]);
                ps.setDouble(6, (Double) row[2]);
                ps.setDouble(7, (Double) row[3]);
                ps.setString(8, AmbulanceStatus.AVAILABLE.name());
                ps.setString(9, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void seedHospitals(Connection c) throws SQLException {
        String sql = "INSERT INTO hospitals(id,name,lat,lon,address,facilities,total_beds," +
                "occupied_beds,operational,verification_status,osm_ref,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        insertHospital(c, sql, "HOSP-01", "Sudha Hospitals",
                11.3371539, 77.7109247,
                "Perundurai Road, New Teachers Colony, Erode 638011 (OSM)",
                "GENERAL,CARDIAC,TRAUMA", 120, 86,
                "OSM_LOCATION_VERIFIED",
                "node/6936695231",
                "OSM feature is named 'Sudha Institute of Medical Sciences Hospital'. Candidate short name retained. Beds are simulated.");
        insertHospital(c, sql, "HOSP-02", "Thanthai Periyar Government Headquarters Hospital",
                11.3393048, 77.7179260,
                "Erode, Tamil Nadu (OSM District Hospital, Eroda)",
                "GENERAL,TRAUMA", 500, 412,
                "OSM_LOCATION_VERIFIED_NAME_ALIAS",
                "way/31469343",
                "Location verified from OSM 'District Hospital, Eroda'. Admin should confirm the official name alias. Beds are simulated.");
        insertHospital(c, sql, "HOSP-03", "Lotus Hospital",
                11.3259471, 77.7302149,
                "Erode - Dharapuram Road, Mullamparappu, Erode 638002 (OSM)",
                "GENERAL,CARDIAC", 80, 54,
                "OSM_LOCATION_VERIFIED",
                "way/73242511",
                "Exact OSM name match. Beds are simulated.");
        insertHospital(c, sql, "HOSP-04", "Dhanvantri Critical Care Hospital",
                11.3504266, 77.7311274,
                "KNK Road, Veerappanchatiram, Erode 638003 (OSM)",
                "CARDIAC,TRAUMA", 60, 41,
                "OSM_LOCATION_VERIFIED_NAME_ALIAS",
                "node/6934062831",
                "OSM feature is named 'Dhanvantri Critical Care Center'. Candidate name retained. Beds are simulated.");
        // No exact OSM feature could be confirmed for this candidate. Do not invent a location.
        insertHospital(c, sql, "HOSP-05", "Erode Medical Centre",
                null, null,
                "Location not verified - set it in Settings",
                "GENERAL", 100, 70,
                "UNVERIFIED",
                null,
                "No exact OSM match was found. This record is excluded from routing until an administrator confirms a location. Beds are simulated.");
    }

    private void insertHospital(Connection c, String sql, String id, String name, Double lat, Double lon,
                                String address, String facilities, int total, int occupied,
                                String verification, String osmRef, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, name);
            if (lat == null) ps.setNull(3, java.sql.Types.REAL); else ps.setDouble(3, lat);
            if (lon == null) ps.setNull(4, java.sql.Types.REAL); else ps.setDouble(4, lon);
            ps.setString(5, address);
            ps.setString(6, facilities);
            ps.setInt(7, total);
            ps.setInt(8, occupied);
            ps.setInt(9, 1);
            ps.setString(10, verification);
            ps.setString(11, osmRef);
            ps.setString(12, note);
            ps.executeUpdate();
        }
    }
}
