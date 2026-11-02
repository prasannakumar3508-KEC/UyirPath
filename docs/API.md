# ARUNAI REST API

Base URL: `http://localhost:8080/api`

All request and response bodies are JSON. Errors use:

```json
{ "error": true, "code": "VALIDATION_ERROR", "message": "human-readable reason" }
```

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_ERROR` | bad/missing input |
| 405 | `METHOD_NOT_ALLOWED` | wrong verb |
| 409 | `CONFLICT` | invalid state transition / resource busy |
| 500 | `INTERNAL_ERROR` | unexpected server failure |

Every endpoint is exercised by the browser application. `GET` endpoints are
read-only; state changes require `POST`/`PUT`.

---

## Health

### `GET /api/health`

```json
{
  "status": "ok",
  "product": "ARUNAI",
  "subtitle": "Erode Emergency Dispatch Grid",
  "version": "1.0.0",
  "time": "2026-11-01T08:35:15Z",
  "uptimeSeconds": 42,
  "database": "connected",
  "counts": {
    "ambulances": 5, "hospitals": 5, "emergencies": 3,
    "activeEmergencies": 0, "queued": 0, "activeDispatches": 0
  },
  "network": { "source": "OSM_OVERPASS", "realOsm": true,
               "nodes": 176054, "edges": 186729 },
  "simulation": { "...": "pause and speed state" },
  "mapData": "OpenStreetMap (real)"
}
```

Returns `503` with `"status": "degraded"` if SQLite cannot be queried.

---

## Ambulances

### `GET /api/ambulances`

```json
{
  "ambulances": [{
    "id": "AMB-01",
    "baseLocation": "Erode Central Bus Stand",
    "currentLat": 11.3470648, "currentLon": 77.7199695,
    "status": "AVAILABLE",
    "displayStatus": "AVAILABLE",
    "simulated": true
  }],
  "simulatedMovement": true
}
```

Active trips add `activeDispatchId`, `activeEmergencyId`, `phase`, `simProgress`.

### `GET /api/ambulances/{id}`

Returns `{ "ambulance": {...} }`.

### `POST /api/ambulances/{id}/availability`

```json
{ "available": false }
```

Rejected with `409` while the unit has an active dispatch.

### `PUT /api/ambulances/{id}/base`

```json
{ "baseLocation": "Perundurai", "lat": 11.2764669, "lon": 77.5836267 }
```

Coordinates must be inside the configured operating area.

---

## Hospitals

### `GET /api/hospitals`

```json
{
  "hospitals": [{
    "id": "HOSP-01",
    "name": "Sudha Hospitals",
    "lat": 11.3371539, "lon": 77.7109247,
    "facilities": ["GENERAL", "CARDIAC", "TRAUMA"],
    "totalBeds": 120, "occupiedBeds": 86, "availableBeds": 34,
    "operational": true,
    "verificationStatus": "OSM_LOCATION_VERIFIED",
    "osmRef": "node/6936695231",
    "bedDataSimulated": true,
    "routable": true
  }],
  "bedDataSimulated": true
}
```

### `GET /api/hospitals/{id}`

Returns `{ "hospital": {...} }`.

### `POST /api/hospitals/{id}/beds`

```json
{ "totalBeds": 120, "occupiedBeds": 87 }
```

`occupiedBeds` must be between 0 and `totalBeds`.

### `POST /api/hospitals/{id}/operational`

```json
{ "operational": true }
```

### `PUT /api/hospitals/{id}`

Any of: `name`, `address`, `lat` + `lon`, `facilities`, `totalBeds`,
`occupiedBeds`, `operational`. Supplying coordinates marks the hospital
`ADMIN_CONFIRMED` and makes it routable. This is how HOSP-05 is enabled.

---

## Emergencies

### `POST /api/emergencies`

```json
{
  "type": "ROAD_TRAFFIC_ACCIDENT",
  "severity": "CRITICAL",
  "requiredFacility": "TRAUMA",
  "lat": 11.3716,
  "lon": 77.64519,
  "description": "Two-vehicle collision"
}
```

`type`: `ROAD_TRAFFIC_ACCIDENT | CARDIAC_EMERGENCY | GENERAL_MEDICAL | OTHER`
`severity`: `CRITICAL | SERIOUS | MINOR`
`requiredFacility`: `GENERAL | CARDIAC | TRAUMA`

Response `201`:

```json
{
  "emergency": { "id": "EMG-0004", "status": "ASSIGNED", "...": "..." },
  "dispatch": { "id": "DSP-0004", "ambulanceId": "AMB-05",
                "hospitalName": "Sudha Hospitals", "...": "..." },
  "selection": {
    "success": true,
    "selectedAmbulance": { "ambulanceId": "AMB-05", "etaSeconds": 348 },
    "selectedHospital": { "hospitalId": "HOSP-01", "etaSeconds": 1008 },
    "ambulanceCandidates": ["..."],
    "hospitalCandidates": ["..."],
    "explanation": "AMB-05 has the lowest estimated response time ..."
  },
  "message": "Emergency registered and dispatched automatically."
}
```

If no resource is eligible, `dispatch` is `null`, the emergency stays `QUEUED`
and `message` explains why.

### `GET /api/emergencies?limit=100`

Returns `{ "emergencies": [...] }`, newest first.

### `GET /api/emergencies/{id}`

Returns the emergency, its event timeline, current/last dispatch and a fresh
selection comparison.

---

## Dispatch

### `GET /api/dispatches?scope=active|recent|all&limit=50`

Returns `{ "dispatches": [...], "scope": "active" }`. Dispatch views include
nested `emergency`, `ambulance`, `hospital`, `alternatives`, route geometry,
`simulation` and `progress`.

### `GET /api/dispatches/{id}`

Returns `{ "dispatch": {...} }`.

### `POST /api/dispatch`

Body may be empty to process the whole queue, or:

```json
{ "emergencyId": "EMG-0004" }
```

Retries one queued emergency. Returns `{ "dispatched": true, "dispatch": {...} }`
or `{ "dispatched": false, "message": "...", "queue": [...] }`.

### `POST /api/dispatch/manual`

```json
{
  "emergencyId": "EMG-0004",
  "ambulanceId": "AMB-02",
  "hospitalId": "HOSP-02",
  "reason": "Operator knows the nearest unit is blocked"
}
```

A reason is mandatory. Eligibility is revalidated. The audit note records what
auto-selection would have chosen.

### `POST /api/complete-trip`

```json
{ "dispatchId": "DSP-0004" }
```

Only allowed once the emergency is `ARRIVED_AT_HOSPITAL`.

### `GET /api/selection/{emergencyId}`

Returns the full ambulance/hospital comparison without writing anything.

### `GET /api/queue`

Returns `{ "queue": [...] }` in priority order.

---

## Road network

### `GET /api/network`

```json
{
  "source": "OSM_OVERPASS",
  "realOsm": true,
  "importedAt": "2026-11-01T08:15:55Z",
  "bbox": "11.20,77.45,11.50,77.82",
  "operatingArea": [11.18, 77.40, 11.55, 77.85],
  "stats": {
    "nodes": 176054, "edges": 186729, "namedEdges": 15747,
    "closedEdges": 0, "closedLengthMeters": 0,
    "totalLengthMeters": 7119606.54,
    "byRoadType": { "residential": 88000, "...": 0 }
  },
  "closures": [{
    "closureId": 1, "edgeId": "w769600543_846636563_2164949913",
    "reason": "SIMULATED road closure", "name": "Perundurai Road",
    "roadType": "primary", "distanceMeters": 59.17,
    "createdAt": "2026-11-01T08:29:51Z",
    "geometry": [{ "lat": 11.3306383, "lon": 77.6994021 }]
  }],
  "attribution": "© OpenStreetMap contributors, ODbL 1.0",
  "notice": "Road geometry and names imported from OpenStreetMap via Overpass. ..."
}
```

### `GET /api/network/edges?q=Perundurai&limit=40`

Returns matching named road segments with geometry and closure/traffic state.

### `GET /api/route`

Either coordinates:

```text
/api/route?fromLat=11.3470648&fromLon=77.7199695&toLat=11.3371539&toLon=77.7109247
```

or node ids:

```text
/api/route?fromNode=30877083&toNode=6936695231
```

Response:

```json
{
  "found": true,
  "distanceMeters": 1842.3,
  "travelTimeSeconds": 131.4,
  "geometry": [{ "lat": 11.347, "lon": 77.719 }],
  "nodeIds": [30877083],
  "edgeIds": ["w..."]
}
```

### `POST /api/road/closure`

```json
{ "edgeId": "w769600543_846636563_2164949913",
  "closed": true, "reason": "SIMULATED road closure" }
