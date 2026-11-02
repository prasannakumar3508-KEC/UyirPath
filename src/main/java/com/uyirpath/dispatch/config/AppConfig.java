package com.uyirpath.dispatch.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Central configuration defaults. Values can be overridden in the settings table. */
public final class AppConfig {
    public static final String PRODUCT_NAME = "UyirPath";
    public static final String PRODUCT_SUBTITLE = "Erode Emergency Dispatch Grid";
    public static final String VERSION = "1.0.0";
    public static final int DEFAULT_PORT = 8080;

    /** Geographic operating area for incident validation. */
    public static final double AREA_MIN_LAT = 11.18;
    public static final double AREA_MIN_LON = 77.40;
    public static final double AREA_MAX_LAT = 11.55;
    public static final double AREA_MAX_LON = 77.85;

    /** Default Overpass bounding box (south, west, north, east). */
    public static final String OSM_BBOX = "11.20,77.45,11.50,77.82";
    public static final String OVERPASS_URL = "https://overpass.kumi.systems/api/interpreter";
    public static final String OVERPASS_FALLBACK_URL = "https://overpass-api.de/api/interpreter";

    public static final Path DB_PATH = Path.of("data", "uyirpath.db");
    public static final Path OSM_CACHE = Path.of("data", "osm", "erode-network.json");
    public static final Path FRONTEND_DIR = Path.of("frontend");

    private AppConfig() {}

    /** Speed assumptions by OSM highway class, used to convert metres to seconds. */
    public static Map<String, Integer> roadSpeedsKph() {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        speeds.put("motorway", 80);
        speeds.put("motorway_link", 45);
        speeds.put("trunk", 70);
        speeds.put("trunk_link", 40);
        speeds.put("primary", 50);
        speeds.put("primary_link", 35);
        speeds.put("secondary", 40);
        speeds.put("secondary_link", 30);
        speeds.put("tertiary", 35);
        speeds.put("tertiary_link", 25);
        speeds.put("unclassified", 30);
        speeds.put("residential", 25);
        speeds.put("living_street", 15);
        speeds.put("service", 15);
        speeds.put("road", 25);
        return speeds;
    }

    public static Map<String, String> defaultSettings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("operating_area_min_lat", String.valueOf(AREA_MIN_LAT));
        s.put("operating_area_min_lon", String.valueOf(AREA_MIN_LON));
        s.put("operating_area_max_lat", String.valueOf(AREA_MAX_LAT));
        s.put("operating_area_max_lon", String.valueOf(AREA_MAX_LON));
        s.put("simulation_speed", "8");
        s.put("simulation_paused", "false");
        s.put("return_policy", "ALWAYS_RETURN");
        s.put("demo_admission", "true");
        s.put("osm_source", "NOT_LOADED");
        s.put("osm_bbox", OSM_BBOX);
        s.put("osm_imported_at", "");
        s.put("osm_cache_file", OSM_CACHE.toString());
        s.put("network_nodes", "0");
        s.put("network_edges", "0");
        s.put("demo_reset_at", "");
        return s;
    }
}
