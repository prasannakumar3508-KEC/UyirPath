package com.uyirpath.dispatch.model;

/** A vertex in the road graph: an OpenStreetMap node or a demo-grid node. */
public record RoadNode(long id, double lat, double lon) {
    public GeoPoint point() { return new GeoPoint(lat, lon); }
}
