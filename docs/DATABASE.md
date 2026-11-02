# Database Design

**Engine:** SQLite 3 (WAL mode)  
**File:** `data/arunai.db` (override with `ARUNAI_DB`)  
**Schema:** `src/main/resources/database/schema.sql`

SQLite is the durable source of truth. The in-memory road graph and priority
queue are rebuilt from it at startup, so a restart preserves every call,
dispatch, closure and audit event.

---

## 1. Entity-relationship diagram

```mermaid
erDiagram
  AMBULANCES ||--o{ DISPATCHES : assigned_to
  HOSPITALS  ||--o{ DISPATCHES : receives
  EMERGENCIES ||--o{ DISPATCHES : triggers
  EMERGENCIES ||--o{ EMERGENCY_EVENTS : records
  ROAD_NODES ||--o{ ROAD_EDGES : from_node
  ROAD_NODES ||--o{ ROAD_EDGES : to_node
  ROAD_EDGES ||--o{ ROAD_CLOSURES : closed_by
  SETTINGS {
    TEXT key PK
    TEXT value
    TEXT updated_at
  }
  AMBULANCES {
    TEXT id PK
    TEXT base_location
    REAL base_lat
    REAL base_lon
    TEXT current_location
    REAL current_lat
    REAL current_lon
    TEXT status
    TEXT assigned_emergency_id
    TEXT destination
    INTEGER eta_seconds
    TEXT trip_status
    TEXT updated_at
  }
  HOSPITALS {
    TEXT id PK
    TEXT name
    REAL lat
    REAL lon
    TEXT address
    TEXT facilities
    INTEGER total_beds
    INTEGER occupied_beds
    INTEGER operational
    TEXT verification_status
    TEXT osm_ref
    TEXT note
  }
  EMERGENCIES {
    TEXT id PK
    INTEGER seq
    TEXT type
    TEXT severity
    TEXT required_facility
    REAL lat
    REAL lon
    TEXT description
    TEXT status
    TEXT created_at
    TEXT updated_at
    TEXT queued_at
    TEXT assigned_ambulance_id FK
    TEXT assigned_hospital_id FK
    INTEGER snapped_node_id
    TEXT dispatch_note
    TEXT completed_at
  }
  DISPATCHES {
    TEXT id PK
    TEXT emergency_id FK
    TEXT ambulance_id FK
    TEXT hospital_id FK
    TEXT assigned_at
    INTEGER ambulance_eta_seconds
    INTEGER hospital_eta_seconds
    REAL ambulance_distance_m
    REAL hospital_distance_m
    TEXT status
    TEXT selection_reason
    TEXT alternatives_json
    TEXT ambulance_route
    TEXT hospital_route
    TEXT return_route
    TEXT phase
    INTEGER phase_started_at
    REAL phase_duration_seconds
    TEXT route_warning
    TEXT completed_at
  }
  EMERGENCY_EVENTS {
    INTEGER id PK
    TEXT emergency_id FK
    TEXT status
    TEXT note
    TEXT created_at
  }
  ROAD_NODES {
    INTEGER id PK
    REAL lat
    REAL lon
  }
  ROAD_EDGES {
    TEXT id PK
    INTEGER from_node FK
    INTEGER to_node FK
    INTEGER way_id
    TEXT name
    TEXT road_type
    REAL distance_m
    REAL travel_time_s
    TEXT geometry
    INTEGER closed
    REAL traffic_multiplier
  }
  ROAD_CLOSURES {
    INTEGER id PK
    TEXT edge_id FK
    TEXT reason
    INTEGER active
    TEXT created_at
  }
```

---

## 2. Table notes

### `settings`
Key/value store. Defaults are inserted on first run. Holds operating area,
simulation speed/pause, return policy, demo admission and OSM provenance
(`osm_source`, `osm_bbox`, `osm_imported_at`, `osm_cache_file`,
`network_nodes`, `network_edges`, `demo_reset_at`).

### `ambulances`
Exactly the five demonstration units. `status` is constrained to the eight
operational states. `assigned_emergency_id` and `destination` describe the
active trip. Movement updates `current_lat/current_lon`, `eta_seconds` and
`trip_status`.

