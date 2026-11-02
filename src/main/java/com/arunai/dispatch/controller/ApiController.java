package com.arunai.dispatch.controller;

import com.arunai.dispatch.config.AppConfig;
import com.arunai.dispatch.model.Ambulance;
import com.arunai.dispatch.model.Dispatch;
import com.arunai.dispatch.model.Emergency;
import com.arunai.dispatch.model.EmergencyEvent;
import com.arunai.dispatch.model.EmergencyStatus;
import com.arunai.dispatch.model.Facility;
import com.arunai.dispatch.model.GeoPoint;
import com.arunai.dispatch.model.Hospital;
import com.arunai.dispatch.model.IncidentType;
import com.arunai.dispatch.model.RoadEdge;
import com.arunai.dispatch.model.Severity;
import com.arunai.dispatch.graph.RouteResult;
import com.arunai.dispatch.osm.OsmImporter;
import com.arunai.dispatch.repository.AmbulanceRepository;
import com.arunai.dispatch.repository.Database;
import com.arunai.dispatch.repository.DispatchRepository;
import com.arunai.dispatch.repository.EmergencyRepository;
import com.arunai.dispatch.repository.EventRepository;
import com.arunai.dispatch.repository.HospitalRepository;
import com.arunai.dispatch.repository.SettingsRepository;
import com.arunai.dispatch.service.AnalyticsService;
import com.arunai.dispatch.service.DemoResetService;
import com.arunai.dispatch.service.DispatchService;
import com.arunai.dispatch.service.GraphService;
import com.arunai.dispatch.service.SelectionResult;
import com.arunai.dispatch.service.SimulationService;
import com.arunai.dispatch.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for the whole application. The handler deliberately keeps
 * database entities out of error messages; validation errors are reported as
 * structured JSON, while unexpected failures return a generic 500 message.
 */
public class ApiController implements HttpHandler {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Database db;
    private final AmbulanceRepository ambulances;
    private final HospitalRepository hospitals;
    private final EmergencyRepository emergencies;
    private final DispatchRepository dispatches;
    private final EventRepository events;
    private final SettingsRepository settings;
    private final GraphService graph;
    private final DispatchService dispatchService;
    private final SimulationService simulation;
    private final AnalyticsService analytics;
    private final DemoResetService demoReset;
    private final long startedAt = System.currentTimeMillis();

