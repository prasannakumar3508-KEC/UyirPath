package com.uyirpath.dispatch.graph;

import com.uyirpath.dispatch.model.GeoPoint;
import com.uyirpath.dispatch.model.RoadEdge;
import com.uyirpath.dispatch.model.RoadNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Custom Dijkstra shortest-path implementation.
 *
 * <p>Requirement mapping:
 * <ul>
 *   <li>Source/destination: OSM graph node ids.</li>
 *   <li>Weight: estimated travel time in seconds (not raw distance).</li>
 *   <li>Closed edges: skipped completely.</li>
 *   <li>PriorityQueue: selects the currently cheapest frontier node.</li>
 *   <li>Path + geometry: reconstructed through predecessor maps and the
 *       ordered edge geometry, so the frontend draws real road shapes.</li>
 * </ul>
 *
 * <p>Complexity: O((V + E) log V) with a binary heap.</p>
 */
public final class Dijkstra {
    private static final double EPSILON = 1e-9;

    private record State(long nodeId, double cost) {}

    private Dijkstra() {}

    public static RouteResult shortestPath(RoadGraph graph, long source, long target) {
        if (!graph.hasNode(source) || !graph.hasNode(target)) {
            return RouteResult.notFound(source, target);
        }
        if (source == target) {
            RoadNode node = graph.node(source);
            return new RouteResult(source, target, List.of(source), List.of(),
                    List.of(node.point()), 0, 0);
        }

        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> previousNode = new HashMap<>();
        Map<Long, RoadEdge> previousEdge = new HashMap<>();
        PriorityQueue<State> frontier = new PriorityQueue<>(Comparator.comparingDouble(State::cost));

        best.put(source, 0.0);
        frontier.add(new State(source, 0.0));

        while (!frontier.isEmpty()) {
            State current = frontier.poll();
            Double known = best.get(current.nodeId());
            if (known == null || current.cost() > known + EPSILON) {
                continue; // stale heap entry
            }
            if (current.nodeId() == target) break;

            for (RoadEdge edge : graph.neighbours(current.nodeId())) {
                if (edge.isClosed()) continue; // road closure rule

                long next = edge.getFrom() == current.nodeId() ? edge.getTo() : edge.getFrom();
                double candidate = current.cost() + edge.effectiveSeconds();
                Double existing = best.get(next);
                if (existing == null || candidate < existing - EPSILON) {
                    best.put(next, candidate);
                    previousNode.put(next, current.nodeId());
                    previousEdge.put(next, edge);
                    frontier.add(new State(next, candidate));
                }
            }
        }

        if (!best.containsKey(target)) {
            return RouteResult.notFound(source, target);
        }

        // Reconstruct backwards, then reverse.
        List<Long> nodePath = new ArrayList<>();
        List<RoadEdge> edgePath = new ArrayList<>();
        long cursor = target;
        nodePath.add(cursor);
        while (cursor != source) {
            RoadEdge edge = previousEdge.get(cursor);
            Long previous = previousNode.get(cursor);
            if (edge == null || previous == null) {
                return RouteResult.notFound(source, target);
            }
            edgePath.add(edge);
            cursor = previous;
            nodePath.add(cursor);
        }
        Collections.reverse(nodePath);
        Collections.reverse(edgePath);

        List<GeoPoint> geometry = new ArrayList<>();
        double distance = 0;
        double time = 0;
        long fromNode = source;
        for (RoadEdge edge : edgePath) {
            List<GeoPoint> points = edge.getGeometry();
            if (edge.getTo() == fromNode) {
                points = new ArrayList<>(points);
                Collections.reverse(points);
            }
            for (GeoPoint point : points) {
                if (geometry.isEmpty() || !samePoint(geometry.get(geometry.size() - 1), point)) {
                    geometry.add(point);
                }
            }
            distance += edge.getDistanceMeters();
            time += edge.effectiveSeconds();
            fromNode = edge.getFrom() == fromNode ? edge.getTo() : edge.getFrom();
        }

        List<String> edgeIds = edgePath.stream().map(RoadEdge::getId).toList();
        return new RouteResult(source, target, List.copyOf(nodePath), edgeIds,
                List.copyOf(geometry), distance, time);
    }

    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return Math.abs(a.lat() - b.lat()) < 1e-12 && Math.abs(a.lon() - b.lon()) < 1e-12;
    }
}