### `hospitals`
Five candidates. `lat/lon` are nullable: HOSP-05 has no verified location and
therefore cannot be routed. `facilities` is a comma-separated list of
`GENERAL`, `CARDIAC`, `TRAUMA`. `verification_status` records
`OSM_LOCATION_VERIFIED`, `OSM_LOCATION_VERIFIED_NAME_ALIAS`, `ADMIN_CONFIRMED`
or `UNVERIFIED`. `CHECK (occupied_beds <= total_beds)` protects bed data.

### `emergencies`
`seq` is unique and defines FIFO order. `status` follows the lifecycle enum.
`snapped_node_id` is the nearest OSM node used for routing. `dispatch_note`
explains why a queued call could not yet be assigned.

### `dispatches`
One row per assignment attempt that succeeded. Route geometry is stored as JSON
arrays of `{lat, lon}` so a restart can continue simulation. `phase` is one of
`TO_SCENE`, `AT_SCENE`, `TO_HOSPITAL`, `RETURNING`, `COMPLETED`.
`alternatives_json` preserves the complete candidate comparison and the
human-readable selection reason for audit and review.

### `emergency_events`
Append-only audit timeline: `REPORTED`, `QUEUED`, `ASSIGNED`,
`AMBULANCE_EN_ROUTE`, `ARRIVED_AT_SCENE`, `TRANSPORTING`,
`ARRIVED_AT_HOSPITAL`, `COMPLETED`, `CANCELLED`, plus route warnings.

### `road_nodes` / `road_edges`
The imported OSM graph. An edge is an undirected segment between two OSM nodes.
`travel_time_s` is the Dijkstra weight before the traffic multiplier.
`geometry` stores the full OSM shape. `closed` is checked in Dijkstra, so one
flag closes both directions.

### `road_closures`
Append-only closure history. Only rows with `active=1` are shown and count as
active. Closing inserts an active row; reopening marks the previous active row
inactive and inserts an audit row with `active=0`. This guarantees at most one
active closure per edge after any sequence of open/close operations.

---

## 3. Integrity rules

1. **No duplicate active ambulance assignment**

```sql
CREATE UNIQUE INDEX ux_active_dispatch_ambulance
  ON dispatches(ambulance_id) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_active_dispatch_emergency
  ON dispatches(emergency_id) WHERE status = 'ACTIVE';
```

2. **Atomic reservation**

`DispatchRepository.createAssignment` runs in one transaction:

```text
BEGIN
  UPDATE ambulances
     SET status='EN_ROUTE', assigned_emergency_id=?, ...
   WHERE id=? AND status='AVAILABLE'
  -- must affect exactly 1 row

  INSERT INTO dispatches (...)
  UPDATE emergencies SET status='ASSIGNED', ...
   WHERE id=? AND status='QUEUED'
  -- must affect exactly 1 row
COMMIT
```

Any zero-row update throws `IllegalStateException`; the transaction rolls back.

3. **Bed sanity** — `occupied_beds <= total_beds`, both bounded.

4. **Status domains** — SQL `CHECK` constraints on ambulance status, severity,
   facility, dispatch status and closure flags.

5. **Referential integrity** — foreign keys are enabled on every connection.

---

## 4. Indexes

| Index | Purpose |
|---|---|
| `ix_emergencies_status(status, severity, created_at)` | queue/history reads |
| `ix_emergencies_created(created_at)` | newest-first lists |
| `ix_dispatches_emergency(emergency_id)` | lookup active dispatch |
| `ix_dispatches_ambulance(ambulance_id)` | lookup active trip |
| `ix_events_emergency(emergency_id, id)` | timeline |
| `ix_edges_type(road_type)` | stats and diagnostics |
| `ix_edges_name(name)` | road search |
| `ix_closures_active(active)` | active closure list |
| `ux_active_dispatch_ambulance` | uniqueness invariant |
| `ux_active_dispatch_emergency` | uniqueness invariant |

---

## 5. Data provenance fields

Every layer carries its own honesty marker:

- `settings.osm_source`: `OSM_OVERPASS`, `OSM_CACHE`, `SYNTHETIC_FALLBACK` or
  `NOT_LOADED`.
- `hospitals.verification_status` and `hospitals.note`.
- API booleans `bedDataSimulated`, `simulatedMovement`, `simulated`.
- UI labels `SIMULATED` / `DEMO` on bed, movement, closure and traffic controls.

No table stores real patient data. Descriptions and notes of seeded history
explicitly state that the row is simulated demonstration data.
