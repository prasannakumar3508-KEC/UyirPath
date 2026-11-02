# ARUNAI — Erode Emergency Dispatch Grid

ARUNAI is a self-contained emergency ambulance dispatch and route-optimisation
system for Erode, Tamil Nadu. It uses **real OpenStreetMap road geometry**,
a **custom Java 21 Dijkstra engine**, SQLite persistence, and a vanilla
JavaScript/Leaflet operations interface.

The application is deliberately framework-free on the server and client so the
routing, priority queue, dispatch transaction and lifecycle logic can be read
and defended line by line.

> **Demonstration scope.** Ambulance positions and movement are simulated.
> Hospital bed counts and facilities are demonstration data. Traffic is a
> simulation multiplier. Nothing in this project is connected to a live 108/112
> emergency service or a real hospital capacity feed.

---

## 1. What is implemented

| Area | Implementation |
|---|---|
| Road network | Real OSM extract for Erode: **176,054 nodes / 186,729 directed edges**, imported through Overpass and cached locally |
| Routing | Custom Dijkstra in `graph/Dijkstra.java`; travel-time weights from OSM highway class; skips closed edges; returns full road geometry |
| Five ambulances | AMB-01 Central Bus Stand, AMB-02 Erode Junction, AMB-03 Perundurai, AMB-04 Bhavani, AMB-05 Chithode |
| Five hospital candidates | Four with OSM-verified locations; HOSP-05 is unverified and excluded from routing until an administrator sets its location |
| Priority dispatch | CRITICAL → SERIOUS → MINOR, FIFO inside each severity |
| Atomic assignment | SQLite transaction + conditional ambulance update + partial unique indexes |
| Matching | Fastest available ambulance to scene, then fastest eligible hospital with the required facility and at least one demonstration bed |
| Lifecycle | `REPORTED → QUEUED → ASSIGNED → AMBULANCE_EN_ROUTE → ARRIVED_AT_SCENE → TRANSPORTING → ARRIVED_AT_HOSPITAL → COMPLETED` |
| Simulation | Server-driven movement interpolated along the Dijkstra geometry; pause/resume; 2×–60× speed; configurable return policy |
| Disruptions | Simulated road closures and traffic multipliers; all active routes are recalculated immediately |
| Analytics | Completed calls, response times, severity/facility breakdowns, utilisation, closure count |
| API | REST/JSON over the JDK HTTP server, documented in `docs/API.md` |
| UI | Eight screens: Live Operations, Emergency Intake, Dispatch Intelligence, Ambulances, Hospitals, Road Network, History & Analytics, Settings |
| Tests | 15 JUnit 5 tests, all passing; browser end-to-end acceptance test with zero console errors |

---

## 2. Requirements

- **Java 21** (tested with Eclipse Temurin 21.0.12)
- Maven 3.9+ **or** the included Maven wrapper
- A modern browser (tested in Microsoft Edge)
- Internet access only for the first OSM import/download and for map tiles

The prepared workspace already contains the imported OSM cache in `data/osm/`
and the SQLite database in `data/`. Those two artefacts are ignored by Git
because of their size; if you copy only the source tree, run
`java -jar target/arunai-dispatch.jar --import-osm` once (or restore both
files) before starting the server.

---

## 3. Quick start

```powershell
# Windows PowerShell (from the project root)
.\mvnw.cmd -DskipTests package
java -jar target\arunai-dispatch.jar
```

Open <http://localhost:8080>.

On macOS/Linux:

```bash
./mvnw -DskipTests package
java -jar target/arunai-dispatch.jar
```

Useful commands:

```bash
# Run the JUnit suite
./mvnw test

# Re-import real Erode roads from Overpass and exit
java -jar target/arunai-dispatch.jar --import-osm

# Reset operational data, restore five ambulances/hospitals, seed labelled demo history
java -jar target/arunai-dispatch.jar --reset-demo --seed-history

# Choose another port
java -jar target/arunai-dispatch.jar --port 9090
```

Environment variable `ARUNAI_DB` overrides the database path. See
`config/example.env`.

---

## 4. Operating the UI

1. **Live Operations** — live map, KPI strip, active dispatch card, queue and
   recent activity. `FIT ROUTE` frames the selected route; the simulation clock
   and speed live in the top bar.
2. **Emergency Intake** — click the map or use GPS, choose incident type,
   severity and required facility, then submit. The call is queued and
   auto-dispatched, or queued with a visible reason if no resource is eligible.
