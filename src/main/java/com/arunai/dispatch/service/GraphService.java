package com.arunai.dispatch.service;

import com.arunai.dispatch.config.AppConfig;
import com.arunai.dispatch.graph.GeoMath;
import com.arunai.dispatch.graph.RoadGraph;
import com.arunai.dispatch.graph.RouteResult;
import com.arunai.dispatch.model.RoadEdge;
import com.arunai.dispatch.model.RoadNode;
import com.arunai.dispatch.osm.OsmImporter;
import com.arunai.dispatch.repository.RoadRepository;
import com.arunai.dispatch.repository.SettingsRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for the in-memory road graph and OSM import state.
 * The graph is always rebuilt from SQLite, never trusted from browser state.
 */
public class GraphService {
    private final RoadRepository roads;
    private final SettingsRepository settings;
    private final OsmImporter importer;
    private volatile RoadGraph graph;

    public GraphService(RoadRepository roads, SettingsRepository settings, OsmImporter importer) {
        this.roads = roads;
        this.settings = settings;
        this.importer = importer;
        this.graph = roads.loadGraph();
    }

    /** Make sure the app can route: use DB, then cache, then clearly-labelled fallback. */
    public void bootstrap() {
        if (roads.hasNetwork()) {
            reload();
            return;
        }
        OsmImporter.ImportResult cached = importer.importCached();
        if (!"NOT_LOADED".equals(cached.source())) {
            reload();
            return;
        }
        importer.syntheticFallback();
        reload();
    }

    public RoadGraph graph() { return graph; }

    public void reload() {
        graph = roads.loadGraph();
    }

    public OsmImporter.ImportResult importLive() {
        OsmImporter.ImportResult result = importer.importLive(settings.get("osm_bbox", AppConfig.OSM_BBOX));
        if (!"NOT_LOADED".equals(result.source())) reload();
        return result;
    }

    public OsmImporter.ImportResult importCached() {
        OsmImporter.ImportResult result = importer.importCached();
        if (!"NOT_LOADED".equals(result.source())) reload();
        return result;
    }

    public OsmImporter.ImportResult useSyntheticFallback() {
        OsmImporter.ImportResult result = importer.syntheticFallback();
        reload();
        return result;
    }

    public Optional<RoadNode> snap(double lat, double lon) {
        return graph.nearestNode(lat, lon);
    }

    public RouteResult route(double fromLat, double fromLon, double toLat, double toLon) {
        return graph.route(fromLat, fromLon, toLat, toLon);
    }

    public RouteResult routeNodes(long from, long to) {
        return graph.route(from, to);
    }

    public boolean insideOperatingArea(double lat, double lon) {
        return GeoMath.inBoundingBox(lat, lon,
                doubleSetting("operating_area_min_lat", AppConfig.AREA_MIN_LAT),
                doubleSetting("operating_area_min_lon", AppConfig.AREA_MIN_LON),
                doubleSetting("operating_area_max_lat", AppConfig.AREA_MAX_LAT),
                doubleSetting("operating_area_max_lon", AppConfig.AREA_MAX_LON));
    }

    public double[] operatingArea() {
        return new double[]{
                doubleSetting("operating_area_min_lat", AppConfig.AREA_MIN_LAT),
                doubleSetting("operating_area_min_lon", AppConfig.AREA_MIN_LON),
                doubleSetting("operating_area_max_lat", AppConfig.AREA_MAX_LAT),
                doubleSetting("operating_area_max_lon", AppConfig.AREA_MAX_LON)
        };
    }

    private double doubleSetting(String key, double fallback) {
        try {
            return Double.parseDouble(settings.get(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public String source() {
        return settings.get("osm_source", "NOT_LOADED");
    }

    public boolean isRealOsm() {
        return source().startsWith("OSM");
    }

    public Map<String, Object> networkSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", source());
        summary.put("realOsm", isRealOsm());
        summary.put("importedAt", settings.get("osm_imported_at", ""));
        summary.put("bbox", settings.get("osm_bbox", AppConfig.OSM_BBOX));
        summary.put("cacheFile", settings.get("osm_cache_file", AppConfig.OSM_CACHE.toString()));
        summary.put("operatingArea", operatingArea());
        summary.put("stats", roads.stats());
        summary.put("closures", roads.closures());
        summary.put("attribution", "© OpenStreetMap contributors, ODbL 1.0");
        summary.put("notice", notice(source()));
        return summary;
    }

    public String notice(String source) {
        return switch (source) {
            case "OSM_OVERPASS" -> "Road geometry and names imported from OpenStreetMap via Overpass. "
                    + "Speeds are class-based estimates, not live traffic.";
            case "OSM_CACHE" -> "Road geometry imported from the local OpenStreetMap cache. "
                    + "Speeds are class-based estimates, not live traffic.";
            case "SYNTHETIC_FALLBACK" -> "SYNTHETIC DEMONSTRATION GRID: these roads are generated, "
                    + "not real Erode roads. Do not present them as OSM data.";
            default -> "No road network loaded. Import OpenStreetMap data from the Road Network screen.";
        };
    }

    public void setClosure(String edgeId, boolean closed, String reason) {
        synchronized (this) {
            roads.setClosed(edgeId, closed, reason);
            try {
                graph.applyEdgeState(edgeId, closed, null);
            } catch (IllegalArgumentException ex) {
                reload();
            }
        }
    }

    public void setTraffic(String edgeId, double multiplier) {
        synchronized (this) {
            roads.setTrafficMultiplier(edgeId, multiplier);
            graph.applyEdgeState(edgeId, null, multiplier);
        }
    }

    public List<RoadEdge> search(String query, int limit) {
        return roads.searchEdges(query, limit);
    }

    public Map<String, Object> stats() {
        return roads.stats();
    }
}
