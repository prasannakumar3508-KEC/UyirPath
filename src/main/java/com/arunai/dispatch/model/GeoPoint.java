package com.arunai.dispatch.model;

/** A latitude/longitude pair used for map geometry. */
public record GeoPoint(double lat, double lon) {
    public GeoPoint {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Invalid coordinate: " + lat + "," + lon);
        }
    }
}
