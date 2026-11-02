package com.arunai.dispatch.graph;

import com.arunai.dispatch.model.GeoPoint;

import java.util.List;

/**
 * Immutable output of Dijkstra. The geometry is the concatenated, ordered OSM
 * road shape that Leaflet draws; nodeIds and edgeIds make the result testable.
 */
public record RouteResult(
        long sourceNodeId,
        long targetNodeId,
        List<Long> nodeIds,
        List<String> edgeIds,
        List<GeoPoint> geometry,
        double distanceMeters,
        double travelTimeSeconds) {

    public boolean found() { return !nodeIds.isEmpty(); }

    public static RouteResult notFound(long source, long target) {
        return new RouteResult(source, target, List.of(), List.of(), List.of(), 0, 0);
    }
}
