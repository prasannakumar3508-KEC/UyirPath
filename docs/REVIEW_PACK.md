# Review Pack

Use this document for a project review, viva, or demonstration. It contains a
short demo script, likely examiner questions with answers, sustainability notes
and a screenshot checklist.

---

## 1. Product identity

**ARUNAI — Erode Emergency Dispatch Grid**

A dark emergency-operations interface: navy background, signal-orange primary
actions, teal for live/verified data, red for critical cases. The visual language
is deliberately an operations board rather than a generic admin dashboard.

---

## 2. Five-minute demo script

1. **Open the app** — point out `OSM · OVERPASS`, `176,054` nodes and
   `186,729` edges in the Road Network KPI. State clearly that roads are real OSM
   and movement/beds/traffic are simulated.
2. **Register a call** — open Emergency Intake, click a location on the map, pick
   `ROAD_TRAFFIC_ACCIDENT`, `CRITICAL`, `TRAUMA`, submit.
3. **Show the assignment** — the result card names the ambulance and hospital and
   gives scene/hospital ETAs.
4. **Open Dispatch Intelligence** — show the ten candidate rows (five ambulances,
   five hospitals), why each was accepted/excluded, the decision trace, and the
   event timeline.
5. **Show the route** — return to Live Operations, press `FIT ROUTE`; the line
   follows real OSM road geometry, not a straight line.
6. **Pause/resume and change speed** — point out the top-bar speed and the
   `SIMULATED` labels.
7. **Disrupt the network** — Road Network → search `Perundurai Road` → close a
   segment. Show the recalculation result and the closure marker.
8. **Reopen** — press `REOPEN`; the active-closure count returns to zero.
9. **Analytics** — History & Analytics shows durable rows and response metrics.
10. **Settings** — show the five ambulances, five hospitals, operating area and
    map-data provenance. Explain that HOSP-05 is excluded until an administrator
    sets a verified location.

---

## 3. Viva questions and model answers

### Q1. Why write Dijkstra yourself instead of using OSRM/GraphHopper?
The brief requires route decisions to come from our own Java implementation. It
also makes the algorithm inspectable: weight definition, closed-edge handling,
predecessor reconstruction and geometry stitching are all visible in
`graph/Dijkstra.java`. An external engine would hide the decision logic.

### Q2. What is the edge weight?
Estimated travel time in seconds:

```text
seconds = distance_metres / (class_speed_kph / 3.6)
effective_seconds = seconds × traffic_multiplier
```

Closed edges are skipped entirely. Travel time is more appropriate than raw
distance for an ambulance.

### Q3. How do you avoid assigning one ambulance twice?
Three layers:

1. the priority queue processes one assignment at a time under a synchronized
   service method;
2. `createAssignment` runs in one SQLite transaction and updates the ambulance
   only `WHERE status='AVAILABLE'`, requiring exactly one affected row;
3. partial unique indexes `ux_active_dispatch_ambulance` and
   `ux_active_dispatch_emergency` reject duplicates at the database level.

Test 15 proves the second assignment is rejected.

### Q4. How is priority handled?
`DispatchQueue` orders by `severity.rank()` then `seq`. CRITICAL (1) before
SERIOUS (2) before MINOR (3); equal severity is FIFO by sequence. The database
remains the durable copy, and the queue is rebuilt on startup.

### Q5. How is the hospital chosen?
Eligibility first: operational, has coordinates, offers the required facility,
has at least one available demonstration bed, and has a road route. Among
eligible hospitals, the lowest Dijkstra ETA wins. If none is eligible, the
emergency stays queued with an explanation rather than forcing a bad match.

### Q6. What happens when a road closes?
`GraphService.setClosure` updates `road_edges.closed`, updates the in-memory
edge, and calls `SimulationService.recalculateActiveRoutes()`, which reruns
Dijkstra for every active dispatch and stores a warning if a route becomes
longer or impossible. Reopening deactivates the prior closure row.

### Q7. Why is HOSP-05 not on the map?
No exact OSM feature could be verified. Rather than invent coordinates, the
record has `lat/lon = NULL`, is marked `UNVERIFIED`, and is excluded from
routing. An administrator can confirm a location in Settings, which changes the
status to `ADMIN_CONFIRMED`.

### Q8. What data is real and what is simulated?
Real: OSM road geometry/names/classes, ambulance base coordinates (as OSM
features), and hospital coordinates for HOSP-01–04. Simulated: ambulance
movement/GPS, bed counts, occupancy, facilities, traffic, closures, emergency
calls and demo history. The UI and API label every simulated element.

### Q9. Why does the map look dark if it uses OSM tiles?
The `OPS` style applies a CSS `invert(1) hue-rotate(180deg)` filter to genuine
OSM raster tiles. The `OSM` toggle removes the filter. This avoids API-key tile
providers and their watermarks.