```

Closes both directions. Recalculates every active route and returns
`{ "ok": true, "edgeId": "...", "closed": true, "recalculation": [...] }`.

Send `"closed": false` to reopen. Reopening deactivates the previous closure row,
so `GET /api/network` no longer lists it.

### `POST /api/road/traffic`

```json
{ "edgeId": "w...", "multiplier": 2.5 }
```

Multiplier must be 1.0–5.0. Applied immediately and recalculation is returned.

### `POST /api/road/recalculate`

Empty body. Reruns Dijkstra for every active dispatch.

---

## History and analytics

### `GET /api/history?limit=50`

Returns emergency history enriched with dispatch, ambulance, hospital and ETA
data. Demo-seeded rows are labelled in their descriptions/notes.

### `GET /api/analytics`

Returns totals, completed count, average response time, severity and facility
breakdowns, ambulance utilisation, hospital bed totals, closure count and
network size. The exact shape is consumed by the History & Analytics screen.

### `GET /api/events?limit=30`

Returns the latest audit events across all emergencies.

---

## Settings and simulation

### `GET /api/settings`

Returns `settings`, `network`, `ambulances`, `hospitals` and `operatingArea`.

### `POST /api/settings`

```json
{
  "operating_area_min_lat": 11.18,
  "operating_area_min_lon": 77.40,
  "operating_area_max_lat": 11.55,
  "operating_area_max_lon": 77.85,
  "simulation_speed": 8,
  "simulation_paused": false,
  "return_policy": "ALWAYS_RETURN",
  "demo_admission": true,
  "osm_bbox": "11.20,77.45,11.50,77.82"
}
```

All keys are optional. The operating area must remain valid and must contain
central Erode. `return_policy` is `ALWAYS_RETURN` or `RELEASE_AFTER_HANDOVER`.

### `GET /api/simulation`

Returns pause state, speed, tick and active-trip summary.

### `POST /api/simulation`

```json
{ "paused": true, "speed": 15 }
```

Either key is optional; speed is clamped to the supported range.

---

## Administration

### `POST /api/demo/reset`

```json
{ "seedHistory": true }
```

Deletes emergencies, dispatches, events, closures and restores the five
ambulances/hospitals. The OSM network is preserved. Speed returns to 8× and the
simulation resumes. With `seedHistory`, three clearly labelled simulated
completed calls are inserted for analytics.

### `POST /api/demo/history`

Only allowed when no emergencies exist. Inserts labelled demonstration history.

### `POST /api/osm`

```json
{ "mode": "live" }
```

`mode`: `live` (Overpass download), `cache` (local OSM JSON) or `fallback`
(synthetic demonstration grid, explicitly not real roads). Recalculates active
routes after import. A failed live import returns `502` and preserves existing
road data.