3. **Dispatch Intelligence** — every ambulance and hospital candidate with ETA,
   distance, eligibility and reason, plus a Dijkstra decision trace and the
   event timeline.
4. **Ambulances** — five cards, simulated phase/progress, pause/resume, speed,
   availability (blocked while a trip is active), base editing.
5. **Hospitals** — demonstration beds, occupancy, facilities, operational state,
   and manual location confirmation (required for HOSP-05).
6. **Road Network** — OSM statistics, road search, closure and traffic controls,
   route recalculation results, and the active-closure list with `REOPEN`.
7. **History & Analytics** — durable call history, analytics KPIs and breakdowns.
8. **Settings** — operating area, demo reset, OSM re-import, ambulance bases,
   hospital configuration, simulation defaults, data provenance.

Every simulated element is labelled: movement, bed counts, closures and traffic
are not real-world feeds.

---

## 5. Project structure

```text
MPMAIN/
├─ src/main/java/com/arunai/dispatch/
│  ├─ App.java                  entry point, wiring, CLI
│  ├─ config/AppConfig.java     defaults and speed assumptions
│  ├─ graph/                    RoadGraph, Dijkstra, GeoMath, RouteResult
│  ├─ model/                    domain records and enums
│  ├─ repository/               SQLite access, schema seeding, OSM persistence
│  ├─ service/                  queue, planner, dispatch, simulation, analytics, reset
│  ├─ controller/ApiController  REST endpoints
│  └─ server/                   JDK HTTP server and static files
├─ src/main/resources/database/schema.sql
├─ src/test/java/               15 JUnit 5 tests
├─ frontend/                    HTML/CSS/vanilla JS + vendored Leaflet 1.9.4
├─ data/arunai.db              prepared SQLite database
├─ data/osm/                   cached OSM response
├─ docs/                       architecture, API, database, testing, review pack
└─ config/                     configuration example
```

---

## 6. Data provenance

- Road geometry and names: © OpenStreetMap contributors, ODbL 1.0.
- Speeds are **class-based estimates** (e.g. primary 50 km/h), not live traffic.
- Ambulance base coordinates come from OSM features. This does **not** mean an
  ambulance is actually stationed there.
- Hospital coordinates are OSM-verified where noted. HOSP-02 and HOSP-04 are
  location-verified name aliases; their official names should be confirmed.
- HOSP-05 has no verified coordinates and is never used for routing until an
  administrator sets one.
- Bed counts, occupancy, facilities, closures, traffic and movement are
  **demonstration data**.

See `docs/OSM_VERIFICATION.md` for the exact OSM references.

---

## 7. Verification status

| Check | Result |
|---|---|
| Maven package | passes |
| `mvn test` | **15 tests, 0 failures, 0 errors** |
| Health/API smoke | passes |
| Browser E2E (Edge/Playwright) | all eight screens loaded; intake → dispatch → route → intelligence → simulation → beds → closure → reopen → analytics → settings |
| Console errors | **0** |
| Failed network requests | **0** (tile requests excluded from failure counting only because they are third-party) |
| Closure reopen | active closures return to **0**; `closedEdges` returns to **0** |
| Default simulation speed after reset | **8×** |

Actual command output and evidence are recorded in `docs/TESTING.md`.

---

## 8. Known limitations

1. The simulation is not real GPS and does not model traffic signals, turn
   restrictions, one-way streets or lane-level behaviour.
2. Speeds are per-road-class estimates.
3. The browser asks for the OSM tile server directly; offline use shows a blank
   base map while the vector routes still draw.
4. Hospital names and facilities are candidate data and must be confirmed by an
   administrator.
5. The HTTP server is suitable for a local/demo deployment, not a hardened
   public production service.
6. Road closures are simulated and do not represent real municipal notices.

---

## 9. Documentation map

| Document | Contents |
|---|---|
| `docs/ARCHITECTURE.md` | components, UML, dispatch sequence, design decisions |
| `docs/API.md` | every endpoint with request/response examples |
| `docs/DATABASE.md` | schema, ER diagram, invariants |
| `docs/TESTING.md` | test matrix, actual results, manual acceptance script |
| `docs/OSM_VERIFICATION.md` | real OSM references and data limits |
| `docs/REVIEW_PACK.md` | demo script, viva questions, sustainability, screenshots checklist |
| `docs/screenshots/` | captured acceptance screenshots |