### Q10. How does the simulation move the ambulance?
It stores the ordered Dijkstra geometry with the dispatch. On each tick it
computes elapsed simulated seconds (wall-clock × speed), finds the position at
that distance along the polyline, and updates the ambulance. It is interpolation,
not live GPS.

### Q11. What are the complexity and limits of the routing?
Dijkstra with a binary heap is `O((V + E) log V)`. Nearest-node snapping is a
brute-force `O(V)` scan; with 176k nodes it is acceptable for a few points per
dispatch, but a production system would use a spatial index.

### Q12. What would you improve next?
Spatial indexing for snapping, A* or contraction hierarchies for larger graphs,
one-way and turn restrictions, real traffic feeds, authentication/roles,
WebSocket updates instead of polling, and a proper production deployment with
TLS and backups.

### Q13. How do you know the system works?
`mvn test` runs 15 deterministic tests with 0 failures. A Playwright end-to-end
run exercised all eight screens, created and dispatched a real test call,
recalculated after a closure, reopened it, and finished with 0 console errors and
0 failed requests. Evidence is in `docs/TESTING.md` and `docs/screenshots/`.

### Q14. What is the biggest limitation?
The simulation is not a real dispatch centre: positions, beds, traffic and
closures are synthetic. The routing itself is real over real OSM roads, but its
speed assumptions are estimates. That distinction is stated throughout the UI.

---

## 4. Sustainability

### Social
- Faster, more transparent prioritisation of life-threatening calls.
- Explainable decisions reduce disputes about why a unit was chosen.
- Unverified hospital data is visibly flagged rather than silently trusted.
- No real patient data is stored, reducing privacy risk.

### Environmental
- Travel-time routing can reduce unnecessary distance and idle time versus
  straight-line dispatch.
- No additional hardware is required for the prototype; it runs on a laptop.
- Simulation avoids repeated physical test drives during development.

### Economic
- Zero licence cost: Java, SQLite, Leaflet and OSM data are free/open.
- Runs on modest hardware and a single file database.
- The same design can be piloted by a small municipality before investing in a
  commercial CAD system.

### Technical
- Durable SQLite state survives restarts.
- Database-level invariants prevent double dispatch.
- OSM cache allows rebuilding without another download.
- Tests and docs make handover and maintenance realistic.

### Honest limits
The project does **not** claim live integration, clinical validation or
production readiness. It is an academic prototype with clearly labelled
simulation and real geospatial routing.

---

## 5. Screenshots checklist

All files are in `docs/screenshots/`.

| File | What it should show |
|---|---|
| `01-live.png` | Live Operations, 5 ready units, `OSM · OVERPASS`, map with unit/hospital markers, queue clear |
| `02-intake-result.png` | Intake result with emergency id, ambulance, hospital, scene/hospital ETAs |
| `03-live-route.png` | Active dispatch card and route fitted to real OSM geometry |
| `04-intelligence.png` | Decision trace, candidate comparison, event timeline |
| `05-ambulances.png` | Five ambulance cards and simulation controls |
| `06-hospitals.png` | Hospital cards with demonstration beds and facilities |
| `07-closure-modal.png` | Simulated closure confirmation dialog |
| `08-network-closure.png` | Active closure list and recalculation result |
| `09-history.png` | Analytics KPIs, severity/status distribution, utilisation |
| `10-settings.png` | Operating area, ambulance/hospital config, map-data provenance |
| `11-live-final.png` | Final live view at the restored 8× default |

Capture settings used: 1440×900 viewport, headless Microsoft Edge, full
application loaded from `http://localhost:8080`.

---

## 6. Remaining limitations (state these openly)

1. Simulated movement, beds, traffic and closures; no live feeds.
2. Class-based speed assumptions, no signals or turn restrictions.
3. Brute-force nearest-node snapping.
4. Polling frontend, single-node server, no authentication or TLS.
5. HOSP-05 requires administrator verification.
6. OSM raster tiles require internet access; offline base map is blank.
7. The project is an academic prototype, not a certified emergency system.

---

## 7. Submission checklist

- [x] Java 21 core and custom Dijkstra
- [x] Real OSM Erode network imported and cached
- [x] Five ambulances and five hospital candidates
- [x] Priority queue with FIFO inside severity
- [x] Atomic dispatch and duplicate-prevention invariants
- [x] Lifecycle, simulation, pause/speed, return policy
- [x] Road closure/traffic simulation with recalculation
- [x] Eight functional frontend screens
- [x] REST API
- [x] 15 JUnit tests passing
- [x] Browser end-to-end test with zero console errors
- [x] README, architecture, API, database, testing, OSM and review docs
- [x] Maven wrapper and configuration examples
- [x] Screenshots captured
- [x] Demo reset to 8× and zero active closures
