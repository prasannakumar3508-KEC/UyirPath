package com.uyirpath.dispatch;

import com.uyirpath.dispatch.config.AppConfig;
import com.uyirpath.dispatch.controller.ApiController;
import com.uyirpath.dispatch.osm.OsmImporter;
import com.uyirpath.dispatch.repository.AmbulanceRepository;
import com.uyirpath.dispatch.repository.Database;
import com.uyirpath.dispatch.repository.DispatchRepository;
import com.uyirpath.dispatch.repository.EmergencyRepository;
import com.uyirpath.dispatch.repository.EventRepository;
import com.uyirpath.dispatch.repository.HospitalRepository;
import com.uyirpath.dispatch.repository.RoadRepository;
import com.uyirpath.dispatch.repository.SeedData;
import com.uyirpath.dispatch.repository.SettingsRepository;
import com.uyirpath.dispatch.server.ApiServer;
import com.uyirpath.dispatch.service.AnalyticsService;
import com.uyirpath.dispatch.service.DemoResetService;
import com.uyirpath.dispatch.service.DispatchService;
import com.uyirpath.dispatch.service.GraphService;
import com.uyirpath.dispatch.service.SimulationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Entry point and manual dependency wiring (no framework magic, easy for
 * second-year students to follow).
 *
 * <pre>
 *   java -jar target/uyirpath-dispatch.jar                 # start server
 *   java -jar target/uyirpath-dispatch.jar --import-osm    # download OSM and exit
 *   java -jar target/uyirpath-dispatch.jar --reset-demo    # reset operational data
 * </pre>
 */
public final class App {

    public static void main(String[] args) {
        List<String> arguments = Arrays.asList(args);
        if (arguments.contains("--help") || arguments.contains("-h")) {
            printHelp();
            return;
        }
        int port = parsePort(arguments);
        boolean importOsm = arguments.contains("--import-osm");
        boolean resetDemo = arguments.contains("--reset-demo");
        boolean seedHistory = arguments.contains("--seed-history");

        Path dbPath = Path.of(System.getenv().getOrDefault("UYIRPATH_DB", AppConfig.DB_PATH.toString()));
        Database db = new Database(dbPath);
        db.initialise();
        SettingsRepository settings = new SettingsRepository(db);
        settings.ensureDefaults();
        SeedData seed = new SeedData(db);
        seed.seedIfEmpty();

        RoadRepository roads = new RoadRepository(db);
        OsmImporter importer = new OsmImporter(roads, settings, AppConfig.OSM_CACHE);
        GraphService graph = new GraphService(roads, settings, importer);

        if (importOsm) {
            System.out.println("[uyirpath] Importing OpenStreetMap road network for bbox "
                    + settings.get("osm_bbox", AppConfig.OSM_BBOX) + " ...");
            OsmImporter.ImportResult result = graph.importLive();
            System.out.println("[uyirpath] " + result.source() + ": " + result.message());
            if ("NOT_LOADED".equals(result.source())) {
                System.out.println("[uyirpath] Import failed. Existing local road data (if any) was preserved.");
                System.exit(2);
            }
            return;
        }

        AmbulanceRepository ambulances = new AmbulanceRepository(db);
        HospitalRepository hospitals = new HospitalRepository(db);
        EmergencyRepository emergencies = new EmergencyRepository(db);
        DispatchRepository dispatches = new DispatchRepository(db);
        EventRepository events = new EventRepository(db);

        graph.bootstrap();

        DispatchService dispatchService = new DispatchService(ambulances, hospitals, emergencies,
                dispatches, events, graph);
        SimulationService simulation = new SimulationService(dispatches, ambulances, emergencies,
                hospitals, events, graph, settings);
        dispatchService.setSimulation(simulation);
        simulation.setOnTripCompleted(dispatchService::processQueue);

        AnalyticsService analytics = new AnalyticsService(db, ambulances, hospitals, events);
        DemoResetService demoReset = new DemoResetService(db, seed, graph, dispatchService, settings, simulation);

        if (resetDemo) {
            Map<String, Object> result = demoReset.reset(seedHistory);
            System.out.println("[uyirpath] Demo reset: " + result.get("message"));
            System.out.println("[uyirpath] History seeded: " + result.get("historySeeded"));
            return;
        }

        simulation.start();

        Path frontend = AppConfig.FRONTEND_DIR.toAbsolutePath();
        if (!Files.isDirectory(frontend)) {
            System.err.println("[uyirpath] WARNING: frontend directory not found at " + frontend);
        }
        ApiController api = new ApiController(db, ambulances, hospitals, emergencies, dispatches,
                events, settings, graph, dispatchService, simulation, analytics, demoReset);

        try {
            ApiServer server = new ApiServer(port, api, frontend);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[uyirpath] Shutting down ...");
                simulation.stop();
                server.stop();
            }));
            printBanner(port, dbPath, frontend, graph, settings, emergencies.countAll());
            Thread.currentThread().join();
        } catch (Exception ex) {
            System.err.println("[uyirpath] Server failed: " + ex.getMessage());
            simulation.stop();
            System.exit(1);
        }
    }

    private static void printBanner(int port, Path dbPath, Path frontend, GraphService graph,
                                    SettingsRepository settings, int emergencies) {
        System.out.println();
        System.out.println("  " + AppConfig.PRODUCT_NAME + " / " + AppConfig.PRODUCT_SUBTITLE);
        System.out.println("  ------------------------------------------------------------------");
        System.out.println("  Local URL     : http://localhost:" + port);
        System.out.println("  Database      : " + dbPath.toAbsolutePath());
        System.out.println("  Frontend      : " + frontend);
        System.out.println("  Road network  : " + graph.source() + " (" + graph.graph().nodeCount()
                + " nodes / " + graph.graph().edgeCount() + " edges)");
        if (!graph.isRealOsm()) {
            System.out.println("  !! " + graph.notice(graph.source()));
        }
        System.out.println("  Simulation    : " + settings.get("simulation_speed", "20")
                + "x, paused=" + settings.get("simulation_paused", "false"));
        System.out.println("  Emergencies   : " + emergencies + " stored");
        System.out.println("  Map data      : © OpenStreetMap contributors (ODbL)");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    private static int parsePort(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--port=")) {
                try { return Integer.parseInt(arg.substring("--port=".length())); }
                catch (NumberFormatException ex) { System.err.println("Invalid --port value"); System.exit(2); }
            }
            if (arg.equals("--port") && i + 1 < args.size()) {
                try { return Integer.parseInt(args.get(i + 1)); }
                catch (NumberFormatException ex) { System.err.println("Invalid --port value"); System.exit(2); }
            }
        }
        return AppConfig.DEFAULT_PORT;
    }

    private static void printHelp() {
        System.out.println("""
                UyirPath Emergency Dispatch Grid
                Usage: java -jar target/uyirpath-dispatch.jar [options]

                  --port <n>        HTTP port (default 8080)
                  --import-osm      Download real Erode OSM roads via Overpass, save locally, exit
                  --reset-demo      Delete operational data and restore the five ambulances/hospitals
                  --seed-history    With --reset-demo: insert clearly labelled demonstration history
                  --help            Show this help

                Environment:
                  UYIRPATH_DB         Override database path (default data/uyirpath.db)
                """);
    }

    private App() {}
}
