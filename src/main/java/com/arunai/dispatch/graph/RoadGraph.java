package com.arunai.dispatch.graph;

import com.arunai.dispatch.model.RoadEdge;
import com.arunai.dispatch.model.RoadNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adjacency-list road graph.
 *
 * <p>Vertices are OSM road nodes (shared nodes become intersections naturally).
 * Edges are undirected OSM way segments and are shared between both adjacency
 * lists, so a closure affects both directions. All routing is delegated to
 * {@link Dijkstra}, not to an external routing service.</p>
 */
public class RoadGraph {
    private final Map<Long, RoadNode> nodes = new LinkedHashMap<>();
    private final Map<String, RoadEdge> edges = new LinkedHashMap<>();
    private final Map<Long, List<RoadEdge>> adjacency = new LinkedHashMap<>();

    public void addNode(RoadNode node) {
        nodes.put(node.id(), node);
        adjacency.computeIfAbsent(node.id(), ignored -> new ArrayList<>());
    }

    public void addEdge(RoadEdge edge) {
        if (!nodes.containsKey(edge.getFrom()) || !nodes.containsKey(edge.getTo())) {
            throw new IllegalArgumentException("Edge references unknown node: " + edge.getId());
        }
        edges.put(edge.getId(), edge);
        adjacency.get(edge.getFrom()).add(edge);
        adjacency.get(edge.getTo()).add(edge);
    }

    public boolean hasNode(long id) { return nodes.containsKey(id); }
    public RoadNode node(long id) { return nodes.get(id); }
    public Collection<RoadNode> nodes() { return nodes.values(); }
    public Collection<RoadEdge> edges() { return edges.values(); }
    public List<RoadEdge> neighbours(long nodeId) {
        return adjacency.getOrDefault(nodeId, List.of());
    }
    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
    public RoadEdge edge(String id) { return edges.get(id); }

    public Optional<RoadEdge> findEdge(String id) { return Optional.ofNullable(edges.get(id)); }

    /** Brute-force nearest-node snapping. Called only a handful of times per dispatch. */
    public Optional<RoadNode> nearestNode(double lat, double lon) {
        RoadNode best = null;
        double bestDistance = Double.MAX_VALUE;
        for (RoadNode candidate : nodes.values()) {
            double d = GeoMath.approxMeters(lat, lon, candidate.lat(), candidate.lon());
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    public RouteResult route(long source, long target) {
        return Dijkstra.shortestPath(this, source, target);
    }

    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon) {
        Optional<RoadNode> source = nearestNode(fromLat, fromLon);
        Optional<RoadNode> target = nearestNode(toLat, toLon);
        if (source.isEmpty() || target.isEmpty()) {
            return RouteResult.notFound(-1, -1);
        }
        return route(source.get().id(), target.get().id());
    }

    /** Rebuild the edge map after changing closure/traffic flags without reloading nodes. */
    public void applyEdgeState(String edgeId, Boolean closed, Double trafficMultiplier) {
        RoadEdge edge = edges.get(edgeId);
        if (edge == null) throw new IllegalArgumentException("Unknown road edge: " + edgeId);
        if (closed != null) edge.setClosed(closed);
        if (trafficMultiplier != null) edge.setTrafficMultiplier(trafficMultiplier);
    }

    public long closedEdgeCount() {
        return edges.values().stream().filter(RoadEdge::isClosed).count();
    }
}
