# Testing and Acceptance Evidence

All results below are from actual runs in this workspace. Nothing is projected
or invented.

Environment: Windows, Java 21.0.12 (Temurin), Maven 3.9.9, Microsoft Edge via
Playwright, server on `http://localhost:8080`.

---

## 1. Automated JUnit suite

Command:

```powershell
.\mvnw.cmd test
```

Actual output summary:

```text
[INFO] Running com.uyirpath.dispatch.graph.DijkstraTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.uyirpath.dispatch.model.EmergencyStatusTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.uyirpath.dispatch.repository.PersistenceInvariantTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.uyirpath.dispatch.service.DispatchPlannerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.uyirpath.dispatch.service.DispatchQueueTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Test matrix

| # | Requirement under test | Test | Result |
|---|---|---|---|
| 1 | Dijkstra uses travel time, not shortest distance | `DijkstraTest.choosesFastestPathByTravelTime` | PASS |
| 2 | Closed edge is skipped and route reroutes | `DijkstraTest.reroutesAroundClosedEdge` | PASS |
| 3 | Traffic multiplier changes the weight | `DijkstraTest.appliesTrafficMultiplier` | PASS |
| 4 | Full ordered geometry and totals reconstructed | `DijkstraTest.reconstructsOrderedGeometry` | PASS |
| 5 | Unreachable/unknown target returns not-found | `DijkstraTest.reportsUnreachableTarget` | PASS |
| 6 | CRITICAL → SERIOUS → MINOR ordering | `DispatchQueueTest.ordersBySeverity` | PASS |
| 7 | FIFO within equal severity | `DispatchQueueTest.preservesFifoWithinSeverity` | PASS |
| 8 | Duplicate queue entry deduplicated | `DispatchQueueTest.deduplicatesById` | PASS |
| 9 | Fastest available ambulance selected; busy excluded | `DispatchPlannerTest.selectsFastestAvailableAmbulance` | PASS |
| 10 | Unverified/incompatible/full/closed hospitals excluded | `DispatchPlannerTest.excludesIneligibleHospitals` | PASS |
| 11 | No eligible hospital is reported, not papered over | `DispatchPlannerTest.reportsMissingHospital` | PASS |
| 12 | Emergency lifecycle transitions validated | `EmergencyStatusTest.validatesLifecycleTransitions` | PASS |
| 13 | Severity ranks enforced | `EmergencyStatusTest.mapsSeverityRanks` | PASS |
| 14 | Closure persists, reroutes and reopening clears it | `PersistenceInvariantTest.closurePersistsAndReopens` | PASS |
| 15 | Database blocks a second active dispatch for one ambulance | `PersistenceInvariantTest.preventsDuplicateActiveAmbulanceAssignment` | PASS |

The tests use deterministic small graphs and a temporary SQLite database, so
they do not depend on network access or the demo database.

### Regression fixed during testing

The first browser run exposed a real bug: reopening a road deactivated the
`road_edges.closed` flag but left the original `road_closures` row with
`active=1`, so the UI still listed the closure. The repository now deactivates
previous active rows for that edge before inserting the new audit row. Test 14
is the regression test; the browser test confirms the API returns
`closures: 0` and `closedEdges: 0` after reopen.

A second UI race was also fixed: the 20-second background refresh could overwrite
the network state while a closure mutation was in flight. Network mutations now
set a `mutating` guard that pauses background polling until the refresh completes,
so the closure list cannot briefly revert to a stale entry.

---

## 2. Build verification

```powershell
.\mvnw.cmd -DskipTests package
```

Observed: `BUILD SUCCESS`; shaded executable produced at
`target/uyirpath-dispatch.jar` (~16.7 MB).

---

## 3. Real OSM import verification

The application reported:

```text
Road network  : OSM_OVERPASS (176054 nodes / 186729 edges)
```

`GET /api/network` returned `realOsm: true`, 176,054 nodes, 186,729 edges,
15,747 named edges, 7,119.6 km total segment length and
`© OpenStreetMap contributors, ODbL 1.0`.

A direct routing smoke test (`AMB-01 → Sudha Hospitals`) returned a found route
with 1,842 m, 131 s and 48 geometry points.

---

## 4. Browser end-to-end acceptance

Tool: Playwright driving Microsoft Edge, viewport 1440×900. Script:
`smoke.js` (kept outside the repository under the test runner's temp folder;
the captured screenshots are in `docs/screenshots/`).

### Actual console output (abridged)

```text
TITLE UyirPath · Erode Emergency Dispatch Grid
KPI_READY 5
NETWORK_CHIP OSM · OVERPASS
OPS_MARKERS 9
FAVICON favicon.svg
INTAKE_POINT 11.37160°N / 77.64519°E
INTAKE_RESULT Emergency EMG-0004 dispatched
INTAKE_BODY ... AMBULANCE AMB-05 HOSPITAL Sudha Hospitals
             SCENE ETA 5m 48s HOSPITAL ETA 16m 48s
