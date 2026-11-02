package com.arunai.dispatch.graph;

/** Small geospatial helpers. Coordinates are WGS84 (as used by OpenStreetMap). */
public final class GeoMath {
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private GeoMath() {}

    /** Great-circle distance using the haversine formula, in metres. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Fast local distance used only to rank nearby graph nodes. The error over
     * a city-sized area is small enough for nearest-node snapping.
     */
    public static double approxMeters(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
    }

    public static boolean inBoundingBox(double lat, double lon,
                                        double minLat, double minLon,
                                        double maxLat, double maxLon) {
        return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    }
}