    public ApiController(Database db, AmbulanceRepository ambulances, HospitalRepository hospitals,
                         EmergencyRepository emergencies, DispatchRepository dispatches,
                         EventRepository events, SettingsRepository settings, GraphService graph,
                         DispatchService dispatchService, SimulationService simulation,
                         AnalyticsService analytics, DemoResetService demoReset) {
        this.db = db;
        this.ambulances = ambulances;
        this.hospitals = hospitals;
        this.emergencies = emergencies;
        this.dispatches = dispatches;
        this.events = events;
        this.settings = settings;
        this.graph = graph;
        this.dispatchService = dispatchService;
        this.simulation = simulation;
        this.analytics = analytics;
        this.demoReset = demoReset;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String suffix = path.startsWith("/api") ? path.substring(4) : path;
            if (suffix.isBlank()) suffix = "/";
            String[] s = suffix.substring(1).split("/");
            String method = exchange.getRequestMethod().toUpperCase();
            dispatch(exchange, method, s);
        } catch (HandledResponse ignored) {
            // A 405 response was already written by requireGet/requirePost.
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, "VALIDATION_ERROR", ex.getMessage());
        } catch (IllegalStateException ex) {
            sendError(exchange, 409, "CONFLICT", ex.getMessage());
        } catch (Exception ex) {
            System.err.println("[api] " + exchange.getRequestURI() + " failed: " + ex);
            sendError(exchange, 500, "INTERNAL_ERROR", "The server could not complete this request.");
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, String method, String[] s) throws IOException {
        String head = s[0];
        String second = s.length > 1 ? s[1] : "";
        switch (head) {
            case "health" -> {
                requireGet(exchange, method);
                health(exchange);
            }
            case "ambulances" -> ambulances(exchange, method, s);
            case "hospitals" -> hospitals(exchange, method, s);
            case "emergencies" -> emergencies(exchange, method, s);
            case "dispatches" -> dispatches(exchange, method, s);
            case "dispatch" -> {
                requirePost(exchange, method);
                if (s.length > 1 && "manual".equals(second)) manualDispatch(exchange);
                else autoDispatch(exchange);
            }
            case "complete-trip" -> {
                requirePost(exchange, method);
                completeTrip(exchange);
            }
            case "selection" -> {
                requireGet(exchange, method);
                if (s.length < 2) throw new IllegalArgumentException("Emergency id required");
                selection(exchange, s[1]);
            }
            case "queue" -> {
                requireGet(exchange, method);
                sendJson(exchange, 200, Map.of("queue", dispatchService.queueSnapshot()));
            }
            case "network" -> network(exchange, method, s);
            case "route" -> {
                requireGet(exchange, method);
                route(exchange);
            }
            case "road" -> road(exchange, method, s);
            case "history" -> {
                requireGet(exchange, method);
                history(exchange);
            }
            case "analytics" -> {
                requireGet(exchange, method);
                sendJson(exchange, 200, analytics.analytics());
            }
            case "events" -> {
                requireGet(exchange, method);
                sendJson(exchange, 200, Map.of("events", events.recent(limitParam(exchange, 30, 100))));
            }
            case "settings" -> settings(exchange, method);
            case "simulation" -> simulation(exchange, method);
            case "demo" -> demo(exchange, method, second);
            case "osm" -> osm(exchange, method);
            default -> sendError(exchange, 404, "NOT_FOUND", "Unknown API endpoint");
        }
    }

    // ---------------------------------------------------------------- health

    private void health(HttpExchange exchange) throws IOException {
        boolean dbOk;
        try (Connection c = db.open(); var ps = c.prepareStatement("SELECT 1"); ResultSet rs = ps.executeQuery()) {
            dbOk = rs.next();
        } catch (Exception ex) {
            dbOk = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "ok" : "degraded");
        body.put("product", AppConfig.PRODUCT_NAME);
        body.put("subtitle", AppConfig.PRODUCT_SUBTITLE);
        body.put("version", AppConfig.VERSION);
        body.put("time", Instant.now().toString());
        body.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        body.put("database", dbOk ? "connected" : "error");
        body.put("counts", Map.of(
                "ambulances", ambulances.findAll().size(),
                "hospitals", hospitals.findAll().size(),
                "emergencies", emergencies.countAll(),
                "activeEmergencies", emergencies.countActive(),
                "queued", dispatchService.queuedCount(),
                "activeDispatches", dispatches.findActive().size()));
        Map<String, Object> net = new LinkedHashMap<>();
        net.put("source", graph.source());
        net.put("realOsm", graph.isRealOsm());
        net.put("nodes", graph.graph().nodeCount());
        net.put("edges", graph.graph().edgeCount());
        body.put("network", net);
        body.put("simulation", simulation.status());
        body.put("mapData", graph.isRealOsm() ? "OpenStreetMap (real)"
                : "SYNTHETIC FALLBACK — generated demonstration grid, not real roads");
        sendJson(exchange, dbOk ? 200 : 503, body);
    }

    // ------------------------------------------------------------- ambulances

    private void ambulances(HttpExchange exchange, String method, String[] s) throws IOException {
        if (s.length == 1) {
            requireGet(exchange, method);
            List<Map<String, Object>> views = new ArrayList<>();
            for (Ambulance a : ambulances.findAll()) views.add(ambulanceView(a));
            sendJson(exchange, 200, Map.of("ambulances", views, "simulatedMovement", true));
            return;
        }
        String id = s[1];
        if (s.length == 3 && "availability".equals(s[2])) {
            requirePost(exchange, method);
            Map<String, Object> body = body(exchange);
            boolean available = bool(body, "available", true);
            dispatchService.setAmbulanceAvailability(id, available);
            Ambulance a = ambulances.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown ambulance"));
            sendJson(exchange, 200, Map.of("ambulance", ambulanceView(a)));
            return;
        }
        if (s.length == 3 && "base".equals(s[2])) {
            if (!method.equals("PUT") && !method.equals("POST")) {
                sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use PUT");
                return;
            }
            Map<String, Object> body = body(exchange);
            double lat = number(body, "lat");
            double lon = number(body, "lon");
            if (!graph.insideOperatingArea(lat, lon)) {
                throw new IllegalArgumentException("Base location is outside the operating area");
            }
            String label = text(body, "baseLocation", "Configured base");
            dispatchService.updateAmbulanceBase(id, label, lat, lon);
            sendJson(exchange, 200, Map.of("ambulance",
                    ambulanceView(ambulances.findById(id).orElseThrow())));
            return;
        }
        requireGet(exchange, method);
        Ambulance a = ambulances.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown ambulance"));
        sendJson(exchange, 200, Map.of("ambulance", ambulanceView(a)));
    }

    private Map<String, Object> ambulanceView(Ambulance a) {
        Map<String, Object> view = Json.MAPPER.convertValue(a, MAP);
        view.put("displayStatus", a.getStatus().name().replace('_', ' '));
        view.put("simulated", true);
        dispatches.findActiveByAmbulance(a.getId()).ifPresent(d -> {
            view.put("activeDispatchId", d.getId());
            view.put("activeEmergencyId", d.getEmergencyId());
            view.put("phase", d.getPhase().name());
            view.put("simProgress", simulation.progress(d));
        });
        return view;
    }

    // -------------------------------------------------------------- hospitals

    private void hospitals(HttpExchange exchange, String method, String[] s) throws IOException {
        if (s.length == 1) {
            requireGet(exchange, method);
            List<Map<String, Object>> views = new ArrayList<>();
            for (Hospital h : hospitals.findAll()) views.add(hospitalView(h));
            sendJson(exchange, 200, Map.of("hospitals", views, "bedDataSimulated", true));
            return;
        }
        String id = s[1];
        Hospital h = hospitals.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown hospital"));
        if (s.length == 3 && "beds".equals(s[2])) {
            requirePost(exchange, method);
            Map<String, Object> body = body(exchange);
            int total = integer(body, "totalBeds", h.getTotalBeds());
            int occupied = integer(body, "occupiedBeds", h.getOccupiedBeds());
            if (total < 0 || occupied < 0 || occupied > total) {
                throw new IllegalArgumentException("Occupied beds must be between 0 and total beds");
            }
            hospitals.updateBeds(id, total, occupied);
            sendJson(exchange, 200, Map.of("hospital", hospitalView(hospitals.findById(id).orElseThrow())));
            return;
        }
        if (s.length == 3 && "operational".equals(s[2])) {
            requirePost(exchange, method);
            Map<String, Object> body = body(exchange);
            hospitals.updateOperational(id, bool(body, "operational", true));
            sendJson(exchange, 200, Map.of("hospital", hospitalView(hospitals.findById(id).orElseThrow())));
            return;
        }
        if (s.length == 2 && (method.equals("PUT") || method.equals("POST"))) {
            Map<String, Object> body = body(exchange);
            if (body.containsKey("name")) h.setName(text(body, "name", h.getName()));
            if (body.containsKey("address")) h.setAddress(text(body, "address", h.getAddress()));
            if (body.containsKey("lat") && body.containsKey("lon")) {
                double lat = number(body, "lat");
                double lon = number(body, "lon");
                if (!graph.insideOperatingArea(lat, lon)) {
                    throw new IllegalArgumentException("Hospital location is outside the operating area");
                }
                h.setLat(lat);
                h.setLon(lon);
                h.setVerificationStatus("ADMIN_CONFIRMED");
                h.setNote("Location confirmed manually by administrator. Bed counts remain simulated.");
            }
            if (body.containsKey("facilities")) h.setFacilities(parseFacilities(body.get("facilities")));
            if (body.containsKey("totalBeds")) h.setTotalBeds(integer(body, "totalBeds", h.getTotalBeds()));
            if (body.containsKey("occupiedBeds")) h.setOccupiedBeds(integer(body, "occupiedBeds", h.getOccupiedBeds()));
            if (body.containsKey("operational")) h.setOperational(bool(body, "operational", h.isOperational()));
            if (h.getOccupiedBeds() > h.getTotalBeds()) {
                throw new IllegalArgumentException("Occupied beds cannot exceed total beds");
            }
            hospitals.update(h);
            sendJson(exchange, 200, Map.of("hospital", hospitalView(hospitals.findById(id).orElseThrow())));
            return;
        }
        requireGet(exchange, method);
        sendJson(exchange, 200, Map.of("hospital", hospitalView(h)));
    }

    private Map<String, Object> hospitalView(Hospital h) {
        Map<String, Object> view = Json.MAPPER.convertValue(h, MAP);
        view.put("availableBeds", h.getAvailableBeds());
        view.put("bedDataSimulated", true);
        view.put("routable", h.isOperational() && h.hasLocation() && h.getAvailableBeds() > 0);
        return view;
    }

    // ------------------------------------------------------------- emergencies

    private void emergencies(HttpExchange exchange, String method, String[] s) throws IOException {
        if (s.length == 1 && method.equals("GET")) {
            int limit = limitParam(exchange, 100, 500);
            List<Map<String, Object>> views = new ArrayList<>();
            for (Emergency e : emergencies.findAll(limit)) views.add(emergencyView(e));
            sendJson(exchange, 200, Map.of("emergencies", views));
            return;
        }
        if (s.length == 1 && method.equals("POST")) {
            Map<String, Object> body = body(exchange);
            Emergency e = new Emergency();
            e.setType(parseIncident(text(body, "type", null)));
            e.setSeverity(parseSeverity(text(body, "severity", null)));
            e.setRequiredFacility(parseFacility(text(body, "requiredFacility", null)));
            e.setLat(number(body, "lat"));
            e.setLon(number(body, "lon"));
            e.setDescription(text(body, "description", ""));
            DispatchService.IntakeResult result = dispatchService.register(e);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("emergency", emergencyView(result.emergency()));
            response.put("dispatch", result.dispatch() == null ? null : dispatchView(result.dispatch(), true, true));
            response.put("selection", selectionView(result.selection()));
            response.put("message", result.message());
            sendJson(exchange, 201, response);
            return;
        }
        if (s.length >= 2) {
            requireGet(exchange, method);
            Emergency e = emergencies.findById(s[1])
                    .orElseThrow(() -> new IllegalArgumentException("Unknown emergency: " + s[1]));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("emergency", emergencyView(e));
            response.put("events", events.forEmergency(e.getId()));
            dispatches.findActiveByEmergency(e.getId())
                    .or(() -> dispatches.findRecent(100).stream()
                            .filter(d -> d.getEmergencyId().equals(e.getId())).findFirst())
                    .ifPresent(d -> response.put("dispatch", dispatchView(d, true, true)));
            response.put("selection", selectionView(dispatchService.selectionFor(e)));
            sendJson(exchange, 200, response);
            return;
        }
        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Unsupported emergency operation");
    }

    private Map<String, Object> emergencyView(Emergency e) {
        Map<String, Object> view = Json.MAPPER.convertValue(e, MAP);
        dispatches.findActiveByEmergency(e.getId()).ifPresent(d -> {
            view.put("dispatchId", d.getId());
            view.put("phase", d.getPhase().name());
            hospitals.findById(d.getHospitalId()).ifPresent(h -> {
                view.put("assignedHospitalName", h.getName());
                view.put("assignedHospitalLat", h.getLat());
                view.put("assignedHospitalLon", h.getLon());
            });
            ambulances.findById(d.getAmbulanceId()).ifPresent(a -> {
                view.put("assignedAmbulanceLabel", a.getBaseLocation());
                view.put("ambulanceEtaSeconds", a.getEtaSeconds());
            });
        });
        try {
            view.put("ageSeconds", Duration.between(Instant.parse(e.getCreatedAt()), Instant.now()).getSeconds());
        } catch (Exception ignored) { }
        return view;
    }

    // --------------------------------------------------------------- dispatches

    private void dispatches(HttpExchange exchange, String method, String[] s) throws IOException {
        requireGet(exchange, method);
        if (s.length >= 2) {
            Dispatch d = dispatches.findById(s[1])
                    .orElseThrow(() -> new IllegalArgumentException("Unknown dispatch: " + s[1]));
            sendJson(exchange, 200, Map.of("dispatch", dispatchView(d, true, true)));
            return;
        }
        String scope = query(exchange).getOrDefault("scope", "active");
        List<Dispatch> list = switch (scope) {
            case "recent" -> dispatches.findRecent(limitParam(exchange, 50, 200));
            case "all" -> dispatches.findRecent(limitParam(exchange, 200, 500));
            default -> dispatches.findActive();
        };
        List<Map<String, Object>> views = new ArrayList<>();
        for (Dispatch d : list) views.add(dispatchView(d, true, true));
        sendJson(exchange, 200, Map.of("dispatches", views, "scope", scope));
    }

    private Map<String, Object> dispatchView(Dispatch d, boolean includeRoutes, boolean includeAlternatives) {
        Map<String, Object> view = Json.MAPPER.convertValue(d, MAP);
        if (!includeRoutes) {
            view.remove("ambulanceRoute");
            view.remove("hospitalRoute");
            view.remove("returnRoute");
        }
        if (!includeAlternatives) view.remove("alternativesJson");
        view.put("simulation", simulation.simulationInfo(d));
        view.put("progress", simulation.progress(d));

        emergencies.findById(d.getEmergencyId()).ifPresent(e -> {
            view.put("emergency", emergencyView(e));
            view.put("severity", e.getSeverity());
            view.put("incidentType", e.getType());
            view.put("requiredFacility", e.getRequiredFacility());
        });
        hospitals.findById(d.getHospitalId()).ifPresent(h -> {
            view.put("hospital", hospitalView(h));
            view.put("hospitalName", h.getName());
            view.put("hospitalLat", h.getLat());
            view.put("hospitalLon", h.getLon());
        });
        ambulances.findById(d.getAmbulanceId()).ifPresent(a -> {
            view.put("ambulance", ambulanceView(a));
            view.put("ambulanceBase", a.getBaseLocation());
            view.put("ambulanceCurrentLat", a.getCurrentLat());
            view.put("ambulanceCurrentLon", a.getCurrentLon());
            view.put("ambulanceEtaRemaining", a.getEtaSeconds());
            view.put("ambulanceStatus", a.getStatus());
        });
        if (includeAlternatives && d.getAlternativesJson() != null) {
            try {
                view.put("alternatives", Json.MAPPER.readValue(d.getAlternativesJson(), MAP));
            } catch (Exception ignored) { }
        }
        return view;
    }

    private void autoDispatch(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String emergencyId = body.get("emergencyId") == null ? null : String.valueOf(body.get("emergencyId"));
        Dispatch d;
        if (emergencyId == null) {
            d = dispatchService.processQueue();
        } else {
            d = dispatchService.retry(emergencyId);
        }
        if (d == null) {
            sendJson(exchange, 200, Map.of(
                    "dispatched", false,
                    "message", "No assignment could be made. Emergency remains queued with an explanation.",
                    "queue", dispatchService.queueSnapshot()));
            return;
        }
        sendJson(exchange, 200, Map.of("dispatched", true,
                "dispatch", dispatchView(d, true, true)));
    }

    private void manualDispatch(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String emergencyId = text(body, "emergencyId", null);
        String ambulanceId = text(body, "ambulanceId", null);
        String hospitalId = text(body, "hospitalId", null);
        String reason = text(body, "reason", null);
        if (emergencyId == null || ambulanceId == null || hospitalId == null || reason == null) {
            throw new IllegalArgumentException("emergencyId, ambulanceId, hospitalId and reason are required");
        }
        Dispatch d = dispatchService.manualDispatch(emergencyId, ambulanceId, hospitalId, reason);
        sendJson(exchange, 200, Map.of("dispatch", dispatchView(d, true, true),
                "message", "Manual override recorded with operator reason."));
    }

    private void completeTrip(HttpExchange exchange) throws IOException {
        Map<String, Object> body = body(exchange);
        String dispatchId = text(body, "dispatchId", null);
        if (dispatchId == null) throw new IllegalArgumentException("dispatchId is required");
        Dispatch d = dispatchService.completeTrip(dispatchId);
        sendJson(exchange, 200, Map.of("dispatch", dispatchView(d, true, true),
                "message", "Trip completed. Ambulance released."));
    }

    private void selection(HttpExchange exchange, String emergencyId) throws IOException {
        Emergency e = emergencies.findById(emergencyId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown emergency: " + emergencyId));
        sendJson(exchange, 200, selectionView(dispatchService.selectionFor(e)));
    }

    private Map<String, Object> selectionView(SelectionResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("success", result.success());
        view.put("selectedAmbulance", result.selectedAmbulance());
        view.put("selectedHospital", result.selectedHospital());
        view.put("ambulanceCandidates", result.ambulanceCandidates());
        view.put("hospitalCandidates", result.hospitalCandidates());
        view.put("explanation", result.explanation());
        view.put("failureReason", result.failureReason());
        return view;
    }

    // ------------------------------------------------------------------ network

    private void network(HttpExchange exchange, String method, String[] s) throws IOException {
        requireGet(exchange, method);
        if (s.length > 1 && "edges".equals(s[1])) {
            String q = query(exchange).getOrDefault("q", "");
            int limit = limitParam(exchange, 40, 200);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (RoadEdge e : graph.search(q, limit)) rows.add(edgeView(e));
            sendJson(exchange, 200, Map.of("edges", rows, "query", q));
            return;
        }
        sendJson(exchange, 200, graph.networkSummary());
    }

    private Map<String, Object> edgeView(RoadEdge e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("name", e.getName() == null ? "(unnamed)" : e.getName());
        row.put("roadType", e.getRoadType());
        row.put("distanceMeters", Math.round(e.getDistanceMeters() * 10) / 10.0);
        row.put("travelTimeSeconds", Math.round(e.getTravelTimeSeconds() * 10) / 10.0);
        row.put("closed", e.isClosed());
        row.put("trafficMultiplier", e.getTrafficMultiplier());
        row.put("fromNode", e.getFrom());
        row.put("toNode", e.getTo());
        row.put("geometry", e.getGeometry());
        return row;
    }

    private void route(HttpExchange exchange) throws IOException {
        Map<String, String> q = query(exchange);
        RouteResult result;
        if (q.containsKey("fromNode") && q.containsKey("toNode")) {
            result = graph.routeNodes(Long.parseLong(q.get("fromNode")), Long.parseLong(q.get("toNode")));
        } else {
            double fromLat = parseDouble(q.get("fromLat"), "fromLat");
            double fromLon = parseDouble(q.get("fromLon"), "fromLon");
            double toLat = parseDouble(q.get("toLat"), "toLat");
            double toLon = parseDouble(q.get("toLon"), "toLon");
            result = graph.route(fromLat, fromLon, toLat, toLon);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("found", result.found());
        body.put("distanceMeters", result.distanceMeters());
        body.put("travelTimeSeconds", result.travelTimeSeconds());
        body.put("geometry", result.geometry());
        body.put("nodeIds", result.nodeIds());
        body.put("edgeIds", result.edgeIds());
        if (!result.found()) {
            body.put("message", "No path exists on the current road graph. A closure may have disconnected it.");
        }
        sendJson(exchange, 200, body);
    }

    private void road(HttpExchange exchange, String method, String[] s) throws IOException {
        if (s.length < 2) {
            sendError(exchange, 404, "NOT_FOUND", "Unknown road operation");
            return;
        }
        String action = s[1];
        if ("closure".equals(action)) {
            requirePost(exchange, method);
            Map<String, Object> body = body(exchange);
            String edgeId = text(body, "edgeId", null);
            if (edgeId == null) throw new IllegalArgumentException("edgeId is required");
            boolean closed = bool(body, "closed", true);
            String reason = text(body, "reason", closed ? "Operator closure" : "Reopened by operator");
            graph.setClosure(edgeId, closed, reason);
            List<Map<String, Object>> recalculation = simulation.recalculateActiveRoutes();
            sendJson(exchange, 200, Map.of("ok", true, "edgeId", edgeId, "closed", closed,
                    "topic", "SIMULATED road closure", "recalculation", recalculation));
            return;
        }
        if ("traffic".equals(action)) {
            requirePost(exchange, method);
            Map<String, Object> body = body(exchange);
            String edgeId = text(body, "edgeId", null);
            if (edgeId == null) throw new IllegalArgumentException("edgeId is required");
            double multiplier = number(body, "multiplier");
            if (multiplier < 1 || multiplier > 5) {
                throw new IllegalArgumentException("Traffic multiplier must be between 1.0 and 5.0");
            }
            graph.setTraffic(edgeId, multiplier);
            List<Map<String, Object>> recalculation = simulation.recalculateActiveRoutes();
            sendJson(exchange, 200, Map.of("ok", true, "edgeId", edgeId, "multiplier", multiplier,
                    "topic", "SIMULATED traffic delay", "recalculation", recalculation));
            return;
        }
        if ("recalculate".equals(action)) {
            requirePost(exchange, method);
            sendJson(exchange, 200, Map.of("ok", true,
                    "recalculation", simulation.recalculateActiveRoutes(),
                    "topic", "Recomputed with the Java Dijkstra engine"));
            return;
        }
        sendError(exchange, 404, "NOT_FOUND", "Unknown road operation");
    }

    // ------------------------------------------------------------------ history

    private void history(HttpExchange exchange) throws IOException {
        int limit = limitParam(exchange, 50, 200);
        List<Emergency> list = emergencies.findAll(limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Emergency e : list) {
            Map<String, Object> row = emergencyView(e);
            dispatches.findRecent(200).stream()
                    .filter(d -> d.getEmergencyId().equals(e.getId()))
                    .findFirst().ifPresent(d -> {
                        row.put("dispatchId", d.getId());
                        row.put("ambulanceId", d.getAmbulanceId());
                        row.put("hospitalId", d.getHospitalId());
                        row.put("ambulanceEtaSeconds", d.getAmbulanceEtaSeconds());
                        row.put("hospitalEtaSeconds", d.getHospitalEtaSeconds());
                        row.put("ambulanceDistanceMeters", d.getAmbulanceDistanceMeters());
                        row.put("dispatchStatus", d.getStatus());
                        hospitals.findById(d.getHospitalId()).ifPresent(h -> row.put("hospitalName", h.getName()));
                    });
            rows.add(row);
        }
        sendJson(exchange, 200, Map.of("history", rows));
    }

    // ------------------------------------------------------------------ settings

    private void settings(HttpExchange exchange, String method) throws IOException {
        if (method.equals("GET")) {
            sendJson(exchange, 200, Map.of(
                    "settings", settings.getAll(),
                    "network", graph.networkSummary(),
                    "ambulances", ambulances.findAll(),
                    "hospitals", hospitals.findAll(),
                    "operatingArea", graph.operatingArea()));
            return;
        }
        requirePost(exchange, method);
        Map<String, Object> body = body(exchange);
        Map<String, String> updates = new LinkedHashMap<>();
        for (String key : List.of("operating_area_min_lat", "operating_area_min_lon",
                "operating_area_max_lat", "operating_area_max_lon", "osm_bbox")) {
            if (body.containsKey(key)) updates.put(key, String.valueOf(body.get(key)));
        }
        if (updates.containsKey("operating_area_min_lat") || updates.containsKey("operating_area_max_lat")
                || updates.containsKey("operating_area_min_lon") || updates.containsKey("operating_area_max_lon")) {
            Map<String, String> merged = settings.getAll();
            merged.putAll(updates);
            try {
                double minLat = Double.parseDouble(merged.get("operating_area_min_lat"));
                double maxLat = Double.parseDouble(merged.get("operating_area_max_lat"));
                double minLon = Double.parseDouble(merged.get("operating_area_min_lon"));
                double maxLon = Double.parseDouble(merged.get("operating_area_max_lon"));
                if (minLat >= maxLat || minLon >= maxLon) {
                    throw new IllegalArgumentException("Operating area min must be less than max");
                }
                if (!graph.insideOperatingArea(11.3470648, 77.7199695)) {
                    throw new IllegalArgumentException("Operating area must still contain central Erode");
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Operating area coordinates must be numbers");
            }
        }
        if (body.containsKey("simulation_speed")) {
            double speed = number(body, "simulation_speed");
            if (speed < 1 || speed > 120) throw new IllegalArgumentException("Simulation speed must be 1-120");
            updates.put("simulation_speed", String.valueOf(speed));
        }
        if (body.containsKey("simulation_paused")) {
            updates.put("simulation_paused", String.valueOf(bool(body, "simulation_paused", false)));
        }
        if (body.containsKey("return_policy")) {
            String policy = text(body, "return_policy", "ALWAYS_RETURN");
            if (!policy.equals("ALWAYS_RETURN") && !policy.equals("RELEASE_AFTER_HANDOVER")) {
                throw new IllegalArgumentException("Unknown return policy");
            }
            updates.put("return_policy", policy);
        }
        if (body.containsKey("demo_admission")) {
            updates.put("demo_admission", String.valueOf(bool(body, "demo_admission", true)));
        }
        settings.putAll(updates);
        sendJson(exchange, 200, Map.of("ok", true, "settings", settings.getAll(),
                "network", graph.networkSummary(),
                "message", "Settings saved. Changes to operating area do not re-import roads automatically."));
    }

    // -------------------------------------------------------------------- simulation

    private void simulation(HttpExchange exchange, String method) throws IOException {
        if (method.equals("GET")) {
            sendJson(exchange, 200, simulation.status());
            return;
        }
        requirePost(exchange, method);
        Map<String, Object> body = body(exchange);
        if (body.containsKey("paused")) simulation.setPaused(bool(body, "paused", false));
        if (body.containsKey("speed")) simulation.setSpeed(number(body, "speed"));
        sendJson(exchange, 200, simulation.status());
    }

    // ------------------------------------------------------------------------ demo

    private void demo(HttpExchange exchange, String method, String action) throws IOException {
        requirePost(exchange, method);
        if ("reset".equals(action)) {
            Map<String, Object> body = body(exchange);
            boolean seedHistory = bool(body, "seedHistory", false);
            sendJson(exchange, 200, demoReset.reset(seedHistory));
            return;
        }
        if ("history".equals(action)) {
            if (emergencies.countAll() > 0) {
                throw new IllegalStateException("History seeding requires an empty emergency dataset. Reset first.");
            }
            demoReset.seedHistory();
            sendJson(exchange, 201, Map.of("seeded", true,
                    "message", "DEMO HISTORY inserted. All rows are labelled simulated."));
            return;
        }
        sendError(exchange, 404, "NOT_FOUND", "Unknown demo operation");
    }

    // ------------------------------------------------------------------------- OSM

    private void osm(HttpExchange exchange, String method) throws IOException {
        requirePost(exchange, method);
        Map<String, Object> body = body(exchange);
        String mode = text(body, "mode", "live");
        OsmImporter.ImportResult result = switch (mode) {
            case "cache" -> graph.importCached();
            case "fallback" -> graph.useSyntheticFallback();
            default -> graph.importLive();
        };
        List<Map<String, Object>> recalculated = simulation.recalculateActiveRoutes();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("network", graph.networkSummary());
        response.put("recalculation", recalculated);
        response.put("success", !"NOT_LOADED".equals(result.source()));
        sendJson(exchange, response.get("success").equals(true) ? 200 : 502, response);
    }

    // -------------------------------------------------------------------- helpers

    private void requireGet(HttpExchange exchange, String method) throws IOException {
        if (!"GET".equals(method)) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET for this endpoint");
            throw new HandledResponse();
        }
    }

    private void requirePost(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use POST for this endpoint");
            throw new HandledResponse();
        }
    }

    private static class HandledResponse extends RuntimeException { }

    private Map<String, Object> body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(1_048_576);
            if (bytes.length == 0) return Map.of();
            return Json.MAPPER.readValue(bytes, MAP);
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) result.put(decode(pair), "");
            else result.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int limitParam(HttpExchange exchange, int fallback, int max) {
        try {
            String raw = query(exchange).get("limit");
            if (raw == null) return fallback;
            return Math.max(1, Math.min(max, Integer.parseInt(raw)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        if (value == null) return fallback;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }

    private double number(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private int integer(Map<String, Object> body, String key, int fallback) {
        Object value = body.get(key);
        if (value == null) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
    }

    private boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double parseDouble(String value, String key) {
        if (value == null) throw new IllegalArgumentException(key + " is required");
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private IncidentType parseIncident(String value) {
        if (value == null) throw new IllegalArgumentException("Incident type is required");
        try { return IncidentType.from(value); }
        catch (RuntimeException ex) { throw new IllegalArgumentException(ex.getMessage()); }
    }

    private Severity parseSeverity(String value) {
        if (value == null) throw new IllegalArgumentException("Severity is required");
        try { return Severity.valueOf(value.trim().toUpperCase()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Severity must be CRITICAL, SERIOUS or MINOR"); }
    }

    private Facility parseFacility(String value) {
        if (value == null) throw new IllegalArgumentException("Required hospital facility is required");
        try { return Facility.valueOf(value.trim().toUpperCase()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Facility must be GENERAL, CARDIAC or TRAUMA"); }
    }

    private List<Facility> parseFacilities(Object value) {
        if (value == null) throw new IllegalArgumentException("facilities is required");
        List<Facility> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) result.add(parseFacility(String.valueOf(item)));
        } else {
            for (String part : String.valueOf(value).split(",")) {
                if (!part.isBlank()) result.add(parseFacility(part));
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("At least one facility is required");
        return result;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", true, "code", code,
                "message", message == null ? "Request failed" : message));
    }
}
