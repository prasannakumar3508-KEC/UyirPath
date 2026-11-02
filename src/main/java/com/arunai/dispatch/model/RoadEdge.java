package com.arunai.dispatch.model;

import java.util.List;

/**
 * An undirected road segment between two graph vertices.
 *
 * <p>Travel time is the routing weight. It is derived from segment length and
 * a speed assumption for the OSM highway class. The segment keeps its original
 * OSM geometry so the map can draw the real road shape instead of a straight
 * line. A single edge object is shared by both adjacency directions, therefore
 * closing it closes both directions.</p>
 */
public class RoadEdge {
    private final String id;
    private final long from;
    private final long to;
    private final long wayId;
    private final String name;
    private final String roadType;
    private final double distanceMeters;
    private final double travelTimeSeconds;
    private final List<GeoPoint> geometry;

    private boolean closed;
    private double trafficMultiplier = 1.0;

    public RoadEdge(String id, long from, long to, long wayId, String name, String roadType,
                    double distanceMeters, double travelTimeSeconds, List<GeoPoint> geometry,
                    boolean closed, double trafficMultiplier) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.wayId = wayId;
        this.name = name;
        this.roadType = roadType;
        this.distanceMeters = distanceMeters;
        this.travelTimeSeconds = travelTimeSeconds;
        this.geometry = List.copyOf(geometry);
        this.closed = closed;
        this.trafficMultiplier = trafficMultiplier <= 0 ? 1.0 : trafficMultiplier;
    }

    public String getId() { return id; }
    public long getFrom() { return from; }
    public long getTo() { return to; }
    public long getWayId() { return wayId; }
    public String getName() { return name; }
    public String getRoadType() { return roadType; }
    public double getDistanceMeters() { return distanceMeters; }
    public double getTravelTimeSeconds() { return travelTimeSeconds; }
    public List<GeoPoint> getGeometry() { return geometry; }
    public boolean isClosed() { return closed; }
    public double getTrafficMultiplier() { return trafficMultiplier; }

    public void setClosed(boolean closed) { this.closed = closed; }
    public void setTrafficMultiplier(double trafficMultiplier) {
        this.trafficMultiplier = Math.max(1.0, Math.min(5.0, trafficMultiplier));
    }

    /** Effective Dijkstra weight in seconds, including the simulated traffic factor. */
    public double effectiveSeconds() { return travelTimeSeconds * trafficMultiplier; }
}
