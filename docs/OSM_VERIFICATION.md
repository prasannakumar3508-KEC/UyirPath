# OpenStreetMap Data Verification

UyirPath does not invent road geometry. The routing graph is built from an
OpenStreetMap extract of the Erode area.

---

## 1. Imported network

| Property | Value |
|---|---|
| Source label | `OSM_OVERPASS` |
| Imported at | `2026-11-01T08:15:55Z` |
| Bounding box | `11.20,77.45,11.50,77.82` (south, west, north, east) |
| Cached response | `data/osm/erode-network.json` |
| Road nodes | **176,054** |
| Directed edges | **186,729** |
| Named edges | **15,747** |
| Total segment length | **7,119.6 km** |
| Attribution | © OpenStreetMap contributors, ODbL 1.0 |

The import first tries `overpass.kumi.systems` and then
`overpass-api.de`. During this build the primary mirror timed out; the fallback
mirror returned the extract, and the result is still real OSM data. The source is
recorded as `OSM_OVERPASS`, not as a synthetic or manually authored graph.

The raw JSON response is kept so the graph can be rebuilt without another
download:

```bash
java -jar target/uyirpath-dispatch.jar --import-osm   # live download
# or from the Settings screen / API: POST /api/osm {"mode":"cache"}
```

If a live import fails, the existing local graph is preserved and the UI shows
the error. If no cache exists, the only fallback is a **synthetic grid**
(`SYNTHETIC_FALLBACK`), which is labelled as not real roads everywhere and was
never used in this deployment.

---

## 2. Ambulance base references

These coordinates identify real OSM features. They are **not** a claim that an
ambulance is currently stationed there.

| Unit | Base label | OSM reference | Approx. coordinate |
|---|---|---|---|
| AMB-01 | Erode Central Bus Stand | way/30877083 | 11.3470648, 77.7199695 |
| AMB-02 | Erode Junction Railway Station | node/288370387 | 11.3286258, 77.7257896 |
| AMB-03 | Perundurai | node/655090824 | 11.2764669, 77.5836267 |
| AMB-04 | Bhavani | node/245592587 | 11.4405993, 77.6824386 |
| AMB-05 | Chithode | node/4997758492 | 11.3954841, 77.6580812 |

---

## 3. Hospital candidate references

| ID | Candidate name | OSM reference | Verification |
|---|---|---|---|
| HOSP-01 | Sudha Hospitals | node/6936695231 | Location verified. OSM feature is named “Sudha Institute of Medical Sciences Hospital”; short candidate name retained. |
| HOSP-02 | Thanthai Periyar Government Headquarters Hospital | way/31469343 | Location verified from OSM “District Hospital, Eroda”. Name alias must be confirmed by an administrator. |
| HOSP-03 | Lotus Hospital | way/73242511 | Exact OSM name match, location verified. |
| HOSP-04 | Dhanvantri Critical Care Hospital | node/6934062831 | Location verified. OSM feature is named “Dhanvantri Critical Care Center”; candidate name retained. |
| HOSP-05 | Erode Medical Centre | — | **No exact OSM feature confirmed. No coordinates were invented.** Excluded from routing until an administrator sets a verified location in Settings. |

Coordinates stored in `seed_data` mirror the OSM feature centres:

| ID | Latitude | Longitude |
|---|---|---|
| HOSP-01 | 11.3371539 | 77.7109247 |
| HOSP-02 | 11.3393048 | 77.7179260 |
| HOSP-03 | 11.3259471 | 77.7302149 |
| HOSP-04 | 11.3504266 | 77.7311274 |
| HOSP-05 | `NULL` | `NULL` |

Hospital facilities and bed counts are **demonstration data**. They are not
official hospital capacities and should not be presented as such.

---

## 4. What is real vs simulated

| Data | Real? | Notes |
|---|---|---|
| Road geometry, names, road class | Yes | OSM extract via Overpass |
| Road speed assumptions | No | Class-based estimates (primary 50 km/h, secondary 40, etc.) |
| Ambulance base coordinates | Yes (OSM features) | Stationing is a demonstration assumption |
| Ambulance movement/GPS | No | Interpolated along the Java Dijkstra geometry |
| Hospital locations | Yes for HOSP-01–04 | HOSP-05 unverified |
| Hospital names | Candidate data | Two are name aliases |
| Hospital beds/facilities | No | Demonstration only |
| Road closures/traffic | No | Simulated operator controls |
| Emergency calls/history | No | Synthetic test/demo records |

---

## 5. Map tiles

The application uses standard OpenStreetMap raster tiles. The dark `OPS` style
is produced with a CSS `invert(1) hue-rotate(180deg)` filter; the `OSM` toggle
removes the filter. No API-key tile provider (such as CARTO) is used, and the
earlier “API KEY REQUIRED” watermark is gone.

Tile availability is a third-party dependency. If the tile server is unreachable,
the base map is blank but routes, markers and all controls still work.

---

## 6. Reproducing the checks

```sql
-- network size
SELECT COUNT(*) FROM road_nodes;
SELECT COUNT(*) FROM road_edges;

-- hospital verification status
SELECT id, name, lat, lon, verification_status, osm_ref FROM hospitals;

-- active closures (must be 0 after a reset)
SELECT * FROM road_closures WHERE active = 1;
```

API checks:

```text
GET /api/network
GET /api/hospitals
GET /api/route?fromLat=11.3470648&fromLon=77.7199695&toLat=11.3371539&toLon=77.7109247
```