KPI_ACTIVE 1
ACTIVE_CARD DSP-0004 · EMG-0004 CRITICAL ...
QUEUE_COUNT 0
INTEL_CANDIDATES 10
INTEL_TIMELINE 4
AMB_CARDS 5
TOP_SIM_PAUSED SIMULATION PAUSED
TOP_SIM_SPEED SIM 15x
BEDS_BEFORE_AFTER 86 -> 87
RESTORED_SPEED SIM 8x
NETWORK_KPIS ROAD NODES 1,76,054 ... DIRECTED EDGES 1,86,729 ...
ROAD_ROWS 60
CLOSURES 1
RECALC DSP-0004 · TO_SCENE · OK · 3m 17s · Route recalculated
CLOSURES_AFTER_REOPEN 0
API_ACTIVE_CLOSURES 0 / CLOSED_EDGES 0
ANALYTICS_KPIS TOTAL EMERGENCIES 4 ... COMPLETED 3 1 active ...
HISTORY_ROWS 4
SETTINGS_AREA 11.1800
SETTINGS_CONFIG_ITEMS 5/5
CONSOLE_ERRORS []
FAILED_REQUESTS []
```

`OPS_MARKERS 9` is correct: five ambulance markers plus four hospitals with
verified coordinates. HOSP-05 has no coordinates and is intentionally not drawn.

### Screens checked

| Screen | Checks | Result |
|---|---|---|
| Live Operations | 5 ready units, OSM provenance, map, KPI, route fit | PASS |
| Emergency Intake | map click, severity/facility form, submit | PASS |
| Intake result | auto-assigned AMB-05 + Sudha Hospitals, ETAs | PASS |
| Dispatch Intelligence | 10 candidate rows, 4 timeline events, decision trace | PASS |
| Ambulances | 5 cards, pause/resume, speed 15×, later restored to 8× | PASS |
| Hospitals | bed increment 86 → 87 | PASS |
| Road Network | 60 search results, closure, recalculation | PASS |
| Closure reopen | UI list 0, API closures 0, closed edges 0 | PASS |
| History & Analytics | KPIs and 4 history rows | PASS |
| Settings | operating area, 5/5 ambulance and hospital config items | PASS |

### Console and network health

```text
CONSOLE_ERRORS []
FAILED_REQUESTS []
```

The favicon 404 from the earlier run is gone (`favicon.svg` is now linked).

---

## 5. Manual acceptance script (repeatable)

1. Run `.\mvnw.cmd -DskipTests package` and start the jar.
2. Open `http://localhost:8080`. Confirm 5 ready ambulances and
   `OSM · OVERPASS`.
3. Open **Emergency Intake**, click a point near central Erode, choose
   `ROAD_TRAFFIC_ACCIDENT` / `CRITICAL` / `TRAUMA`, submit.
4. Confirm an emergency id and dispatch id, an ambulance, a hospital and ETAs.
5. On **Live Operations** press `FIT ROUTE`; confirm real road geometry.
6. Open **Dispatch Intelligence**; confirm every candidate has a reason and the
   timeline shows REPORTED, QUEUED, ASSIGNED, AMBULANCE_EN_ROUTE.
7. Pause and resume the simulation; change speed; confirm the top bar updates.
8. On **Hospitals**, increment occupied beds; confirm the card updates.
9. On **Road Network**, search `Perundurai Road`, close a segment; confirm the
   closure appears and the active dispatch is recalculated with a time/warning.
10. Press `REOPEN`; confirm the list is empty and `SIMULATED CLOSURES` returns
    to `0 0 m closed`.
11. Open **History & Analytics**; confirm durable rows and KPIs.
12. Open **Settings**; confirm 5 ambulances, 5 hospitals, operating area and
    map-data provenance.

---

## 6. What is *not* tested

- Real ambulance GPS or dispatch centre integration (not part of scope).
- Real hospital bed feeds (demonstration data only).
- Production load, TLS, authentication or multi-tenant security.
- Offline raster tile rendering (routes still render; base tiles require the
  OSM tile server).
