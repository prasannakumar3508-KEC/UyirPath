package com.uyirpath.dispatch.osm;

import com.uyirpath.dispatch.config.AppConfig;
import com.uyirpath.dispatch.graph.GeoMath;
import com.uyirpath.dispatch.model.GeoPoint;
import com.uyirpath.dispatch.model.RoadEdge;
import com.uyirpath.dispatch.model.RoadNode;
import com.uyirpath.dispatch.repository.RoadRepository;
import com.uyirpath.dispatch.repository.SettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads OpenStreetMap road data from Overpass, parses nodes/ways, and
 * stores a local routable graph in SQLite.
 *
 * <p>Only public OSM services are used. The raw response is cached under
 * data/osm so later starts never re-download the network. If all services fail,
 * {@link #syntheticFallback()} can provide a deliberately labelled grid; it is
 * NOT presented as real Erode roads.</p>
 */
public class OsmImporter {
    public record ImportResult(String source, int nodes, int edges, String message,
                               String importedAt, boolean fallback) {}

    private static final Set<String> ALLOWED = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "living_street", "service", "road");

    private static final Set<String> EXCLUDED = Set.of(
            "footway", "path", "cycleway", "steps", "pedestrian", "track", "bridleway",
            "construction", "proposed", "raceway", "corridor", "platform", "elevator");

    private final RoadRepository roads;
    private final SettingsRepository settings;
    private final Path cachePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public OsmImporter(RoadRepository roads, SettingsRepository settings, Path cachePath) {
        this.roads = roads;
        this.settings = settings;
        this.cachePath = cachePath;
    }

    public ImportResult importLive(String bbox) {
        String query = buildQuery(bbox);
        for (String url : List.of(AppConfig.OVERPASS_URL, AppConfig.OVERPASS_FALLBACK_URL)) {
            try {
                String json = fetch(url, query);
                ImportResult result = importJson(json, "OSM_OVERPASS", false);
                saveCache(json);
                return result;
            } catch (Exception ex) {
                System.err.println("[osm] " + url + " failed: " + ex.getMessage());
            }
        }
        return new ImportResult("NOT_LOADED", 0, 0,
                "Overpass services unavailable. Existing data (if any) was kept. "
                        + "You can retry, import a cached file, or use the clearly labelled synthetic fallback.",
                Instant.now().toString(), false);
    }

    public ImportResult importCached() {
        try {
            if (!Files.exists(cachePath)) {
                return new ImportResult("NOT_LOADED", 0, 0, "No cached OSM file at " + cachePath,
                        Instant.now().toString(), false);
            }
            String json = Files.readString(cachePath, StandardCharsets.UTF_8);
            return importJson(json, "OSM_CACHE", false);
        } catch (Exception ex) {
            return new ImportResult("NOT_LOADED", 0, 0, "Cached OSM import failed: " + ex.getMessage(),
                    Instant.now().toString(), false);
        }
    }

    private String fetch(String url, String query) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(4))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "UyirPathAcademicProject/1.0 (Erode ambulance dispatch student project)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        String text = response.body();
        if (text.contains("\"remark\"") && text.length() < 5000 && !text.contains("\"elements\"")) {
            throw new IllegalStateException("Overpass remark: " + text.substring(0, Math.min(300, text.length())));
        }
        return text;
    }

    public ImportResult importJson(String json, String source, boolean fallback) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode elements = root.path("elements");
        if (!elements.isArray() || elements.isEmpty()) {
            throw new IllegalArgumentException("OSM response contains no elements");
        }

        Map<Long, double[]> coordinates = new HashMap<>();
        for (JsonNode element : elements) {
            if ("node".equals(element.path("type").asText())) {
                coordinates.put(element.path("id").asLong(),
                        new double[]{element.path("lat").asDouble(), element.path("lon").asDouble()});
            }
        }

        Map<Long, RoadNode> usedNodes = new LinkedHashMap<>();
        Map<String, RoadEdge> edges = new LinkedHashMap<>();
        Map<String, Integer> speeds = AppConfig.roadSpeedsKph();

        for (JsonNode element : elements) {
            if (!"way".equals(element.path("type").asText())) continue;
            JsonNode tags = element.path("tags");
            String highway = tags.path("highway").asText("");
            if (!ALLOWED.contains(highway) || EXCLUDED.contains(highway)) continue;
            if ("yes".equalsIgnoreCase(tags.path("area").asText())) continue;
            if ("no".equalsIgnoreCase(tags.path("access").asText())
                    || "no".equalsIgnoreCase(tags.path("motor_vehicle").asText())) continue;

            String name = tags.path("name").asText(null);
            long wayId = element.path("id").asLong();
            JsonNode nodeIds = element.path("nodes");
            if (!nodeIds.isArray()) continue;

            int speedKph = speeds.getOrDefault(highway, 20);
            double speedMs = speedKph * 1000.0 / 3600.0;

            for (int i = 0; i < nodeIds.size() - 1; i++) {
                long from = nodeIds.get(i).asLong();
                long to = nodeIds.get(i + 1).asLong();
                if (from == to) continue;
                double[] a = coordinates.get(from);
                double[] b = coordinates.get(to);
                if (a == null || b == null) continue;

                usedNodes.putIfAbsent(from, new RoadNode(from, a[0], a[1]));
                usedNodes.putIfAbsent(to, new RoadNode(to, b[0], b[1]));

                double distance = GeoMath.haversine(a[0], a[1], b[0], b[1]);
                if (distance <= 0.2) continue;
                String id = "w" + wayId + "_" + from + "_" + to;
                List<GeoPoint> geometry = List.of(new GeoPoint(a[0], a[1]), new GeoPoint(b[0], b[1]));
                RoadEdge edge = new RoadEdge(id, from, to, wayId, name, highway,
                        distance, distance / speedMs, geometry, false, 1.0);
                edges.putIfAbsent(id, edge);
            }
        }

        if (edges.size() < 10) {
            throw new IllegalArgumentException("OSM parse produced too few routable edges (" + edges.size() + ")");
        }

        roads.saveNetwork(usedNodes.values(), edges.values());
        String now = Instant.now().toString();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("osm_source", source);
        values.put("osm_imported_at", now);
        values.put("network_nodes", String.valueOf(usedNodes.size()));
        values.put("network_edges", String.valueOf(edges.size()));
        values.put("osm_cache_file", cachePath.toString());
        settings.putAll(values);
        return new ImportResult(source, usedNodes.size(), edges.size(),
                "Imported " + usedNodes.size() + " road nodes and " + edges.size() + " road segments.",
                now, fallback);
    }

    /**
     * Deliberately synthetic fallback. Every road in it is generated by this
     * method, is connected to the five demo bases for routing practice, and is
     * labelled SYNTHETIC_FALLBACK in the UI.
     */
    public ImportResult syntheticFallback() {
        Map<Long, RoadNode> nodes = new LinkedHashMap<>();
        Map<String, RoadEdge> edges = new LinkedHashMap<>();
        long id = 9_000_000_000L;
        int columns = 13;
        int rows = 11;
        double minLat = 11.22, maxLat = 11.48, minLon = 77.50, maxLon = 77.80;
        long[][] grid = new long[rows][columns];

        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < columns; col++) {
                double lat = minLat + (maxLat - minLat) * r / (rows - 1);
                double lon = minLon + (maxLon - minLon) * col / (columns - 1);
                long nodeId = id++;
                grid[r][col] = nodeId;
                nodes.put(nodeId, new RoadNode(nodeId, lat, lon));
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < columns; col++) {
                if (col + 1 < columns) connect(nodes, edges, grid[r][col], grid[r][col + 1], "Synthetic Grid East-West");
                if (r + 1 < rows) connect(nodes, edges, grid[r][col], grid[r + 1][col], "Synthetic Grid North-South");
            }
        }

        // Spokes to the real base/hospital coordinates only make the fallback
        // usable for a demonstration. They are NOT real roads.
        double[][] spokes = {
                {11.3470648, 77.7199695}, {11.3286258, 77.7257896}, {11.2764669, 77.5836267},
                {11.4405993, 77.6824386}, {11.3954841, 77.6580812},
                {11.3371539, 77.7109247}, {11.3393048, 77.7179260}, {11.3259471, 77.7302149},
                {11.3504266, 77.7311274}
        };
        for (double[] point : spokes) {
            long spokeId = id++;
            nodes.put(spokeId, new RoadNode(spokeId, point[0], point[1]));
            long nearest = nearest(grid, minLat, maxLat, minLon, maxLon, columns, rows, point[0], point[1]);
            connect(nodes, edges, spokeId, nearest, "Synthetic Access Spoke");
        }

        roads.saveNetwork(nodes.values(), edges.values());
        String now = Instant.now().toString();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("osm_source", "SYNTHETIC_FALLBACK");
        values.put("osm_imported_at", now);
        values.put("network_nodes", String.valueOf(nodes.size()));
        values.put("network_edges", String.valueOf(edges.size()));
        settings.putAll(values);
        return new ImportResult("SYNTHETIC_FALLBACK", nodes.size(), edges.size(),
                "SYNTHETIC DEMONSTRATION GRID — these are generated roads, not real Erode roads. "
                        + "Use for UI practice only; import OSM data before presenting routes.",
                now, true);
    }

    private long nearest(long[][] grid, double minLat, double maxLat, double minLon, double maxLon,
                         int columns, int rows, double lat, double lon) {
        int col = (int) Math.round((lon - minLon) / (maxLon - minLon) * (columns - 1));
        int row = (int) Math.round((lat - minLat) / (maxLat - minLat) * (rows - 1));
        col = Math.max(0, Math.min(columns - 1, col));
        row = Math.max(0, Math.min(rows - 1, row));
        return grid[row][col];
    }

    private void connect(Map<Long, RoadNode> nodes, Map<String, RoadEdge> edges, long a, long b, String name) {
        RoadNode from = nodes.get(a);
        RoadNode to = nodes.get(b);
        double distance = GeoMath.haversine(from.lat(), from.lon(), to.lat(), to.lon());
        double speedMs = 25 * 1000.0 / 3600.0;
        String edgeId = "demo_" + a + "_" + b;
        edges.put(edgeId, new RoadEdge(edgeId, a, b, -1, name, "demo",
                distance, distance / speedMs,
                List.of(from.point(), to.point()), false, 1.0));
    }

    private void saveCache(String json) {
        try {
            Files.createDirectories(cachePath.toAbsolutePath().getParent());
            Files.writeString(cachePath, json, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            System.err.println("[osm] Could not save cache: " + ex.getMessage());
        }
    }

    private String buildQuery(String bbox) {
        return "[out:json][timeout:180];"
                + "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|"
                + "residential|living_street|service|road)(_link)?$\"]"
                + "[\"access\"!~\"^(no|private)$\"][\"motor_vehicle\"!~\"^no$\"](" + bbox + ");"
                + "out body;>;out skel qt;";
    }
}
