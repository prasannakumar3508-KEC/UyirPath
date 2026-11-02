package com.arunai.dispatch;

import com.arunai.dispatch.graph.RoadGraph;
import com.arunai.dispatch.model.GeoPoint;
import com.arunai.dispatch.model.RoadEdge;
import com.arunai.dispatch.model.RoadNode;

import java.util.List;

/** Small deterministic road graphs shared by unit tests. */
public final class TestGraphs {

    private TestGraphs() {}

    public static final long N1 = 1, N2 = 2, N3 = 3, N4 = 4, N5 = 5, N7 = 7;

    public static RoadGraph fork() {
        RoadGraph g = new RoadGraph();
        g.addNode(node(N1, 11.3400, 77.7000));
        g.addNode(node(N2, 11.3400, 77.7100));
        g.addNode(node(N3, 11.3400, 77.7200));
        g.addNode(node(N4, 11.3500, 77.7100));
        g.addNode(node(N5, 11.3600, 77.7000));
        g.addNode(node(N7, 11.4000, 77.8000)); // isolated

        g.addEdge(edge("E12", N1, N2, "Main Road", "primary", 1000, 100,
                List.of(point(N1), point(N2))));
        g.addEdge(edge("E23", N2, N3, "Market Road", "secondary", 1000, 100,
                List.of(point(N2), point(N3))));
        g.addEdge(edge("E13", N1, N3, "Bypass", "trunk", 3000, 400,
                List.of(point(N1), new GeoPoint(11.3400, 77.7150), point(N3))));
        g.addEdge(edge("E24", N2, N4, "Link Road", "tertiary", 800, 90,
                List.of(point(N2), point(N4))));
        g.addEdge(edge("E43", N4, N3, "Hospital Road", "tertiary", 700, 80,
                List.of(point(N4), point(N3))));
        g.addEdge(edge("E35", N3, N5, "North Road", "primary", 500, 60,
                List.of(point(N3), point(N5))));
        return g;
    }

    public static RoadNode node(long id, double lat, double lon) {
        return new RoadNode(id, lat, lon);
    }

    public static GeoPoint point(long nodeId) {
        return switch ((int) nodeId) {
            case 1 -> new GeoPoint(11.3400, 77.7000);
            case 2 -> new GeoPoint(11.3400, 77.7100);
            case 3 -> new GeoPoint(11.3400, 77.7200);
            case 4 -> new GeoPoint(11.3500, 77.7100);
            case 5 -> new GeoPoint(11.3600, 77.7000);
            case 7 -> new GeoPoint(11.4000, 77.8000);
            default -> throw new IllegalArgumentException("No point for node " + nodeId);
        };
    }

    public static RoadEdge edge(String id, long from, long to, String name, String roadType,
                                double metres, double seconds, List<GeoPoint> geometry) {
        return new RoadEdge(id, from, to, 100 + Math.abs(id.hashCode() % 1000L),
                name, roadType, metres, seconds, geometry, false, 1.0);
    }
}
