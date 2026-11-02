-- ARUNAI Emergency Dispatch Grid - SQLite schema
-- All timestamps are ISO-8601 UTC strings unless the column name ends in _at_ms.

CREATE TABLE IF NOT EXISTS settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ambulances (
    id                    TEXT PRIMARY KEY,
    base_location         TEXT NOT NULL,
    base_lat              REAL NOT NULL,
    base_lon              REAL NOT NULL,
    current_location      TEXT NOT NULL,
    current_lat           REAL NOT NULL,
    current_lon           REAL NOT NULL,
    status                TEXT NOT NULL CHECK (status IN (
        'AVAILABLE','RESERVED','DISPATCHED','EN_ROUTE','AT_SCENE',
        'TRANSPORTING','RETURNING','OUT_OF_SERVICE')),
    assigned_emergency_id TEXT,
    destination           TEXT,
    eta_seconds           INTEGER,
    trip_status           TEXT,
    updated_at            TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS hospitals (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    lat                 REAL,
    lon                 REAL,
    address             TEXT,
    facilities          TEXT NOT NULL DEFAULT 'GENERAL',
    total_beds          INTEGER NOT NULL CHECK (total_beds >= 0 AND total_beds <= 10000),
    occupied_beds       INTEGER NOT NULL CHECK (occupied_beds >= 0),
    operational         INTEGER NOT NULL DEFAULT 1 CHECK (operational IN (0,1)),
    verification_status TEXT NOT NULL DEFAULT 'UNVERIFIED',
    osm_ref             TEXT,
    note                TEXT,
    CHECK (occupied_beds <= total_beds)
);

CREATE TABLE IF NOT EXISTS emergencies (
    id                    TEXT PRIMARY KEY,
    seq                   INTEGER NOT NULL UNIQUE,
    type                  TEXT NOT NULL,
    severity              TEXT NOT NULL CHECK (severity IN ('CRITICAL','SERIOUS','MINOR')),
    required_facility     TEXT NOT NULL CHECK (required_facility IN ('GENERAL','CARDIAC','TRAUMA')),
    lat                   REAL NOT NULL,
    lon                   REAL NOT NULL,
    description           TEXT,
    status                TEXT NOT NULL,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    queued_at             TEXT,
    assigned_ambulance_id TEXT REFERENCES ambulances(id),
    assigned_hospital_id  TEXT REFERENCES hospitals(id),
    snapped_node_id       INTEGER,
    dispatch_note         TEXT,
    completed_at          TEXT
);

CREATE TABLE IF NOT EXISTS dispatches (
    id                       TEXT PRIMARY KEY,
    emergency_id             TEXT NOT NULL REFERENCES emergencies(id),
    ambulance_id             TEXT NOT NULL REFERENCES ambulances(id),
    hospital_id              TEXT NOT NULL REFERENCES hospitals(id),
    assigned_at              TEXT NOT NULL,
    ambulance_eta_seconds    INTEGER NOT NULL,
    hospital_eta_seconds     INTEGER NOT NULL,
    ambulance_distance_m     REAL NOT NULL,
    hospital_distance_m      REAL NOT NULL,
    status                   TEXT NOT NULL CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
    selection_reason         TEXT,
    alternatives_json        TEXT,
    ambulance_route          TEXT,
    hospital_route           TEXT,
    return_route             TEXT,
    phase                    TEXT NOT NULL DEFAULT 'TO_SCENE',
    phase_started_at         INTEGER NOT NULL,
    phase_duration_seconds   REAL NOT NULL DEFAULT 0,
    route_warning            TEXT,
    completed_at             TEXT
);

CREATE TABLE IF NOT EXISTS emergency_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    emergency_id  TEXT NOT NULL REFERENCES emergencies(id) ON DELETE CASCADE,
    status        TEXT NOT NULL,
    note          TEXT,
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS road_nodes (
    id  INTEGER PRIMARY KEY,
    lat REAL NOT NULL,
    lon REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS road_edges (
    id                 TEXT PRIMARY KEY,
    from_node          INTEGER NOT NULL REFERENCES road_nodes(id) ON DELETE CASCADE,
    to_node            INTEGER NOT NULL REFERENCES road_nodes(id) ON DELETE CASCADE,
    way_id             INTEGER,
    name               TEXT,
    road_type          TEXT NOT NULL,
    distance_m         REAL NOT NULL,
    travel_time_s      REAL NOT NULL,
    geometry           TEXT NOT NULL,
    closed             INTEGER NOT NULL DEFAULT 0 CHECK (closed IN (0,1)),
    traffic_multiplier REAL NOT NULL DEFAULT 1.0 CHECK (traffic_multiplier >= 1.0 AND traffic_multiplier <= 5.0)
);

CREATE TABLE IF NOT EXISTS road_closures (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    edge_id    TEXT NOT NULL REFERENCES road_edges(id) ON DELETE CASCADE,
    reason     TEXT,
    active     INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_emergencies_status ON emergencies(status, severity, created_at);
CREATE INDEX IF NOT EXISTS ix_emergencies_created ON emergencies(created_at);
CREATE INDEX IF NOT EXISTS ix_dispatches_emergency ON dispatches(emergency_id);
CREATE INDEX IF NOT EXISTS ix_dispatches_ambulance ON dispatches(ambulance_id);
CREATE INDEX IF NOT EXISTS ix_events_emergency ON emergency_events(emergency_id, id);
CREATE INDEX IF NOT EXISTS ix_edges_type ON road_edges(road_type);
CREATE INDEX IF NOT EXISTS ix_edges_name ON road_edges(name);
CREATE INDEX IF NOT EXISTS ix_closures_active ON road_closures(active);

-- A partial unique index is the database-level guarantee against assigning the
-- same ambulance (or the same emergency) to two active dispatches.
CREATE UNIQUE INDEX IF NOT EXISTS ux_active_dispatch_ambulance
    ON dispatches(ambulance_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS ux_active_dispatch_emergency
    ON dispatches(emergency_id) WHERE status = 'ACTIVE';
