package com.uyirpath.dispatch.repository;

import com.uyirpath.dispatch.graph.RoadGraph;
import com.uyirpath.dispatch.model.GeoPoint;
import com.uyirpath.dispatch.model.RoadEdge;
import com.uyirpath.dispatch.model.RoadNode;
import com.uyirpath.dispatch.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stores and loads the imported road graph, closures and simulated traffic. */
public class RoadRepository {
    private static final TypeReference<List<GeoPoint>> POINTS = new TypeReference<>() {};
    private final Database db;

    public RoadRepository(Database db) {
        this.db = db;
    }

    /** Replaces the local graph in one transaction. */
    public void saveNetwork(Collection<RoadNode> nodes, Collection<RoadEdge> edges) {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM road_closures")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM road_edges")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM road_nodes")) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement("INSERT INTO road_nodes(id,lat,lon) VALUES(?,?,?)")) {
                int batch = 0;
                for (RoadNode n : nodes) {
                    ps.setLong(1, n.id());
                    ps.setDouble(2, n.lat());
                    ps.setDouble(3, n.lon());
                    ps.addBatch();
                    if (++batch % 5000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO road_edges(id,from_node,to_node,way_id,name,road_type,distance_m," +
                            "travel_time_s,geometry,closed,traffic_multiplier) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                int batch = 0;
                for (RoadEdge e : edges) {
                    ps.setString(1, e.getId());
                    ps.setLong(2, e.getFrom());
                    ps.setLong(3, e.getTo());
                    ps.setLong(4, e.getWayId());
                    ps.setString(5, e.getName());
                    ps.setString(6, e.getRoadType());
                    ps.setDouble(7, e.getDistanceMeters());
                    ps.setDouble(8, e.getTravelTimeSeconds());
                    ps.setString(9, Json.write(e.getGeometry()));
                    ps.setInt(10, e.isClosed() ? 1 : 0);
                    ps.setDouble(11, e.getTrafficMultiplier());
                    ps.addBatch();
                    if (++batch % 5000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public RoadGraph loadGraph() {
        RoadGraph graph = new RoadGraph();
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id,lat,lon FROM road_nodes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) graph.addNode(new RoadNode(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM road_edges");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<GeoPoint> geometry = Json.readList(rs.getString("geometry"), POINTS);
                    RoadEdge edge = new RoadEdge(
                            rs.getString("id"), rs.getLong("from_node"), rs.getLong("to_node"),
                            rs.getLong("way_id"), rs.getString("name"), rs.getString("road_type"),
                            rs.getDouble("distance_m"), rs.getDouble("travel_time_s"), geometry,
                            rs.getInt("closed") == 1, rs.getDouble("traffic_multiplier"));
                    graph.addEdge(edge);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot load road network", ex);
        }
        return graph;
    }

    public boolean hasNetwork() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM road_nodes");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException ex) {
            return false;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodes", scalar("SELECT COUNT(*) FROM road_nodes"));
        stats.put("edges", scalar("SELECT COUNT(*) FROM road_edges"));
        stats.put("namedEdges", scalar("SELECT COUNT(*) FROM road_edges WHERE name IS NOT NULL AND name<>''"));
        stats.put("closedEdges", scalar("SELECT COUNT(*) FROM road_edges WHERE closed=1"));
        stats.put("closedLengthMeters", doubleScalar("SELECT COALESCE(SUM(distance_m),0) FROM road_edges WHERE closed=1"));
        stats.put("totalLengthMeters", doubleScalar("SELECT COALESCE(SUM(distance_m),0) FROM road_edges"));
        Map<String, Integer> byType = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT road_type, COUNT(*) c FROM road_edges GROUP BY road_type ORDER BY c DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) byType.put(rs.getString(1), rs.getInt(2));
        } catch (SQLException ignored) {
        }
        stats.put("byRoadType", byType);
        return stats;
    }

    public List<RoadEdge> searchEdges(String query, int limit) {
        List<RoadEdge> result = new ArrayList<>();
        String sql = "SELECT * FROM road_edges WHERE (name LIKE ? OR id LIKE ?) AND name IS NOT NULL " +
                "ORDER BY name, road_type LIMIT ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            String like = "%" + (query == null ? "" : query.trim()) + "%";
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setInt(3, Math.max(1, Math.min(200, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new RoadEdge(rs.getString("id"), rs.getLong("from_node"), rs.getLong("to_node"),
                            rs.getLong("way_id"), rs.getString("name"), rs.getString("road_type"),
                            rs.getDouble("distance_m"), rs.getDouble("travel_time_s"),
                            Json.readList(rs.getString("geometry"), POINTS),
                            rs.getInt("closed") == 1, rs.getDouble("traffic_multiplier")));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot search road edges", ex);
        }
        return result;
    }

    public void setClosed(String edgeId, boolean closed, String reason) {
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE road_edges SET closed=? WHERE id=?")) {
                ps.setInt(1, closed ? 1 : 0);
                ps.setString(2, edgeId);
                if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Unknown road edge: " + edgeId);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE road_closures SET active=0 WHERE edge_id=? AND active=1")) {
                ps.setString(1, edgeId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO road_closures(edge_id,reason,active,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, edgeId);
                ps.setString(2, reason);
                ps.setInt(3, closed ? 1 : 0);
                ps.setString(4, Instant.now().toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setTrafficMultiplier(String edgeId, double multiplier) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE road_edges SET traffic_multiplier=? WHERE id=?")) {
            ps.setDouble(1, Math.max(1.0, Math.min(5.0, multiplier)));
            ps.setString(2, edgeId);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Unknown road edge: " + edgeId);
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot update simulated traffic", ex);
        }
    }

    public List<Map<String, Object>> closures() {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT rc.id,rc.edge_id,rc.reason,rc.active,rc.created_at,re.name,re.road_type," +
                "re.distance_m,re.geometry FROM road_closures rc JOIN road_edges re ON re.id=rc.edge_id " +
                "WHERE rc.active=1 ORDER BY rc.id DESC";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("closureId", rs.getLong("id"));
                row.put("edgeId", rs.getString("edge_id"));
                row.put("reason", rs.getString("reason"));
                row.put("createdAt", rs.getString("created_at"));
                row.put("name", rs.getString("name"));
                row.put("roadType", rs.getString("road_type"));
                row.put("distanceMeters", rs.getDouble("distance_m"));
                row.put("geometry", Json.readList(rs.getString("geometry"), POINTS));
                result.add(row);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read closures", ex);
        }
        return result;
    }

    public int scalar(String sql) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }

    private double doubleScalar(String sql) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDouble(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }
}
