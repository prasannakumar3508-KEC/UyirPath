package com.uyirpath.dispatch.graph;

import com.uyirpath.dispatch.TestGraphs;
import com.uyirpath.dispatch.model.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DijkstraTest {

    @Test
    @DisplayName("Dijkstra chooses the path with the lowest travel time, not the shortest distance")
    void choosesFastestPathByTravelTime() {
        RoadGraph graph = TestGraphs.fork();
        // 1-2-3 = 200 s / 2.0 km; 1-2-4-3 = 270 s / 2.5 km; 1-3 = 400 s / 3.0 km
        RouteResult route = graph.route(TestGraphs.N1, TestGraphs.N3);

        assertTrue(route.found());
        assertEquals(List.of(1L, 2L, 3L), route.nodeIds());
        assertEquals(List.of("E12", "E23"), route.edgeIds());
        assertEquals(200, route.travelTimeSeconds(), 0.001);
        assertEquals(2000, route.distanceMeters(), 0.001);
    }

    @Test
    @DisplayName("Dijkstra skips a closed edge and reroutes through the next fastest path")
    void reroutesAroundClosedEdge() {
        RoadGraph graph = TestGraphs.fork();
        graph.edge("E23").setClosed(true);

        RouteResult route = graph.route(TestGraphs.N1, TestGraphs.N3);

        assertTrue(route.found());
        assertEquals(List.of(1L, 2L, 4L, 3L), route.nodeIds());
        assertEquals(List.of("E12", "E24", "E43"), route.edgeIds());
        assertEquals(270, route.travelTimeSeconds(), 0.001);
        assertFalse(route.edgeIds().contains("E23"));
    }

    @Test
    @DisplayName("Dijkstra applies the simulated traffic multiplier to edge weight")
    void appliesTrafficMultiplier() {
        RoadGraph graph = TestGraphs.fork();
        graph.edge("E23").setTrafficMultiplier(5.0); // 100 s becomes 500 s

        RouteResult route = graph.route(TestGraphs.N1, TestGraphs.N3);

        assertTrue(route.found());
        assertEquals(List.of(1L, 2L, 4L, 3L), route.nodeIds());
        assertEquals(270, route.travelTimeSeconds(), 0.001);
        assertEquals(5.0, graph.edge("E23").getTrafficMultiplier(), 0.001);
    }

    @Test
    @DisplayName("Dijkstra reconstructs ordered OSM geometry and sums distance and time")
    void reconstructsOrderedGeometry() {
        RoadGraph graph = TestGraphs.fork();
        RouteResult route = graph.route(TestGraphs.N1, TestGraphs.N3);

        List<GeoPoint> geometry = route.geometry();
        assertEquals(3, geometry.size());
        assertEquals(11.3400, geometry.get(0).lat(), 1e-9);
        assertEquals(77.7000, geometry.get(0).lon(), 1e-9);
        assertEquals(77.7100, geometry.get(1).lon(), 1e-9);
        assertEquals(77.7200, geometry.get(2).lon(), 1e-9);
        assertEquals(200, route.travelTimeSeconds(), 0.001);
        assertEquals(2000, route.distanceMeters(), 0.001);
    }

    @Test
    @DisplayName("Dijkstra returns not-found for an unreachable or unknown target")
    void reportsUnreachableTarget() {
        RoadGraph graph = TestGraphs.fork();

        RouteResult isolated = graph.route(TestGraphs.N1, TestGraphs.N7);
        RouteResult unknown = graph.route(TestGraphs.N1, 9999);

        assertFalse(isolated.found());
        assertFalse(unknown.found());
        assertTrue(isolated.geometry().isEmpty());
    }
}
