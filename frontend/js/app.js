/* ==========================================================================
   UyirPath — Erode Emergency Dispatch Grid
   Frontend controller: REST API integration, Leaflet operations maps,
   dispatch queue, simulation controls, road closures and analytics.
   All movement, traffic, beds and history are DEMONSTRATION data.
   ========================================================================== */
(function () {
  'use strict';

  /* ------------------------------------------------------------- utilities */

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  const esc = (value) => String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

  const svg = (name) => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;

  const num = (value, fallback = 0) => {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  };

  function fmtDistance(meters) {
    if (meters == null || !Number.isFinite(Number(meters))) return '—';
    const m = Number(meters);
    return m >= 1000 ? (m / 1000).toFixed(2) + ' km' : Math.round(m) + ' m';
  }

  function fmtSeconds(seconds) {
    if (seconds == null || !Number.isFinite(Number(seconds))) return '—';
    const s = Math.max(0, Math.round(Number(seconds)));
    if (s < 60) return s + ' s';
    if (s < 3600) return Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's';
    return Math.floor(s / 3600) + 'h ' + String(Math.floor((s % 3600) / 60)).padStart(2, '0') + 'm';
  }

  function parseDate(value) {
    if (!value) return null;
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  function fmtTime(value) {
    const d = parseDate(value);
    if (!d) return '—';
    return d.toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour12: false });
  }

  function fmtDateTime(value) {
    const d = parseDate(value);
    if (!d) return '—';
    return d.toLocaleString('en-IN', {
      timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short',
      hour: '2-digit', minute: '2-digit', hour12: false
    });
  }

  function timeAgo(value) {
    const d = parseDate(value);
    if (!d) return '—';
    const seconds = Math.max(0, Math.floor((Date.now() - d.getTime()) / 1000));
    if (seconds < 20) return 'just now';
    if (seconds < 60) return seconds + 's ago';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return minutes + 'm ago';
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + 'h ' + (minutes % 60) + 'm ago';
    return Math.floor(hours / 24) + 'd ago';
  }

  function titleCase(value) {
    return String(value == null ? '' : value)
      .toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }

  function severityTag(severity) {
    const s = String(severity || '').toUpperCase();
    return `<span class="sev ${esc(s)}">${esc(s || 'UNKNOWN')}</span>`;
  }

  function statusDot(status) {
    const s = String(status || '').toUpperCase();
    return `<span class="status-badge ${esc(s)}">${esc(titleCase(s))}</span>`;
  }

  const INCIDENT_LABELS = {
    ROAD_TRAFFIC_ACCIDENT: 'Road Traffic Accident',
    CARDIAC_EMERGENCY: 'Cardiac Emergency',
    GENERAL_MEDICAL: 'General Medical Emergency',
    OTHER: 'Other Emergency'
  };
  const incidentLabel = (type) => INCIDENT_LABELS[type] || titleCase(type);

  const STATUS_LABELS = {
    REPORTED: 'Reported', QUEUED: 'Queued', ASSIGNED: 'Assigned',
    AMBULANCE_EN_ROUTE: 'Ambulance en route', ARRIVED_AT_SCENE: 'At scene',
    TRANSPORTING: 'Transporting', ARRIVED_AT_HOSPITAL: 'At hospital',
    COMPLETED: 'Completed', CANCELLED: 'Cancelled'
  };
  const statusLabel = (status) => STATUS_LABELS[status] || titleCase(status);

  const PHASE_LABELS = {
    TO_SCENE: 'To scene', AT_SCENE: 'At scene', TO_HOSPITAL: 'To hospital',
    AT_HOSPITAL: 'Handover', RETURNING: 'Returning to base',
    COMPLETED: 'Completed', ROUTE_BLOCKED: 'Route blocked'
  };
  const phaseLabel = (phase) => PHASE_LABELS[phase] || titleCase(phase);

  const TERMINAL = new Set(['COMPLETED', 'CANCELLED']);
  const isActiveEmergency = (e) => e && !TERMINAL.has(e.status);

  function emptyState(icon, title, text) {
    return `<div class="empty-state">${svg(icon)}<b>${esc(title)}</b><small>${esc(text)}</small></div>`;
  }

  function verifyBadge(status) {
    const s = String(status || 'UNVERIFIED');
    const cls = s === 'UNVERIFIED' ? 'unverified' : s.indexOf('ALIAS') >= 0 ? 'alias' : '';
    return `<span class="verify ${cls}" title="${esc(s)}">${esc(titleCase(s))}</span>`;
  }

  function barRow(label, value, max, cls) {
    const width = max > 0 ? Math.min(100, Math.round((Number(value) / max) * 100)) : 0;
    return `<div class="bar-row"><div class="bar-top"><span>${esc(label)}</span><b>${esc(value)}</b></div>
      <div class="bar ${cls || ''}"><i style="width:${width}%"></i></div></div>`;
  }

  /* ---------------------------------------------------------------- state */

  const state = {
    screen: 'live',
    health: null,
    network: null,
    analytics: null,
    settings: null,
    sim: null,
    ambulances: [],
    hospitals: [],
    emergencies: [],
    dispatches: [],
    queue: [],
    events: [],
    history: [],
    operatingArea: null,
    selectedDispatchId: null,
    intelEmergencyId: null,
    intelDetail: null,
    intelFetchedAt: 0,
    manualOpen: false,
    intakePoint: null,
    mapStyle: 'dark',
    layers: { ambulances: true, hospitals: true, incidents: true, routes: true, closures: true },
    roadResults: [],
    roadQuery: '',
    lastRecalc: [],
    previewEdge: null,
    busy: false,
    mutating: false,
    polling: false,
    apiError: false,
    toastLock: 0
  };

  let opsMap = null;
  let intakeMap = null;
  let opsTile = null;
  let intakeTile = null;
  const groups = {};
  let intakeMarker = null;
  let previewLayer = null;

  /* ------------------------------------------------------------------ API */

  class ApiError extends Error {
    constructor(message, status, code) {
      super(message || 'Request failed');
      this.status = status;
      this.code = code;
    }
  }

  async function api(path, options = {}) {
    const opts = { cache: 'no-store', method: options.method || 'GET' };
    if (options.body !== undefined) {
      opts.headers = { 'Content-Type': 'application/json' };
      opts.body = JSON.stringify(options.body);
    }
    let response;
    try {
      response = await fetch('/api' + path, opts);
    } catch (err) {
      state.apiError = true;
      throw new ApiError('Cannot reach the UyirPath server. Check that the Java process is running.', 0, 'NETWORK');
    }
    let data = null;
    try { data = await response.json(); } catch (err) { data = null; }
    if (!response.ok) {
      throw new ApiError((data && data.message) || ('Request failed with HTTP ' + response.status),
        response.status, data && data.code);
    }
    state.apiError = false;
    return data || {};
  }

  /* ------------------------------------------------------ toasts and modal */

  function toast(title, message, type) {
    const stack = $('#toastStack');
    if (!stack) return;
    const node = document.createElement('div');
    node.className = 'toast ' + (type || '');
    node.innerHTML = `<b>${esc(title)}</b>${esc(message || '')}`;
    stack.appendChild(node);
    while (stack.children.length > 4) stack.removeChild(stack.firstElementChild);
    setTimeout(() => {
      node.style.opacity = '0';
      node.style.transform = 'translateX(12px)';
      setTimeout(() => node.remove(), 260);
    }, 5200);
  }

  function openModal(title, bodyHtml, actionsHtml) {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = bodyHtml + (actionsHtml
      ? `<div class="modal-actions">${actionsHtml}</div>` : '');
    $('#modalBackdrop').hidden = false;
  }

  function closeModal() {
    $('#modalBackdrop').hidden = true;
    $('#modalBody').innerHTML = '';
  }

  function showBusy(title, text) {
    state.busy = true;
    $('#busyTitle').textContent = title || 'Working…';
    $('#busyText').textContent = text || 'Please wait.';
    $('#busyOverlay').hidden = false;
  }

  function hideBusy() {
    state.busy = false;
    $('#busyOverlay').hidden = true;
  }

  function toastError(err, title) {
    const message = err && err.message ? err.message : 'Unexpected failure';
    toast(title || 'Request failed', message, 'error');
  }

  /* ------------------------------------------------------------------ maps */

  // Both map styles use genuine OpenStreetMap raster tiles. The "OPS" style is
  // a dark CSS treatment applied to the tile pane only, so no third-party
  // basemap key or non-OSM geometry is involved.
  const OSM_TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  const TILE_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';
  const ERODE_CENTER = [11.355, 77.655];
  const DEFAULT_ZOOM = 11;

  function makeTile() {
    return window.L.tileLayer(OSM_TILES, {
      maxZoom: 19,
      attribution: TILE_ATTRIBUTION
    });
  }

  function initMaps() {
    if (!window.L) {
      console.error('Leaflet failed to load');
      return;
    }
    opsMap = window.L.map('opsMap', {
      zoomControl: false, attributionControl: true, preferCanvas: true
    }).setView(ERODE_CENTER, DEFAULT_ZOOM);
    $('#opsMap').classList.add('map-dark');
    opsTile = makeTile().addTo(opsMap);

    groups.closures = window.L.layerGroup().addTo(opsMap);
    groups.routes = window.L.layerGroup().addTo(opsMap);
    groups.hospitals = window.L.layerGroup().addTo(opsMap);
    groups.incidents = window.L.layerGroup().addTo(opsMap);
    groups.ambulances = window.L.layerGroup().addTo(opsMap);
    previewLayer = window.L.layerGroup().addTo(opsMap);

    opsMap.on('mousemove', (event) => {
      const el = $('#mapCoords');
      if (el) el.textContent = event.latlng.lat.toFixed(4) + '°N / ' + event.latlng.lng.toFixed(4) + '°E';
    });

    intakeMap = window.L.map('intakeMap', { zoomControl: true }).setView(ERODE_CENTER, DEFAULT_ZOOM);
    $('#intakeMap').classList.add('map-dark');
    intakeTile = makeTile().addTo(intakeMap);
    intakeMap.on('click', (event) => setIntakePoint(event.latlng.lat, event.latlng.lng));
    applyMapStyleButtons();
  }

  function setMapStyle(style) {
    state.mapStyle = style === 'osm' ? 'osm' : 'dark';
    const dark = state.mapStyle === 'dark';
    if (opsMap) {
      $('#opsMap').classList.toggle('map-dark', dark);
      opsMap.invalidateSize();
    }
    if (intakeMap) {
      $('#intakeMap').classList.toggle('map-dark', dark);
      intakeMap.invalidateSize();
    }
    applyMapStyleButtons();
  }

  function applyMapStyleButtons() {
    $$('[data-mapstyle]').forEach((button) => {
      button.classList.toggle('active', button.dataset.mapstyle === state.mapStyle);
    });
  }

  function applyLayerVisibility() {
    if (!opsMap) return;
    Object.keys(groups).forEach((key) => {
      const visible = state.layers[key] !== false;
      if (visible && !opsMap.hasLayer(groups[key])) opsMap.addLayer(groups[key]);
      if (!visible && opsMap.hasLayer(groups[key])) opsMap.removeLayer(groups[key]);
    });
    if (previewLayer) {
      if (opsMap.hasLayer(previewLayer)) previewLayer.removeFrom(opsMap);
      if (state.layers.routes) previewLayer.addTo(opsMap);
    }
  }

  function setIntakePoint(lat, lon) {
    state.intakePoint = { lat: Number(lat), lon: Number(lon) };
    if (!intakeMap) return;
    const icon = window.L.divIcon({
      className: '',
      html: '<div class="mk mk-incident critical"><span>!</span></div>',
      iconSize: [22, 22], iconAnchor: [11, 11]
    });
    if (intakeMarker) {
      intakeMarker.setLatLng([lat, lon]);
    } else {
      intakeMarker = window.L.marker([lat, lon], { icon, keyboard: false }).addTo(intakeMap);
    }
    intakeMap.panTo([lat, lon]);
    renderIntakeReadout();
  }

  function clearIntakePoint() {
    state.intakePoint = null;
    if (intakeMarker && intakeMap) {
      intakeMap.removeLayer(intakeMarker);
      intakeMarker = null;
    }
    renderIntakeReadout();
  }

  function renderIntakeReadout() {
    const readout = $('#intakeCoords');
    const status = $('#locationStatus');
    if (!readout) return;
    if (!state.intakePoint) {
      readout.classList.remove('set');
      readout.innerHTML = `<span>SELECTED POINT</span><strong>Click the map to place the incident</strong>
        <small>Validated against the Erode operating area, then snapped to the nearest OSM node.</small>`;
      if (status) status.textContent = 'Browser GPS is optional. Manual map selection always works.';
      return;
    }
    const lat = state.intakePoint.lat;
    const lon = state.intakePoint.lon;
    const inside = withinArea(lat, lon);
    readout.classList.add('set');
    readout.innerHTML = `<span>SELECTED POINT</span><strong>${lat.toFixed(5)}°N / ${lon.toFixed(5)}°E</strong>
      <small>Server will snap this point to the nearest OSM road node before Dijkstra runs.</small>`;
    if (status) {
      status.textContent = inside
        ? 'Inside the Erode operating area. Ready to queue and dispatch.'
        : 'Outside the operating area. The server will reject this point — move the pin inside the box.';
    }
  }

  function withinArea(lat, lon) {
    const area = state.operatingArea;
    if (!Array.isArray(area) || area.length < 4) return true;
    const [minLat, minLon, maxLat, maxLon] = area.map(Number);
    return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
  }

  function markerIcon(kind, options = {}) {
    let html = '';
    let size = [22, 22];
    let anchor = [11, 11];
    if (kind === 'ambulance') {
      const busy = options.busy ? 'busy' : '';
      const off = options.off ? 'off' : '';
      const selected = options.selected ? 'selected' : '';
      html = `<div class="mk mk-amb ${busy} ${off} ${selected}"><span>${esc(options.label || '')}</span></div>`;
      size = [32, 23]; anchor = [16, 11];
    } else if (kind === 'hospital') {
      html = `<div class="mk mk-hosp ${options.unverified ? 'unverified' : ''}"><span>+</span></div>`;
      size = [25, 25]; anchor = [12, 12];
    } else if (kind === 'incident') {
      const severity = String(options.severity || 'CRITICAL').toLowerCase();
      html = `<div class="mk mk-incident ${severity}"><span>${options.selected ? '!' : ''}</span></div>`;
      size = [22, 22]; anchor = [11, 11];
    } else if (kind === 'closure') {
      html = '<div class="mk mk-closure"><span>!</span></div>';
      size = [20, 20]; anchor = [10, 10];
    }
    return window.L.divIcon({ className: '', html, iconSize: size, iconAnchor: anchor });
  }

  function routeLine(points, options) {
    if (!Array.isArray(points) || points.length < 2) return null;
    const latlngs = points.map((p) => [Number(p.lat), Number(p.lon)])
      .filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (latlngs.length < 2) return null;
    return window.L.polyline(latlngs, Object.assign({
      lineCap: 'round', lineJoin: 'round', interactive: false
    }, options));
  }

  function routePoints(dispatch) {
    const points = [];
    ['ambulanceRoute', 'hospitalRoute', 'returnRoute'].forEach((key) => {
      if (Array.isArray(dispatch[key])) points.push.apply(points, dispatch[key]);
    });
    const emergency = dispatch.emergency;
    if (emergency && Number.isFinite(Number(emergency.lat))) points.push({ lat: emergency.lat, lon: emergency.lon });
    if (Number.isFinite(Number(dispatch.hospitalLat))) points.push({ lat: dispatch.hospitalLat, lon: dispatch.hospitalLon });
    return points;
  }

  function drawOpsMap() {
    if (!opsMap) return;
    groups.routes.clearLayers();
    groups.ambulances.clearLayers();
    groups.hospitals.clearLayers();
    groups.incidents.clearLayers();
    groups.closures.clearLayers();
    previewLayer.clearLayers();

    const selectedId = state.selectedDispatchId;

    state.dispatches.forEach((dispatch) => {
      const selected = dispatch.id === selectedId;
      const alpha = selected ? 0.96 : 0.2;
      const weight = selected ? 4 : 2;
      const ambulanceLine = routeLine(dispatch.ambulanceRoute, {
        color: '#ff5c35', weight: weight, opacity: alpha
      });
      if (ambulanceLine) {
        ambulanceLine.addTo(groups.routes);
        if (selected) ambulanceLine.bindTooltip('Ambulance route to scene', { sticky: true });
      }
      const hospitalLine = routeLine(dispatch.hospitalRoute, {
        color: '#2dd4c4', weight: Math.max(2, weight - 1), opacity: selected ? 0.9 : 0.16,
        dashArray: selected ? '7 6' : null
      });
      if (hospitalLine) {
        hospitalLine.addTo(groups.routes);
        if (selected) hospitalLine.bindTooltip('Scene to hospital route', { sticky: true });
      }
      const returnLine = routeLine(dispatch.returnRoute, {
        color: '#57a6ff', weight: Math.max(2, weight - 1), opacity: selected ? 0.8 : 0.14,
        dashArray: selected ? '3 8' : null
      });
      if (returnLine) returnLine.addTo(groups.routes);
    });

    (state.network && state.network.closures ? state.network.closures : []).forEach((closure) => {
      const line = routeLine(closure.geometry, {
        color: '#ff3d5e', weight: 5, opacity: 0.8, dashArray: '2 6'
      });
      if (line) {
        line.addTo(groups.closures);
        line.bindTooltip('SIMULATED closure: ' + (closure.name || closure.edgeId), { sticky: true });
      }
      if (Array.isArray(closure.geometry) && closure.geometry.length) {
        const mid = closure.geometry[Math.floor(closure.geometry.length / 2)];
        const marker = window.L.marker([mid.lat, mid.lon], {
          icon: markerIcon('closure'), keyboard: false
        }).addTo(groups.closures);
        marker.on('click', () => showClosureModal(closure));
      }
    });

    state.hospitals.forEach((hospital) => {
      if (hospital.lat == null || hospital.lon == null) return;
      const marker = window.L.marker([hospital.lat, hospital.lon], {
        icon: markerIcon('hospital', { unverified: hospital.verificationStatus === 'UNVERIFIED' }),
        keyboard: false
      }).addTo(groups.hospitals);
      marker.bindTooltip(`<b>${esc(hospital.name)}</b><br>${esc(hospital.id)} · ${esc(hospital.availableBeds)} beds available (simulated)`,
        { direction: 'top', offset: [0, -8] });
      marker.on('click', () => showHospitalModal(hospital.id));
    });

    state.emergencies.filter(isActiveEmergency).forEach((emergency) => {
      if (!Number.isFinite(Number(emergency.lat))) return;
      const selected = state.dispatches.some((d) => d.emergencyId === emergency.id);
      const marker = window.L.marker([emergency.lat, emergency.lon], {
        icon: markerIcon('incident', { severity: emergency.severity, selected }),
        keyboard: false
      }).addTo(groups.incidents);
      marker.bindTooltip(`<b>${esc(emergency.id)}</b> · ${esc(emergency.severity)}<br>${esc(incidentLabel(emergency.type))} · ${esc(statusLabel(emergency.status))}`,
        { direction: 'top', offset: [0, -10] });
      marker.on('click', () => {
        if (emergency.dispatchId) {
          selectDispatch(emergency.dispatchId, true);
        } else {
          openIntel(emergency.id);
        }
      });
    });

    state.ambulances.forEach((ambulance) => {
      if (!Number.isFinite(Number(ambulance.currentLat))) return;
      const busy = ambulance.status !== 'AVAILABLE' && ambulance.status !== 'OUT_OF_SERVICE';
      const active = state.dispatches.find((d) => d.ambulanceId === ambulance.id);
      const marker = window.L.marker([ambulance.currentLat, ambulance.currentLon], {
        icon: markerIcon('ambulance', {
          label: ambulance.id.replace('AMB-', ''),
          busy: busy,
          off: ambulance.status === 'OUT_OF_SERVICE',
          selected: active && active.id === selectedId
        }),
        keyboard: false
      }).addTo(groups.ambulances);
      marker.bindTooltip(`<b>${esc(ambulance.id)}</b> · ${esc(ambulance.status)}<br>${esc(ambulance.currentLocation || ambulance.baseLocation)}`,
        { direction: 'top', offset: [0, -10] });
      marker.on('click', () => {
        if (active) selectDispatch(active.id, true);
        else showAmbulanceModal(ambulance.id);
      });
    });

    if (state.previewEdge) {
      const line = routeLine(state.previewEdge.geometry, {
        color: '#ffb020', weight: 6, opacity: 0.95
      });
      if (line) {
        line.addTo(previewLayer);
        line.bindTooltip('Selected road: ' + (state.previewEdge.name || state.previewEdge.id), { sticky: true });
      }
    }

    applyLayerVisibility();
  }

  function fitRoute(dispatch) {
    const d = dispatch || getSelectedDispatch();
    if (!d) {
      toast('No active route', 'There is no active dispatch geometry to fit.', 'warn');
      return;
    }
    const points = routePoints(d);
    if (!points.length || !opsMap) {
      toast('No route geometry', 'This dispatch has no stored geometry.', 'warn');
      return;
    }
    if (state.screen !== 'live') switchScreen('live');
    setTimeout(() => {
      if (!opsMap) return;
      opsMap.invalidateSize();
      const bounds = window.L.latLngBounds(points.map((p) => [Number(p.lat), Number(p.lon)]));
      opsMap.fitBounds(bounds.pad(0.18), { maxZoom: 16 });
    }, 120);
  }

  /* ------------------------------------------------------------- data load */

  async function loadCore() {
    const [health, ambulanceData, hospitalData, emergencyData, dispatchData, queueData, eventData] =
      await Promise.all([
        api('/health'),
        api('/ambulances'),
        api('/hospitals'),
        api('/emergencies?limit=100'),
        api('/dispatches?scope=active'),
        api('/queue'),
        api('/events?limit=30')
      ]);
    state.health = health;
    state.sim = health.simulation || state.sim;
    state.ambulances = ambulanceData.ambulances || [];
    state.hospitals = hospitalData.hospitals || [];
    state.emergencies = emergencyData.emergencies || [];
    state.dispatches = dispatchData.dispatches || [];
    state.queue = queueData.queue || [];
    state.events = eventData.events || [];
    if (!state.dispatches.some((d) => d.id === state.selectedDispatchId)) {
      state.selectedDispatchId = state.dispatches.length ? state.dispatches[0].id : null;
    }
  }

  async function loadNetwork() {
    state.network = await api('/network');
    if (Array.isArray(state.network.operatingArea)) state.operatingArea = state.network.operatingArea;
    return state.network;
  }

  async function loadAnalytics() {
    state.analytics = await api('/analytics');
    return state.analytics;
  }

  async function loadHistory() {
    const data = await api('/history?limit=100');
    state.history = data.history || [];
    return state.history;
  }

  async function loadSettings() {
    const data = await api('/settings');
    state.settings = data;
    if (Array.isArray(data.operatingArea)) state.operatingArea = data.operatingArea;
    return data;
  }

  async function loadIntel(emergencyId, silent) {
    if (!emergencyId) {
      state.intelDetail = null;
      state.intelEmergencyId = null;
      state.intelFetchedAt = Date.now();
      renderIntelligence();
      return;
    }
    state.intelEmergencyId = emergencyId;
    const content = $('#intelContent');
    if (content && state.screen === 'intelligence' && !state.intelDetail) {
      content.innerHTML = `<div class="panel intel-empty"><span class="eyebrow">LOADING</span>
        <h2>Reading decision trace…</h2>
        <p>Fetching candidates, route legs and audit events from SQLite.</p></div>`;
    }
    try {
      const data = await api('/emergencies/' + encodeURIComponent(emergencyId));
      if (state.intelEmergencyId !== emergencyId) return;
      state.intelDetail = data;
      state.intelFetchedAt = Date.now();
      renderIntelligence();
    } catch (err) {
      if (!silent) toastError(err, 'Cannot load emergency');
    }
  }

  async function refreshAfterChange() {
    try {
      await loadCore();
      await Promise.allSettled([loadNetwork(), loadAnalytics()]);
      renderGlobal();
      renderCurrentScreen();
    } catch (err) {
      toastError(err, 'Refresh failed');
    }
  }

  async function manualRefresh() {
    try {
      await loadCore();
      await Promise.all([loadNetwork(), loadAnalytics()]);
      renderGlobal();
      renderCurrentScreen();
      if (state.screen === 'settings') {
        await loadSettings();
        renderSettings();
      }
      toast('Operational data', 'All screens refreshed from SQLite and the Java engine.');
    } catch (err) {
      toastError(err, 'Refresh failed');
    }
  }

  /* ------------------------------------------------------------- chrome */

  const SCREEN_META = {
    live: ['ERODE · OPERATIONS BOARD', 'Live Operations'],
    intake: ['ERODE · CALL INTAKE', 'Emergency Intake'],
    intelligence: ['ERODE · DECISION TRACE', 'Dispatch Intelligence'],
    ambulances: ['ERODE · FLEET STATUS', 'Ambulance Management'],
    hospitals: ['ERODE · RECEIVING FACILITIES', 'Hospital Management'],
    network: ['ERODE · ROAD GRAPH', 'Road Network'],
    history: ['ERODE · AUDIT & METRICS', 'History & Analytics'],
    settings: ['ERODE · CONFIGURATION', 'Settings']
  };

  function renderGlobal() {
    const active = state.emergencies.filter(isActiveEmergency).length;
    const el = $('#navActiveCount');
    if (el) el.textContent = String(active);

    renderSimChrome();

    const chip = $('#sidebarNetwork');
    if (chip) {
      const source = state.network ? state.network.source : (state.health && state.health.network ? state.health.network.source : 'NOT_LOADED');
      chip.className = 'network-chip';
      if (!state.network && state.apiError) {
        chip.textContent = 'API · UNREACHABLE';
        chip.classList.add('bad');
      } else if (!source || source === 'NOT_LOADED') {
        chip.textContent = 'NETWORK · NOT LOADED';
        chip.classList.add('bad');
      } else if (source === 'SYNTHETIC_FALLBACK') {
        chip.textContent = 'SYNTHETIC · FALLBACK';
        chip.classList.add('bad');
      } else if (source === 'OSM_CACHE') {
        chip.textContent = 'OSM · LOCAL CACHE';
        chip.classList.add('warn');
      } else {
        chip.textContent = 'OSM · OVERPASS';
      }
    }

    const notice = $('#dataNotice');
    if (notice) {
      let text = '';
      let cls = 'global-notice';
      if (state.apiError) {
        text = 'API UNREACHABLE — the interface is showing the last successful response. Start the Java server and refresh.';
        cls += ' fallback';
      } else if (state.network && (!state.network.realOsm || state.network.source === 'OSM_CACHE')) {
        text = state.network.notice;
        if (!state.network.realOsm) cls += ' fallback';
      }
      if (text) {
        notice.className = cls;
        notice.innerHTML = svg('warning') + '<span>' + esc(text) + '</span>';
        notice.classList.remove('hidden');
      } else {
        notice.className = 'global-notice hidden';
        notice.innerHTML = '';
      }
    }

    const mapSub = $('#mapSubLabel');
    if (mapSub) {
      const net = state.network;
      mapSub.textContent = net
        ? `${net.source.replace(/_/g, ' ')} · ${Number(net.stats.edges).toLocaleString('en-IN')} edges · ${state.sim ? state.sim.speed : '—'}x simulation`
        : 'Awaiting road network…';
    }
  }

  function renderSimChrome() {
    const paused = !!(state.sim && state.sim.paused);
    const speed = state.sim && state.sim.speed ? state.sim.speed : 8;
    const chip = $('#topSimBtn');
    if (chip) chip.classList.toggle('paused', paused);
    const label = $('#topSimLabel');
    if (label) label.textContent = paused ? 'SIMULATION PAUSED' : 'SIM ' + speed + 'x';
    const stateLabel = $('#simStateLabel');
    if (stateLabel) {
      stateLabel.textContent = paused ? 'SIMULATION PAUSED' : 'SIMULATION ACTIVE';
      stateLabel.className = 'tag ' + (paused ? 'amber' : 'teal');
    }
    const pauseBtn = $('#simPauseBtn');
    if (pauseBtn) {
      pauseBtn.innerHTML = paused
        ? svg('play') + '<span>Resume</span>'
        : svg('pause') + '<span>Pause</span>';
    }
    syncSpeedSelect($('#simSpeed'), speed);
    const top = $('#topSimBtn');
    if (top) top.title = paused ? 'Resume simulated movement' : 'Pause simulated movement';
  }

  function syncSpeedSelect(select, speed) {
    if (!select) return;
    const value = String(Math.round(Number(speed || 8)));
    if (!Array.from(select.options).some((o) => o.value === value)) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = value + 'x';
      select.appendChild(option);
    }
    if (document.activeElement !== select) select.value = value;
  }

  function renderCurrentScreen() {
    switch (state.screen) {
      case 'live': renderLive(); break;
      case 'intake': renderIntake(); break;
      case 'intelligence': renderIntelligence(); break;
      case 'ambulances': renderAmbulances(); break;
      case 'hospitals': renderHospitals(); break;
      case 'network': renderNetwork(); break;
      case 'history': renderHistory(); break;
      case 'settings': renderSettings(); break;
    }
  }

  /* --------------------------------------------------------------- live */

  function getSelectedDispatch() {
    return state.dispatches.find((d) => d.id === state.selectedDispatchId) || null;
  }

  function renderLive() {
    renderLiveKpis();
    renderQueue();
    renderActiveDispatch();
    renderActivity();
    drawOpsMap();
  }

  function renderLiveKpis() {
    const active = state.emergencies.filter(isActiveEmergency).length;
    const queued = state.emergencies.filter((e) => e.status === 'QUEUED').length;
    const available = state.ambulances.filter((a) => a.status === 'AVAILABLE').length;
    const deployed = state.ambulances.filter((a) => a.status !== 'AVAILABLE' && a.status !== 'OUT_OF_SERVICE').length;
    const response = state.analytics && state.analytics.response ? state.analytics.response : null;

    $('#kpiActive').textContent = String(active);
    $('#kpiActiveNote').textContent = queued ? queued + ' waiting in priority queue' : 'no calls waiting';
    $('#kpiAvailable').textContent = String(available);
    $('#kpiAvailableNote').textContent = 'of five units · ' + deployed + ' deployed';
    $('#kpiAvgResponse').textContent = response && response.averageSeconds != null
      ? fmtSeconds(response.averageSeconds) : '—';
    const kpiAvgNote = $('#kpiAvgResponse').parentElement.querySelector('small');
    if (kpiAvgNote) {
      kpiAvgNote.textContent = response && response.sampleSize
        ? 'from ' + response.sampleSize + ' completed call' + (response.sampleSize === 1 ? '' : 's')
        : 'from stored audit events';
    }

    const net = state.network;
    const source = net ? net.source : 'NOT LOADED';
    $('#kpiNetwork').textContent = net
      ? source.replace(/_/g, ' ') : 'NOT LOADED';
    $('#kpiNetworkNote').textContent = net
      ? Number(net.stats.nodes).toLocaleString('en-IN') + ' nodes · ' + Number(net.stats.edges).toLocaleString('en-IN') + ' edges'
      : 'OpenStreetMap not loaded';
  }

  function renderQueue() {
    const list = $('#queueList');
    const count = $('#queueCount');
    if (count) count.textContent = String(state.queue.length);
    if (!list) return;
    if (!state.queue.length) {
      list.innerHTML = emptyState('i-live', 'Queue clear', 'Every registered call has a dispatch or has completed.');
      return;
    }
    list.innerHTML = state.queue.map((emergency, index) => `
      <button class="queue-item ${esc(emergency.severity)}" data-act="queue-open" data-id="${esc(emergency.id)}">
        <div class="queue-top">
          <span class="queue-id">#${index + 1} · ${esc(emergency.id)}</span>
          ${severityTag(emergency.severity)}
        </div>
        <span class="queue-type">${esc(incidentLabel(emergency.type))}</span>
        <div class="queue-meta">
          <span>${esc(emergency.requiredFacility || 'GENERAL')}</span>
          <span>${esc(timeAgo(emergency.createdAt))}</span>
          <span>${Number(emergency.lat).toFixed(4)}, ${Number(emergency.lon).toFixed(4)}</span>
        </div>
        ${emergency.dispatchNote ? `<div class="queue-note">${esc(emergency.dispatchNote)}</div>` : ''}
        <div class="ad-actions">
          <span class="text-btn" data-act="queue-dispatch" data-id="${esc(emergency.id)}">RETRY DISPATCH ${svg('arrow')}</span>
        </div>
      </button>`).join('');
  }

  function renderActiveDispatch() {
    const container = $('#activeDispatchCard');
    if (!container) return;
    const d = getSelectedDispatch();
    if (!d) {
      container.innerHTML = emptyState('i-ambulance', 'No active dispatch', 'Register an emergency to start a simulated trip.');
      return;
    }
    const emergency = d.emergency || state.emergencies.find((e) => e.id === d.emergencyId) || {};
    const sim = d.simulation || {};
    const progress = Math.round(num(d.progress) * 100);
    const phase = d.phase || sim.phase;
    const duration = num(sim.phaseDurationSeconds);
    const remaining = duration > 0 ? Math.max(0, Math.round(duration * (1 - num(d.progress)))) : null;
    const arrived = emergency.status === 'ARRIVED_AT_HOSPITAL';

    container.innerHTML = `
      <div class="ad-head">
        <span class="ad-id">${esc(d.id)} · ${esc(emergency.id || d.emergencyId)}</span>
        ${severityTag(emergency.severity || d.severity)}
      </div>
      <div class="ad-title">${esc(incidentLabel(emergency.type || d.incidentType))}</div>
      <div class="ad-sub">${esc(statusLabel(emergency.status))} · ${esc(d.hospitalName || 'hospital pending')}</div>
      <div class="ad-route">${svg('ambulance')}
        <div><b>${esc(d.ambulanceId)}</b> ${svg('arrow')} <b>${esc(d.hospitalName || '—')}</b>
          <small>${esc(d.ambulanceBase || '')} · ${esc(phaseLabel(phase))}</small></div>
      </div>
      <div class="ad-grid">
        <div class="ad-stat"><span>SCENE ETA (PLAN)</span><b>${fmtSeconds(d.ambulanceEtaSeconds)}</b></div>
        <div class="ad-stat"><span>HOSPITAL ETA (PLAN)</span><b>${fmtSeconds(d.hospitalEtaSeconds)}</b></div>
        <div class="ad-stat"><span>PHASE REMAINING</span><b>${remaining == null ? '—' : fmtSeconds(remaining)}</b></div>
        <div class="ad-stat"><span>SCENE DISTANCE</span><b>${fmtDistance(d.ambulanceDistanceMeters)}</b></div>
      </div>
      <div class="progress ${phase === 'TO_HOSPITAL' ? 'teal' : ''}"><i style="width:${progress}%"></i></div>
      <div class="ad-sub" style="display:flex;justify-content:space-between">
        <span>${esc(phaseLabel(phase))}</span><span>${progress}%</span>
      </div>
      ${d.routeWarning ? `<div class="ad-warning">${esc(d.routeWarning)}</div>` : ''}
      <div class="ad-actions">
        <button class="btn ghost small" data-act="open-intel" data-id="${esc(emergency.id)}">INTEL ${svg('arrow')}</button>
        <button class="btn ghost small" data-act="fit-route">FIT ROUTE</button>
        <button class="btn ghost small" data-act="recalc">RECALC</button>
        ${arrived ? `<button class="btn primary small" data-act="complete-trip" data-id="${esc(d.id)}">COMPLETE TRIP</button>` : ''}
      </div>`;
  }

  function renderActivity() {
    const feed = $('#activityFeed');
    if (!feed) return;
    if (!state.events.length) {
      feed.innerHTML = emptyState('i-history', 'No audit events yet', 'Actions appear here as the demo runs.');
      return;
    }
    feed.innerHTML = state.events.slice(0, 30).map((event) => {
      const emergency = state.emergencies.find((e) => e.id === event.emergencyId);
      const severity = emergency ? emergency.severity : '';
      return `<div class="activity-item" data-sev="${esc(severity)}">
        <span class="activity-dot"></span>
        <div class="activity-main">
          <b>${esc(event.emergencyId)} · ${esc(titleCase(event.status))}</b>
          <span>${esc(event.note)}</span>
          <time>${esc(fmtTime(event.createdAt))} · ${esc(timeAgo(event.createdAt))}</time>
        </div>
      </div>`;
    }).join('');
  }

  /* -------------------------------------------------------------- intake */

  function renderIntake() {
    renderIntakeReadout();
    const tag = $('#intakeAreaTag');
    if (tag) {
      const area = state.operatingArea;
      tag.textContent = Array.isArray(area)
        ? `ERODE · ${Number(area[0]).toFixed(2)}–${Number(area[2]).toFixed(2)} N`
        : 'ERODE AREA';
    }
  }

  function renderIntakeResult(result) {
    const box = $('#intakeResult');
    if (!box) return;
    const dispatch = result.dispatch;
    const emergency = result.emergency || {};
    box.classList.remove('hidden');
    if (dispatch) {
      box.innerHTML = `
        <h3>Emergency ${esc(emergency.id)} dispatched</h3>
        <p>${esc(result.message || 'Automatic selection completed.')}</p>
        <div class="ad-grid">
          <div class="ad-stat"><span>AMBULANCE</span><b>${esc(dispatch.ambulanceId)}</b></div>
          <div class="ad-stat"><span>HOSPITAL</span><b>${esc(dispatch.hospitalName || dispatch.hospitalId)}</b></div>
          <div class="ad-stat"><span>SCENE ETA</span><b>${fmtSeconds(dispatch.ambulanceEtaSeconds)}</b></div>
          <div class="ad-stat"><span>HOSPITAL ETA</span><b>${fmtSeconds(dispatch.hospitalEtaSeconds)}</b></div>
        </div>
        <div class="button-row" style="margin-top:8px">
          <button class="btn primary small" data-act="open-intel" data-id="${esc(emergency.id)}">VIEW DECISION TRACE ${svg('arrow')}</button>
          <button class="btn ghost small" data-act="fit-dispatch" data-id="${esc(dispatch.id)}">SHOW ROUTE</button>
        </div>`;
    } else {
      box.innerHTML = `
        <h3>Emergency ${esc(emergency.id)} queued</h3>
        <p>${esc(result.message || 'No eligible ambulance/hospital right now. The call remains at the front of its priority band.')}</p>
        <div class="ad-warning">${esc((result.selection && result.selection.failureReason) || 'The dispatcher will retry when a unit becomes available.')}</div>
        <div class="button-row" style="margin-top:8px">
          <button class="btn primary small" data-act="open-intel" data-id="${esc(emergency.id)}">VIEW QUEUE POSITION ${svg('arrow')}</button>
        </div>`;
    }
  }

  async function submitIntake(event) {
    event.preventDefault();
    if (!state.intakePoint) {
      toast('Location required', 'Click the intake map or use browser GPS before submitting.', 'warn');
      return;
    }
    if (!withinArea(state.intakePoint.lat, state.intakePoint.lon)) {
      toast('Outside operating area', 'Move the incident pin inside the Erode operating box.', 'error');
      return;
    }
    const severityInput = $('input[name="severity"]:checked');
    const facilityInput = $('input[name="facility"]:checked');
    const body = {
      type: $('#incidentType').value,
      severity: severityInput ? severityInput.value : 'CRITICAL',
      requiredFacility: facilityInput ? facilityInput.value : 'GENERAL',
      lat: state.intakePoint.lat,
      lon: state.intakePoint.lon,
      description: $('#incidentDescription').value.trim()
    };
    showBusy('Registering emergency', 'Ranking the priority queue and running Dijkstra selection…');
    try {
      const result = await api('/emergencies', { method: 'POST', body });
      renderIntakeResult(result);
      toast(result.dispatch ? 'Emergency dispatched' : 'Emergency queued',
        result.dispatch ? `${result.dispatch.ambulanceId} assigned to ${result.emergency.id}` : result.message,
        result.dispatch ? '' : 'warn');
      clearIntakePoint();
      hideBusy();
      await refreshAfterChange();
    } catch (err) {
      toastError(err, 'Intake rejected');
    } finally {
      hideBusy();
    }
  }

  function useBrowserGps() {
    if (!navigator.geolocation) {
      toast('GPS unavailable', 'This browser does not expose the Geolocation API. Pick the location on the map.', 'warn');
      return;
    }
    const status = $('#locationStatus');
    if (status) status.textContent = 'Requesting browser GPS permission…';
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setIntakePoint(position.coords.latitude, position.coords.longitude);
        if (intakeMap) intakeMap.setView([position.coords.latitude, position.coords.longitude], 15);
        if (withinArea(position.coords.latitude, position.coords.longitude)) {
          toast('GPS position selected', 'Browser GPS accuracy varies. Confirm the pin on the map.');
        } else {
          toast('GPS outside Erode', 'The browser location is outside the configured operating area.', 'warn');
        }
      },
      (error) => {
        if (status) status.textContent = 'GPS was not available. Manual map selection still works.';
        toast('GPS not available', error.message || 'Permission denied or position unavailable.', 'warn');
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
    );
  }

  /* -------------------------------------------------------- intelligence */

  function renderIntelSelect() {
    const select = $('#intelEmergencySelect');
    if (!select) return;
    const sorted = state.emergencies.slice().sort((a, b) => {
      const ta = parseDate(a.createdAt);
      const tb = parseDate(b.createdAt);
      return (tb ? tb.getTime() : 0) - (ta ? ta.getTime() : 0);
    });
    if (state.intelEmergencyId && !sorted.some((e) => e.id === state.intelEmergencyId)) {
      const current = state.intelDetail && state.intelDetail.emergency;
      if (current && current.id === state.intelEmergencyId) sorted.unshift(current);
    }
    select.innerHTML = sorted.length
      ? sorted.map((e) => `<option value="${esc(e.id)}" ${e.id === state.intelEmergencyId ? 'selected' : ''}>${esc(e.id)} · ${esc(e.severity)} · ${esc(incidentLabel(e.type))} · ${esc(statusLabel(e.status))}</option>`).join('')
      : '<option value="">No emergencies registered</option>';
  }

  function ensureIntel() {
    if (!state.intelEmergencyId) {
      const active = state.emergencies.filter(isActiveEmergency)
        .sort((a, b) => (a.priority || 9) - (b.priority || 9));
      const pick = active[0] || state.emergencies[0];
      if (pick) state.intelEmergencyId = pick.id;
    }
    if (state.intelEmergencyId && (!state.intelDetail
        || state.intelDetail.emergency.id !== state.intelEmergencyId)) {
      loadIntel(state.intelEmergencyId, true);
    } else {
      renderIntelligence();
    }
  }

  function openIntel(emergencyId) {
    state.intelEmergencyId = emergencyId;
    if (state.screen !== 'intelligence') switchScreen('intelligence');
    if (emergencyId) loadIntel(emergencyId);
  }

  function renderIntelligence() {
    renderIntelSelect();
    const content = $('#intelContent');
    const simPanel = $('#intelSimPanel');
    if (!content) return;
    const detail = state.intelDetail;
    if (!detail || !detail.emergency) {
      content.innerHTML = `<div class="panel intel-empty"><h2>No emergency selected</h2>
        <p>Register a call or choose a record from the selector above.</p></div>`;
      if (simPanel) simPanel.innerHTML = '<div class="micro-copy">No trip state available.</div>';
      updateManualPanel(null);
      return;
    }

    const emergency = detail.emergency;
    const dispatch = detail.dispatch;
    const events = detail.events || [];
    const selection = detail.selection || {};
    const alternatives = dispatch && dispatch.alternatives ? dispatch.alternatives : {};
    const ambCandidates = alternatives.ambulances || selection.ambulanceCandidates || [];
    const hospCandidates = alternatives.hospitals || selection.hospitalCandidates || [];
    const chosenAmbulance = dispatch ? dispatch.ambulanceId : (selection.selectedAmbulance && selection.selectedAmbulance.ambulanceId);
    const chosenHospital = dispatch ? dispatch.hospitalId : (selection.selectedHospital && selection.selectedHospital.hospitalId);
    const explanation = (dispatch && dispatch.selectionReason) || selection.explanation
      || emergency.dispatchNote || 'The decision trace is available after selection runs.';
    const phase = dispatch ? dispatch.phase : null;
    const progress = dispatch ? Math.round(num(dispatch.progress) * 100) : 0;
    const warning = dispatch && dispatch.routeWarning;

    content.innerHTML = `
      <section class="panel intel-hero">
        <div class="intel-hero-top">
          <div>
            <span class="eyebrow">${esc(emergency.id)} · ${esc(fmtDateTime(emergency.createdAt))}</span>
            <h2>${esc(incidentLabel(emergency.type))}</h2>
            <div class="sub">${esc(statusLabel(emergency.status))} · ${esc(emergency.requiredFacility)} · ${Number(emergency.lat).toFixed(5)}, ${Number(emergency.lon).toFixed(5)}</div>
          </div>
          ${severityTag(emergency.severity)}
        </div>
        <div class="reasoning"><b>DECISION TRACE</b>${esc(explanation)}</div>
        <div class="route-legs">
          <div class="route-leg"><span>AMBULANCE LEG</span>
            <strong>${fmtSeconds(dispatch ? dispatch.ambulanceEtaSeconds : (selection.selectedAmbulance && selection.selectedAmbulance.etaSeconds))}</strong>
            <small>${fmtDistance(dispatch ? dispatch.ambulanceDistanceMeters : (selection.selectedAmbulance && selection.selectedAmbulance.distanceMeters))} · ${esc(chosenAmbulance || 'not assigned')}</small>
          </div>
          <div class="route-leg"><span>HOSPITAL LEG</span>
            <strong>${fmtSeconds(dispatch ? dispatch.hospitalEtaSeconds : (selection.selectedHospital && selection.selectedHospital.etaSeconds))}</strong>
            <small>${fmtDistance(dispatch ? dispatch.hospitalDistanceMeters : (selection.selectedHospital && selection.selectedHospital.distanceMeters))} · ${esc(chosenHospital || 'not assigned')}</small>
          </div>
        </div>
        ${dispatch ? `<div class="progress ${phase === 'TO_HOSPITAL' ? 'teal' : ''}" style="margin-top:12px"><i style="width:${progress}%"></i></div>
          <div class="sub" style="display:flex;justify-content:space-between"><span>${esc(phaseLabel(phase))}</span><span>${progress}% simulated</span></div>` : ''}
        ${warning ? `<div class="ad-warning">${esc(warning)}</div>` : ''}
      </section>
      <section class="intel-cols">
        <div class="panel">
          <div class="panel-head"><div><span class="eyebrow">RANKED BY DIJKSTRA ETA</span><h2>Ambulance candidates</h2></div></div>
          <div>${ambCandidates.length ? ambCandidates.map((c) => candidateAmbulanceRow(c, chosenAmbulance)).join('') : '<div class="empty-state"><small>No candidate snapshot stored.</small></div>'}</div>
        </div>
        <div class="panel">
          <div class="panel-head"><div><span class="eyebrow">FACILITY + TRAVEL TIME</span><h2>Hospital candidates</h2></div></div>
          <div>${hospCandidates.length ? hospCandidates.map((c) => candidateHospitalRow(c, chosenHospital)).join('') : '<div class="empty-state"><small>No candidate snapshot stored.</small></div>'}</div>
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">APPEND-ONLY AUDIT</span><h2>Lifecycle timeline</h2></div><span class="tag neutral">${events.length} events</span></div>
        <div class="timeline">${events.length ? events.map((event) => `
          <div class="tl-item"><b>${esc(titleCase(event.status))}</b>
            <span>${esc(event.note)}</span>
            <time>${esc(fmtTime(event.createdAt))}</time></div>`).join('')
          : '<div class="empty-state"><small>No events recorded for this emergency.</small></div>'}</div>
      </section>`;

    if (simPanel) {
      const sim = dispatch && dispatch.simulation ? dispatch.simulation : (state.sim || {});
      simPanel.innerHTML = `
        <div class="sim-row"><span>ENGINE</span><b>JAVA DIJKSTRA</b></div>
        <div class="sim-row"><span>SPEED</span><b>${esc(sim.speed || (state.sim && state.sim.speed) || 8)}x</b></div>
        <div class="sim-row"><span>STATE</span><b>${sim.paused ? 'PAUSED' : 'RUNNING'}</b></div>
        <div class="sim-row"><span>PHASE</span><b>${esc(phase ? phaseLabel(phase) : '—')}</b></div>
        <div class="sim-row"><span>ACTIVE TRIPS</span><b>${esc((state.sim && state.sim.activeTrips) || 0)}</b></div>
        <div class="sim-row"><span>ROUTE WARNING</span><b>${warning ? 'YES' : 'NONE'}</b></div>
        <div class="button-row" style="margin-top:10px">
          <button class="btn ghost small" data-act="set-paused" data-paused="${sim.paused ? 'false' : 'true'}">${sim.paused ? 'RESUME' : 'PAUSE'}</button>
          <button class="btn ghost small" data-act="recalc">RECALC</button>
          ${dispatch && emergency.status === 'ARRIVED_AT_HOSPITAL'
            ? `<button class="btn primary small" data-act="complete-trip" data-id="${esc(dispatch.id)}">COMPLETE</button>` : ''}
        </div>`;
    }
    updateManualPanel(detail);
  }

  function candidateAmbulanceRow(candidate, chosen) {
    const selected = candidate.ambulanceId === chosen;
    const excluded = !candidate.available || !candidate.routeFound;
    return `<div class="comparison-row ${selected ? 'chosen' : ''} ${excluded ? 'excluded' : ''}">
      <div><span class="comp-name">${esc(candidate.ambulanceId)}</span>
        <span class="comp-sub">${esc(candidate.baseLocation || '')}</span></div>
      <div class="comp-eta">${candidate.routeFound ? fmtSeconds(candidate.etaSeconds) : 'NO ROUTE'}
        <small>${candidate.routeFound ? fmtDistance(candidate.distanceMeters) : 'excluded'}</small></div>
      <div class="comp-reason">${esc(candidate.rankReason || '')}${selected ? ' · SELECTED' : ''}</div>
    </div>`;
  }

  function candidateHospitalRow(candidate, chosen) {
    const selected = candidate.hospitalId === chosen;
    const excluded = !candidate.eligible;
    return `<div class="comparison-row ${selected ? 'chosen' : ''} ${excluded ? 'excluded' : ''}">
      <div><span class="comp-name">${esc(candidate.name || candidate.hospitalId)}</span>
        <span class="comp-sub">${esc(candidate.facilityMatch ? 'facility match' : 'facility mismatch')} · ${esc(candidate.availableBeds)} beds</span></div>
      <div class="comp-eta">${candidate.routeFound ? fmtSeconds(candidate.etaSeconds) : 'NO ROUTE'}
        <small>${candidate.routeFound ? fmtDistance(candidate.distanceMeters) : 'excluded'}</small></div>
      <div class="comp-reason">${esc(candidate.reason || '')}${selected ? ' · SELECTED' : ''}</div>
    </div>`;
  }

  function updateManualPanel(detail) {
    const panel = $('#manualPanel');
    const form = $('#manualForm');
    if (!panel || !form) return;
    panel.hidden = !state.manualOpen;
    const toggle = $('#manualToggle');
    if (toggle) toggle.textContent = state.manualOpen ? 'Hide manual override' : 'Manual override…';
    if (!state.manualOpen) return;
    if (!detail || !detail.emergency) {
      form.innerHTML = '<p class="micro-copy">Select an emergency first.</p>';
      return;
    }
    const emergency = detail.emergency;
    const queued = emergency.status === 'QUEUED';
    const available = state.ambulances.filter((a) => a.status === 'AVAILABLE');
    const hospitals = state.hospitals.filter((h) => h.operational && h.lat != null && h.lon != null && h.availableBeds > 0);
    form.innerHTML = `
      <p class="micro-copy">${queued
        ? 'This emergency is QUEUED. Choose an available unit and a routable hospital; the reason is written to the dispatch audit record.'
        : 'Manual override is only allowed while the emergency is QUEUED. Current status: ' + esc(statusLabel(emergency.status)) + '.'}</p>
      <label class="field"><span>Ambulance</span>
        <select id="manualAmbulance" ${queued ? '' : 'disabled'}>
          ${available.length ? available.map((a) => `<option value="${esc(a.id)}">${esc(a.id)} · ${esc(a.baseLocation)}</option>`).join('') : '<option value="">No available ambulance</option>'}
        </select></label>
      <label class="field"><span>Hospital</span>
        <select id="manualHospital" ${queued ? '' : 'disabled'}>
          ${hospitals.length ? hospitals.map((h) => `<option value="${esc(h.id)}">${esc(h.name)} · ${esc(h.availableBeds)} beds</option>`).join('') : '<option value="">No routable hospital</option>'}
        </select></label>
      <label class="field"><span>Reason (required)</span>
        <textarea id="manualReason" rows="2" placeholder="Why is the automatic choice being overridden?" ${queued ? '' : 'disabled'}></textarea></label>
      <button class="btn primary full" type="submit" ${queued && available.length && hospitals.length ? '' : 'disabled'}>Record override</button>`;
  }

  async function submitManual(event) {
    event.preventDefault();
    const ambulanceId = $('#manualAmbulance');
    const hospitalId = $('#manualHospital');
    const reason = $('#manualReason');
    if (!state.intelEmergencyId) return;
    if (!ambulanceId || !ambulanceId.value || !hospitalId || !hospitalId.value) {
      toast('Override incomplete', 'An available ambulance and routable hospital are required.', 'warn');
      return;
    }
    if (!reason || !reason.value.trim()) {
      toast('Reason required', 'Record why the automatic choice is being overridden.', 'warn');
      return;
    }
    try {
      await api('/dispatch/manual', {
        method: 'POST',
        body: {
          emergencyId: state.intelEmergencyId,
          ambulanceId: ambulanceId.value,
          hospitalId: hospitalId.value,
          reason: reason.value.trim()
        }
      });
      toast('Manual override recorded', 'The dispatch audit record contains the operator reason.');
      state.manualOpen = false;
      await refreshAfterChange();
      await loadIntel(state.intelEmergencyId, true);
    } catch (err) {
      toastError(err, 'Override rejected');
    }
  }

  /* ---------------------------------------------------------- ambulances */

  function renderAmbulances() {
    const grid = $('#ambulanceGrid');
    if (!grid) return;
    if (!state.ambulances.length) {
      grid.innerHTML = emptyState('i-ambulance', 'No ambulances', 'Run the demo reset or check the server.');
      return;
    }
    grid.innerHTML = state.ambulances.map((ambulance) => {
      const active = state.dispatches.find((d) => d.ambulanceId === ambulance.id);
      const busy = ambulance.status !== 'AVAILABLE' && ambulance.status !== 'OUT_OF_SERVICE';
      const available = ambulance.status === 'AVAILABLE';
      return `<article class="panel amb-card">
        <div class="amb-head">
          <div class="amb-id">
            <div class="amb-id-badge">${esc(ambulance.id.replace('AMB-', ''))}</div>
            <div><h3>${esc(ambulance.baseLocation)}</h3>
              <small>${esc(ambulance.id)} · ${esc(ambulance.currentLocation || 'position unknown')}</small></div>
          </div>
          ${statusDot(ambulance.status)}
        </div>
        <div class="amb-body">
          <div class="amb-route-line">${svg('ambulance')}
            <div>${active
              ? `Assigned to <b>${esc(active.emergencyId)}</b> · ${esc(active.hospitalName || '')}`
              : 'No active assignment. Unit is held at its demonstration base.'}
              ${active ? `<div class="micro-copy">${esc(phaseLabel(active.phase))} · ${esc(statusLabel(active.emergency && active.emergency.status))}</div>` : ''}
            </div>
          </div>
          <div class="amb-stats">
            <div class="amb-stat"><span>ETA / REMAINING</span><b>${fmtSeconds(ambulance.etaSeconds)}</b></div>
            <div class="amb-stat"><span>CURRENT POSITION</span><b>${Number(ambulance.currentLat).toFixed(4)}, ${Number(ambulance.currentLon).toFixed(4)}</b></div>
            <div class="amb-stat"><span>TRIP STATE</span><b>${esc(ambulance.tripStatus ? titleCase(ambulance.tripStatus) : 'IDLE')}</b></div>
          </div>
          <div class="amb-actions">
            <button class="btn ghost small" data-act="amb-map" data-id="${esc(ambulance.id)}">LOCATE</button>
            <button class="btn ghost small" data-act="edit-base" data-id="${esc(ambulance.id)}">EDIT BASE</button>
            ${active ? `<button class="btn ghost small" data-act="open-trip" data-id="${esc(active.id)}">ACTIVE TRIP</button>` : ''}
            <label class="availability-toggle" title="${busy ? 'Availability is locked while a trip is active' : 'Toggle operational availability'}">
              <input type="checkbox" data-availability="${esc(ambulance.id)}" ${available ? 'checked' : ''} ${busy ? 'disabled' : ''}>
              ${available ? 'OPERATIONAL' : (busy ? 'LOCKED ON TRIP' : 'OUT OF SERVICE')}
            </label>
          </div>
          <div class="micro-copy" style="margin-top:8px">Updated ${esc(timeAgo(ambulance.updatedAt))} · position is SIMULATED along Dijkstra geometry, not live GPS.</div>
        </div>
      </article>`;
    }).join('');
  }

  /* ------------------------------------------------------------ hospitals */

  function renderHospitals() {
    const grid = $('#hospitalGrid');
    if (!grid) return;
    if (!state.hospitals.length) {
      grid.innerHTML = emptyState('i-hospital', 'No hospitals', 'Run the demo reset or check the server.');
      return;
    }
    grid.innerHTML = state.hospitals.map((hospital) => {
      const total = num(hospital.totalBeds);
      const occupied = num(hospital.occupiedBeds);
      const utilised = total > 0 ? Math.min(100, Math.round((occupied / total) * 100)) : 0;
      const located = hospital.lat != null && hospital.lon != null;
      return `<article class="panel hosp-card">
        <div class="hosp-head">
          <div><h3>${esc(hospital.name)}</h3>
            <div class="mono">${esc(hospital.id)} · ${hospital.osmRef ? esc(hospital.osmRef) : 'NO OSM FEATURE'}</div></div>
          ${verifyBadge(hospital.verificationStatus)}
        </div>
        <div class="hosp-body">
          <div class="facility-list">${(hospital.facilities || []).map((f) => `<span>${esc(f)}</span>`).join('')}</div>
          <div class="bed-meter">
            <div class="bed-top"><span>DEMO OCCUPANCY</span><span><b>${occupied}</b> / ${total} beds</span></div>
            <div class="bed-bar"><i style="width:${utilised}%"></i></div>
            <div class="bed-top" style="margin-top:4px"><span>${esc(hospital.availableBeds)} available</span><span>${utilised}%</span></div>
          </div>
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px">
            <div class="bed-controls">
              <button data-act="bed" data-id="${esc(hospital.id)}" data-delta="-1" title="Decrease demo occupied beds">−</button>
              <span>ADJUST OCCUPIED</span>
              <button data-act="bed" data-id="${esc(hospital.id)}" data-delta="1" title="Increase demo occupied beds">+</button>
            </div>
            <span class="tag ${hospital.operational ? 'teal' : 'red'}">${hospital.operational ? 'OPERATIONAL' : 'CLOSED'}</span>
          </div>
          <div class="hosp-meta">${esc(hospital.address || '')}<br>${esc(hospital.note || '')}</div>
          <div class="hosp-actions">
            ${located ? `<button class="btn ghost small" data-act="hospital-map" data-id="${esc(hospital.id)}">SHOW ON MAP</button>` : ''}
            <button class="btn ghost small" data-act="edit-hospital" data-id="${esc(hospital.id)}">${located ? 'EDIT / BEDS' : 'SET LOCATION'}</button>
            <button class="btn ghost small" data-act="toggle-hospital" data-id="${esc(hospital.id)}" data-operational="${hospital.operational ? 'false' : 'true'}">${hospital.operational ? 'MARK CLOSED' : 'MARK OPEN'}</button>
          </div>
          <div class="micro-copy" style="margin-top:7px">${located ? 'Routable in Java Dijkstra: ' + (hospital.routable ? 'yes' : 'no — facility/operational/bed rule failed') : 'No verified coordinates. Excluded from auto-dispatch until an operator confirms a location.'}</div>
        </div>
      </article>`;
    }).join('');
  }

  function openHospitalEdit(hospitalId) {
    const hospital = state.hospitals.find((h) => h.id === hospitalId);
    if (!hospital) return;
    const unverified = hospital.verificationStatus === 'UNVERIFIED';
    openModal('Configure ' + hospital.name, `
      ${unverified ? '<div class="ad-warning" style="margin-bottom:10px">No exact OpenStreetMap feature was verified for this hospital. Entering coordinates marks it ADMIN_CONFIRMED. Do not invent a location.</div>' : ''}
      <div class="form-grid" style="padding:0">
        <label class="field span-2"><span>Name</span><input id="editHospitalName" value="${esc(hospital.name)}"></label>
        <label class="field span-2"><span>Address</span><input id="editHospitalAddress" value="${esc(hospital.address || '')}"></label>
        <label class="field"><span>Latitude</span><input id="editHospitalLat" type="number" step="0.0000001" value="${hospital.lat == null ? '' : esc(hospital.lat)}" placeholder="11.34"></label>
        <label class="field"><span>Longitude</span><input id="editHospitalLon" type="number" step="0.0000001" value="${hospital.lon == null ? '' : esc(hospital.lon)}" placeholder="77.72"></label>
        <label class="field"><span>Total beds (simulated)</span><input id="editHospitalTotal" type="number" min="0" value="${num(hospital.totalBeds)}"></label>
        <label class="field"><span>Occupied beds (simulated)</span><input id="editHospitalOccupied" type="number" min="0" value="${num(hospital.occupiedBeds)}"></label>
        <label class="field span-2"><span>Facilities (comma separated)</span><input id="editHospitalFacilities" value="${esc((hospital.facilities || []).join(','))}"></label>
      </div>
      <p class="micro-copy">Bed counts and facilities are DEMONSTRATION data. They are not reported by the hospitals.</p>`,
      `<button class="btn primary small" data-act="save-hospital" data-id="${esc(hospital.id)}">Save hospital</button>
       <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
  }

  async function saveHospital(hospitalId) {
    const body = {
      name: $('#editHospitalName').value.trim(),
      address: $('#editHospitalAddress').value.trim(),
      totalBeds: Number($('#editHospitalTotal').value),
      occupiedBeds: Number($('#editHospitalOccupied').value),
      facilities: $('#editHospitalFacilities').value.split(',').map((v) => v.trim().toUpperCase()).filter(Boolean)
    };
    const lat = $('#editHospitalLat').value.trim();
    const lon = $('#editHospitalLon').value.trim();
    if (lat !== '' && lon !== '') {
      body.lat = Number(lat);
      body.lon = Number(lon);
    }
    if (!Number.isFinite(body.totalBeds) || !Number.isFinite(body.occupiedBeds)) {
      toast('Invalid bed counts', 'Enter whole numbers for total and occupied beds.', 'warn');
      return;
    }
    try {
      await api('/hospitals/' + encodeURIComponent(hospitalId), { method: 'POST', body });
      closeModal();
      toast('Hospital saved', 'Demo configuration updated. Coordinates, where supplied, are admin-confirmed.');
      await refreshAfterChange();
      if (state.screen === 'hospitals') renderHospitals();
    } catch (err) {
      toastError(err, 'Hospital update rejected');
    }
  }

  /* -------------------------------------------------------------- network */

  function renderNetwork() {
    const net = state.network;
    const kpis = $('#networkKpis');
    if (kpis) {
      if (!net) {
        kpis.innerHTML = '<div class="network-kpi"><span>NETWORK</span><b>—</b><small>not loaded</small></div>';
      } else {
        const s = net.stats;
        kpis.innerHTML = `
          <div class="network-kpi"><span>ROAD NODES</span><b>${Number(s.nodes).toLocaleString('en-IN')}</b><small>OSM vertices</small></div>
          <div class="network-kpi"><span>DIRECTED EDGES</span><b>${Number(s.edges).toLocaleString('en-IN')}</b><small>weighted segments</small></div>
          <div class="network-kpi"><span>NAMED EDGES</span><b>${Number(s.namedEdges).toLocaleString('en-IN')}</b><small>searchable roads</small></div>
          <div class="network-kpi"><span>NETWORK LENGTH</span><b>${(Number(s.totalLengthMeters) / 1000).toFixed(0)} km</b><small>sum of segments</small></div>
          <div class="network-kpi"><span>SIMULATED CLOSURES</span><b>${Number(s.closedEdges)}</b><small>${fmtDistance(s.closedLengthMeters)} closed</small></div>`;
      }
    }

    const source = $('#networkSourcePanel');
    if (source) {
      if (!net) {
        source.innerHTML = '<div class="panel-head"><div><span class="eyebrow">SOURCE</span><h2>Road network not loaded</h2></div></div><p class="micro-copy">Use Settings to import live OSM, load the cache, or start a clearly-labelled synthetic fallback.</p>';
      } else {
        source.innerHTML = `
          <div class="panel-head"><div><span class="eyebrow">${net.realOsm ? 'REAL OPENSTREETMAP DATA' : 'SYNTHETIC DEMONSTRATION DATA'}</span><h2>${esc(net.source.replace(/_/g, ' '))}</h2></div>
            <span class="tag ${net.realOsm ? 'teal' : 'red'}">${net.realOsm ? 'OSM' : 'NOT OSM'}</span></div>
          <div class="source-row"><span>IMPORTED</span><b>${esc(fmtDateTime(net.importedAt))}</b></div>
          <div class="source-row"><span>BBOX</span><b>${esc(net.bbox)}</b></div>
          <div class="source-row"><span>CACHE</span><b>${esc(net.cacheFile)}</b></div>
          <div class="source-row"><span>OPERATING AREA</span><b>${net.operatingArea.map((v) => Number(v).toFixed(3)).join(', ')}</b></div>
          <div class="source-row"><span>ATTRIBUTION</span><b>${esc(net.attribution)}</b></div>
          <p class="micro-copy" style="margin-top:9px">${esc(net.notice)}</p>`;
      }
    }
    renderRoadResults();
    renderClosures();
    renderRecalcResults();
  }

  function renderRoadResults() {
    const table = $('#roadResults');
    if (!table) return;
    const tbody = table.querySelector('tbody');
    if (!state.roadResults.length) {
      tbody.innerHTML = '<tr><td colspan="5"><div class="empty-state"><b>No matching segments</b><small>Search a road name such as Perundurai Road.</small></div></td></tr>';
      return;
    }
    tbody.innerHTML = state.roadResults.map((edge) => `
      <tr>
        <td><b>${esc(edge.name)}</b><div class="mono">${esc(edge.id)}</div></td>
        <td>${esc(edge.roadType)}</td>
        <td class="mono">${fmtDistance(edge.distanceMeters)}</td>
        <td><select data-traffic-edge="${esc(edge.id)}" title="Simulated traffic multiplier">
          ${[1, 1.25, 1.5, 2, 3, 5].map((v) => `<option value="${v}" ${Number(edge.trafficMultiplier) === v ? 'selected' : ''}>${v}x</option>`).join('')}
        </select></td>
        <td>
          <button class="btn ghost small" data-act="preview-edge" data-id="${esc(edge.id)}">MAP</button>
          <button class="btn ${edge.closed ? 'ghost' : 'danger'} small" data-act="${edge.closed ? 'reopen-edge' : 'close-edge'}" data-id="${esc(edge.id)}">${edge.closed ? 'REOPEN' : 'CLOSE'}</button>
        </td>
      </tr>`).join('');
  }

  function renderClosures() {
    const list = $('#closureList');
    if (!list) return;
    const closures = state.network && state.network.closures ? state.network.closures : [];
    if (!closures.length) {
      list.innerHTML = emptyState('i-road', 'No active closures', 'Close a segment above to rerun the Dijkstra routes.');
      return;
    }
    list.innerHTML = closures.map((closure) => `
      <div class="closure-item">
        <div><b>${esc(closure.name || '(unnamed road)')}</b>
          <small>${esc(closure.edgeId)} · ${esc(closure.reason)} · ${esc(fmtDateTime(closure.createdAt))}</small></div>
        <button class="btn ghost small" data-act="reopen-edge" data-id="${esc(closure.edgeId)}">REOPEN</button>
      </div>`).join('');
  }

  function renderRecalcResults() {
    const box = $('#recalcResults');
    if (!box) return;
    if (!state.lastRecalc.length) {
      box.innerHTML = '';
      return;
    }
    box.innerHTML = state.lastRecalc.map((row) => `
      <div style="color:${row.ok === false ? 'var(--red)' : 'var(--muted)'}">
        ${esc(row.dispatchId)} · ${esc(row.phase || '')} · ${row.ok === false ? 'HELD' : 'OK'}
        ${row.etaSeconds != null ? ' · ' + fmtSeconds(row.etaSeconds) : ''} · ${esc(row.reason || '')}
      </div>`).join('');
  }

  async function searchRoads(query) {
    state.roadQuery = query == null ? state.roadQuery : query;
    try {
      const data = await api('/network/edges?q=' + encodeURIComponent(state.roadQuery || '') + '&limit=60');
      state.roadResults = data.edges || [];
      renderRoadResults();
    } catch (err) {
      toastError(err, 'Road search failed');
    }
  }

  async function setClosure(edgeId, closed, reason) {
    state.mutating = true;
    try {
      const data = await api('/road/closure', { method: 'POST', body: { edgeId, closed, reason } });
      state.lastRecalc = data.recalculation || [];
      toast(closed ? 'Simulated closure applied' : 'Segment reopened',
        closed ? 'Active trips were recomputed through the Java Dijkstra engine.' : 'The network is open again; routes recalculated.');
      await refreshAfterChange();
      renderNetwork();
    } catch (err) {
      toastError(err, 'Closure failed');
    } finally {
      state.mutating = false;
    }
  }

  async function setTraffic(edgeId, multiplier) {
    state.mutating = true;
    try {
      const data = await api('/road/traffic', { method: 'POST', body: { edgeId, multiplier } });
      state.lastRecalc = data.recalculation || [];
      toast('Simulated traffic updated', 'Multiplier ' + multiplier + 'x applied; active ETAs recalculated.');
      await refreshAfterChange();
      renderNetwork();
    } catch (err) {
      toastError(err, 'Traffic update failed');
    } finally {
      state.mutating = false;
    }
  }

  async function recalculateRoutes() {
    try {
      showBusy('Recalculating routes', 'Running the Java Dijkstra engine for every active trip…');
      const data = await api('/road/recalculate', { method: 'POST', body: {} });
      state.lastRecalc = data.recalculation || [];
      toast('Routes recalculated', 'Traffic-time weights refreshed for ' + state.lastRecalc.length + ' active trip(s).');
      await refreshAfterChange();
      renderNetwork();
    } catch (err) {
      toastError(err, 'Recalculation failed');
    } finally {
      hideBusy();
    }
  }

  function previewEdge(edgeId) {
    const edge = state.roadResults.find((e) => e.id === edgeId);
    if (!edge) return;
    state.previewEdge = edge;
    switchScreen('live');
    setTimeout(() => {
      drawOpsMap();
      if (edge.geometry && edge.geometry.length && opsMap) {
        const bounds = window.L.latLngBounds(edge.geometry.map((p) => [p.lat, p.lon]));
        opsMap.fitBounds(bounds.pad(0.5), { maxZoom: 17 });
      }
    }, 150);
  }

  function showClosureModal(closure) {
    openModal('Simulated closure', `
      <dl class="kv">
        <dt>Road</dt><dd>${esc(closure.name || '(unnamed road)')}</dd>
        <dt>Edge</dt><dd>${esc(closure.edgeId)}</dd>
        <dt>Reason</dt><dd>${esc(closure.reason)}</dd>
        <dt>Created</dt><dd>${esc(fmtDateTime(closure.createdAt))}</dd>
        <dt>Type</dt><dd>${esc(closure.roadType || '—')}</dd>
      </dl>
      <p class="micro-copy" style="margin-top:9px">This is an operator simulation, not live traffic information. Reopening recalculates active routes.</p>`,
      `<button class="btn primary small" data-act="reopen-edge" data-id="${esc(closure.edgeId)}">Reopen segment</button>
       <button class="btn ghost small" data-act="modal-close">Close</button>`);
  }

  function openClosureModal(edgeId, close) {
    const edge = state.roadResults.find((e) => e.id === edgeId);
    if (!close) {
      setClosure(edgeId, false, 'Reopened by operator');
      return;
    }
    openModal('Close road segment', `
      <dl class="kv">
        <dt>Road</dt><dd>${esc(edge ? edge.name : edgeId)}</dd>
        <dt>Segment</dt><dd>${esc(edgeId)}</dd>
        <dt>Length</dt><dd>${edge ? fmtDistance(edge.distanceMeters) : '—'}</dd>
      </dl>
      <label class="field" style="margin-top:10px"><span>Operator reason</span>
        <input id="closureReason" value="SIMULATED road closure" maxlength="120"></label>
      <p class="micro-copy">The edge is skipped by the Java Dijkstra engine and every active route is recomputed. This does not represent a real road closure.</p>`,
      `<button class="btn danger small" data-act="confirm-closure" data-id="${esc(edgeId)}">Apply closure</button>
       <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
  }

  /* -------------------------------------------------------------- history */

  function renderHistory() {
    renderAnalytics();
    const tbody = $('#historyTable') ? $('#historyTable').querySelector('tbody') : null;
    const count = $('#historyCount');
    if (count) count.textContent = state.history.length + ' record' + (state.history.length === 1 ? '' : 's');
    if (!tbody) return;
    if (!state.history.length) {
      tbody.innerHTML = '<tr><td colspan="9"><div class="empty-state"><b>No emergency history</b><small>Complete a simulated trip or reset with demo history.</small></div></td></tr>';
      return;
    }
    tbody.innerHTML = state.history.map((row) => `
      <tr data-act="history-detail" data-id="${esc(row.id)}" style="cursor:pointer">
        <td class="mono">${esc(row.id)}</td>
        <td>${esc(incidentLabel(row.type))}</td>
        <td>${severityTag(row.severity)}</td>
        <td>${esc(row.requiredFacility)}</td>
        <td>${esc(statusLabel(row.status))}</td>
        <td class="mono">${esc(row.ambulanceId || row.assignedAmbulanceId || '—')}</td>
        <td>${esc(row.hospitalName || row.assignedHospitalName || '—')}</td>
        <td class="mono">${fmtSeconds(row.ambulanceEtaSeconds)}</td>
        <td class="mono">${esc(fmtDateTime(row.createdAt))}</td>
      </tr>`).join('');
  }

  function renderAnalytics() {
    const kpiBox = $('#analyticsKpis');
    const panelBox = $('#analyticsPanels');
    if (!kpiBox || !panelBox) return;
    const analytics = state.analytics;
    if (!analytics) {
      kpiBox.innerHTML = '';
      panelBox.innerHTML = emptyState('i-history', 'Analytics unavailable', 'Refresh to reload SQLite aggregates.');
      return;
    }
    const totals = analytics.totals || {};
    const response = analytics.response || {};
    kpiBox.innerHTML = `
      <div class="kpi"><span class="kpi-label">TOTAL EMERGENCIES</span><strong>${num(totals.emergencies)}</strong><small>durable SQLite rows</small></div>
      <div class="kpi"><span class="kpi-label">COMPLETED</span><strong>${num(totals.completedEmergencies)}</strong><small>${num(totals.activeEmergencies)} active</small></div>
      <div class="kpi"><span class="kpi-label">AVG RESPONSE</span><strong>${response.averageSeconds != null ? fmtSeconds(response.averageSeconds) : '—'}</strong><small>${num(response.sampleSize)} samples</small></div>
      <div class="kpi"><span class="kpi-label">ACTIVE DISPATCHES</span><strong>${num(totals.activeDispatches)}</strong><small>${num(totals.queued)} queued</small></div>
      <div class="kpi"><span class="kpi-label">CLOSED EDGES</span><strong>${num(totals.closedEdges)}</strong><small>of ${Number(totals.networkEdges || 0).toLocaleString('en-IN')} edges</small></div>`;

    const priority = analytics.priorityDistribution || {};
    const priorityMax = Math.max(1, ...Object.values(priority).map(Number));
    const status = analytics.statusDistribution || {};
    const statusMax = Math.max(1, ...Object.values(status).map(Number));
    const route = analytics.routePerformance || {};
    const ambulances = analytics.ambulanceUtilization || [];
    const hospitals = analytics.hospitalUtilization || [];

    panelBox.innerHTML = `
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">PRIORITY MIX</span><h2>Severity distribution</h2></div></div>
        <div class="bar-list">
          ${['CRITICAL', 'SERIOUS', 'MINOR'].map((key) => barRow(key, num(priority[key]), priorityMax,
            key === 'CRITICAL' ? 'critical' : key === 'SERIOUS' ? 'serious' : 'minor')).join('')}
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">LIFECYCLE</span><h2>Status distribution</h2></div></div>
        <div class="bar-list">
          ${Object.keys(status).map((key) => barRow(titleCase(key), num(status[key]), statusMax, 'teal')).join('')}
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">COMPLETED TRIPS</span><h2>Ambulance utilisation</h2></div></div>
        <div class="bar-list">
          ${ambulances.map((row) => `<div class="util-row"><span>${esc(row.id)}</span>
            <div class="bar"><i style="width:${Math.min(100, num(row.sharePercent))}%"></i></div>
            <span>${num(row.sharePercent)}%</span></div>`).join('')}
          <p class="micro-copy">${esc(analytics.definitions && analytics.definitions.utilisation || '')}</p>
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">DEMONSTRATION BEDS</span><h2>Hospital utilisation</h2></div></div>
        <div class="bar-list">
          ${hospitals.map((row) => `<div class="util-row"><span>${esc(row.id)}</span>
            <div class="bar"><i style="width:${Math.min(100, num(row.utilizationPercent))}%"></i></div>
            <span>${num(row.utilizationPercent)}%</span></div>`).join('')}
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">DIJKSTRA TRAVEL-TIME WEIGHTS</span><h2>Route performance</h2></div></div>
        <dl class="kv" style="padding:12px 14px">
          <dt>Avg scene distance</dt><dd>${fmtDistance(route.avgAmbulanceDistanceMeters)}</dd>
          <dt>Avg scene ETA</dt><dd>${fmtSeconds(route.avgAmbulanceEtaSeconds)}</dd>
          <dt>Avg hospital distance</dt><dd>${fmtDistance(route.avgHospitalDistanceMeters)}</dd>
          <dt>Avg hospital ETA</dt><dd>${fmtSeconds(route.avgHospitalEtaSeconds)}</dd>
          <dt>Weight basis</dt><dd>${esc(route.weights || '')}</dd>
        </dl>
      </section>
      <section class="panel">
        <div class="panel-head"><div><span class="eyebrow">AUDIT SAMPLE</span><h2>Recent lifecycle events</h2></div></div>
        <div class="timeline">${(analytics.recentEvents || []).slice(0, 6).map((event) => `
          <div class="tl-item"><b>${esc(event.emergencyId)} · ${esc(titleCase(event.status))}</b>
            <span>${esc(event.note)}</span><time>${esc(fmtTime(event.createdAt))}</time></div>`).join('') || '<div class="empty-state"><small>No events yet.</small></div>'}</div>
      </section>`;
  }

  /* ------------------------------------------------------------- settings */

  function renderSettings() {
    const data = state.settings;
    const net = state.network;
    const area = state.operatingArea || (data && data.operatingArea) || [11.18, 77.4, 11.55, 77.85];
    if ($('#areaMinLat')) $('#areaMinLat').value = Number(area[0]).toFixed(4);
    if ($('#areaMinLon')) $('#areaMinLon').value = Number(area[1]).toFixed(4);
    if ($('#areaMaxLat')) $('#areaMaxLat').value = Number(area[2]).toFixed(4);
    if ($('#areaMaxLon')) $('#areaMaxLon').value = Number(area[3]).toFixed(4);

    if (data && data.settings) {
      const s = data.settings;
      syncSpeedSelect($('#settingSpeed'), s.simulation_speed || 8);
      const ret = $('#settingReturn'); if (ret) ret.value = s.return_policy || 'ALWAYS_RETURN';
      const adm = $('#settingAdmission'); if (adm) adm.value = String(s.demo_admission !== 'false');
      const paused = $('#settingPaused'); if (paused) paused.value = String(s.simulation_paused === 'true');
    }

    const mapBox = $('#settingsMapData');
    if (mapBox) {
      mapBox.innerHTML = net ? `
        <div class="source-row"><span>SOURCE</span><b>${esc(net.source)}</b></div>
        <div class="source-row"><span>OSM REAL DATA</span><b>${net.realOsm ? 'YES' : 'NO — SYNTHETIC'}</b></div>
        <div class="source-row"><span>NODES / EDGES</span><b>${Number(net.stats.nodes).toLocaleString('en-IN')} / ${Number(net.stats.edges).toLocaleString('en-IN')}</b></div>
        <div class="source-row"><span>IMPORTED</span><b>${esc(fmtDateTime(net.importedAt))}</b></div>
        <div class="source-row"><span>CACHE FILE</span><b>${esc(net.cacheFile)}</b></div>
        <p class="micro-copy" style="margin-top:8px">${esc(net.notice)}</p>`
        : '<p class="micro-copy">Network metadata unavailable. Refresh the page.</p>';
    }

    const ambList = $('#ambulanceConfigList');
    if (ambList) {
      ambList.innerHTML = state.ambulances.map((a) => `
        <div class="config-item">
          <div class="config-top"><b>${esc(a.id)}</b><span>${esc(a.status)}</span></div>
          <div class="config-grid">
            <input data-base-label="${esc(a.id)}" value="${esc(a.baseLocation)}" title="Base label">
            <input data-base-lat="${esc(a.id)}" type="number" step="0.0000001" value="${esc(a.baseLat)}" title="Base latitude">
            <input data-base-lon="${esc(a.id)}" type="number" step="0.0000001" value="${esc(a.baseLon)}" title="Base longitude">
            <button class="btn ghost small" data-act="save-base" data-id="${esc(a.id)}">SAVE</button>
          </div>
          <div class="config-note">Demonstration base position. Editing while AVAILABLE moves the unit; OSM coordinates only.</div>
        </div>`).join('');
    }

    const hospList = $('#hospitalConfigList');
    if (hospList) {
      hospList.innerHTML = state.hospitals.map((h) => `
        <div class="config-item">
          <div class="config-top"><b>${esc(h.id)}</b><span>${esc(h.verificationStatus)}</span></div>
          <div class="config-grid">
            <input data-hosp-name="${esc(h.id)}" value="${esc(h.name)}" title="Hospital name">
            <input data-hosp-lat="${esc(h.id)}" type="number" step="0.0000001" value="${h.lat == null ? '' : esc(h.lat)}" placeholder="lat" title="Latitude">
            <input data-hosp-lon="${esc(h.id)}" type="number" step="0.0000001" value="${h.lon == null ? '' : esc(h.lon)}" placeholder="lon" title="Longitude">
            <button class="btn ghost small" data-act="save-config-hospital" data-id="${esc(h.id)}">SAVE</button>
          </div>
          <div class="config-note">${h.lat == null ? 'No verified location. Entering coordinates makes this ADMIN_CONFIRMED.' : esc(h.address || '')}</div>
        </div>`).join('');
    }
  }

  async function saveAmbulanceBase(ambulanceId) {
    const label = $('[data-base-label="' + CSS.escape(ambulanceId) + '"]');
    const lat = $('[data-base-lat="' + CSS.escape(ambulanceId) + '"]');
    const lon = $('[data-base-lon="' + CSS.escape(ambulanceId) + '"]');
    const body = {
      baseLocation: label ? label.value.trim() : 'Configured base',
      lat: lat ? Number(lat.value) : NaN,
      lon: lon ? Number(lon.value) : NaN
    };
    if (!Number.isFinite(body.lat) || !Number.isFinite(body.lon)) {
      toast('Invalid base', 'Latitude and longitude must be numbers.', 'warn');
      return;
    }
    try {
      await api('/ambulances/' + encodeURIComponent(ambulanceId) + '/base', { method: 'PUT', body });
      toast('Base updated', ambulanceId + ' timeline now starts at the configured point.');
      await refreshAfterChange();
      if (state.screen === 'settings') { await loadSettings(); renderSettings(); }
    } catch (err) {
      toastError(err, 'Base update rejected');
    }
  }

  async function saveConfigHospital(hospitalId) {
    const name = $('[data-hosp-name="' + CSS.escape(hospitalId) + '"]');
    const lat = $('[data-hosp-lat="' + CSS.escape(hospitalId) + '"]');
    const lon = $('[data-hosp-lon="' + CSS.escape(hospitalId) + '"]');
    if (!name || !name.value.trim()) {
      toast('Name required', 'A hospital name is required.', 'warn');
      return;
    }
    const body = { name: name.value.trim() };
    if (lat && lon && lat.value.trim() !== '' && lon.value.trim() !== '') {
      body.lat = Number(lat.value);
      body.lon = Number(lon.value);
      if (!Number.isFinite(body.lat) || !Number.isFinite(body.lon)) {
        toast('Invalid coordinates', 'Latitude and longitude must be numbers.', 'warn');
        return;
      }
    }
    try {
      await api('/hospitals/' + encodeURIComponent(hospitalId), { method: 'POST', body });
      toast('Hospital configuration saved', 'Routing eligibility has been refreshed.');
      await refreshAfterChange();
      if (state.screen === 'settings') { await loadSettings(); renderSettings(); }
    } catch (err) {
      toastError(err, 'Hospital update rejected');
    }
  }

  /* ------------------------------------------------------------- modals */

  function showAmbulanceModal(ambulanceId) {
    const amb = state.ambulances.find((a) => a.id === ambulanceId);
    if (!amb) return;
    const active = state.dispatches.find((d) => d.ambulanceId === ambulanceId);
    openModal(amb.id + ' · fleet record', `
      <dl class="kv">
        <dt>Base</dt><dd>${esc(amb.baseLocation)}</dd>
        <dt>Base position</dt><dd>${Number(amb.baseLat).toFixed(6)}, ${Number(amb.baseLon).toFixed(6)}</dd>
        <dt>Status</dt><dd>${esc(amb.status)}</dd>
        <dt>Current location</dt><dd>${esc(amb.currentLocation || '—')}</dd>
        <dt>Current position</dt><dd>${Number(amb.currentLat).toFixed(6)}, ${Number(amb.currentLon).toFixed(6)}</dd>
        <dt>ETA</dt><dd>${fmtSeconds(amb.etaSeconds)}</dd>
        <dt>Assignment</dt><dd>${active ? esc(active.emergencyId + ' · ' + active.hospitalName) : 'none'}</dd>
        <dt>Position source</dt><dd>SIMULATED along Dijkstra geometry — not live GPS</dd>
      </dl>`,
      `<button class="btn ghost small" data-act="edit-base" data-id="${esc(amb.id)}">Edit base</button>
       <button class="btn ghost small" data-act="modal-close">Close</button>`);
  }

  function showHospitalModal(hospitalId) {
    const hospital = state.hospitals.find((h) => h.id === hospitalId);
    if (!hospital) return;
    openModal(hospital.name, `
      <dl class="kv">
        <dt>ID</dt><dd>${esc(hospital.id)}</dd>
        <dt>Verification</dt><dd>${esc(hospital.verificationStatus)}</dd>
        <dt>OSM feature</dt><dd>${esc(hospital.osmRef || 'none verified')}</dd>
        <dt>Address</dt><dd>${esc(hospital.address || '—')}</dd>
        <dt>Position</dt><dd>${hospital.lat == null ? 'no verified coordinates' : Number(hospital.lat).toFixed(6) + ', ' + Number(hospital.lon).toFixed(6)}</dd>
        <dt>Facilities</dt><dd>${esc((hospital.facilities || []).join(', '))}</dd>
        <dt>Demo beds</dt><dd>${num(hospital.occupiedBeds)} / ${num(hospital.totalBeds)} occupied · ${num(hospital.availableBeds)} available</dd>
        <dt>Operational</dt><dd>${hospital.operational ? 'yes' : 'no'}</dd>
        <dt>Routable now</dt><dd>${hospital.routable ? 'yes' : 'no'}</dd>
      </dl>
      <p class="micro-copy" style="margin-top:9px">${esc(hospital.note || '')}</p>
      <p class="micro-copy">All bed and occupancy values are DEMONSTRATION data.</p>`,
      `<button class="btn ghost small" data-act="edit-hospital" data-id="${esc(hospital.id)}">Edit hospital</button>
       ${hospital.lat != null ? `<button class="btn ghost small" data-act="hospital-map" data-id="${esc(hospital.id)}">Show on map</button>` : ''}
       <button class="btn ghost small" data-act="modal-close">Close</button>`);
  }

  async function showEmergencyModal(emergencyId) {
    try {
      const detail = await api('/emergencies/' + encodeURIComponent(emergencyId));
      const emergency = detail.emergency;
      const dispatch = detail.dispatch;
      const events = detail.events || [];
      openModal(emergency.id + ' · ' + incidentLabel(emergency.type), `
        <dl class="kv">
          <dt>Severity</dt><dd>${severityTag(emergency.severity)}</dd>
          <dt>Status</dt><dd>${esc(statusLabel(emergency.status))}</dd>
          <dt>Facility</dt><dd>${esc(emergency.requiredFacility)}</dd>
          <dt>Location</dt><dd>${Number(emergency.lat).toFixed(6)}, ${Number(emergency.lon).toFixed(6)}</dd>
          <dt>Snapped OSM node</dt><dd>${esc(emergency.snappedNodeId || 'pending')}</dd>
          <dt>Description</dt><dd>${esc(emergency.description || '—')}</dd>
          <dt>Ambulance</dt><dd>${esc(dispatch ? dispatch.ambulanceId : (emergency.assignedAmbulanceId || '—'))}</dd>
          <dt>Hospital</dt><dd>${esc(dispatch ? dispatch.hospitalName : (emergency.assignedHospitalName || '—'))}</dd>
          <dt>Created</dt><dd>${esc(fmtDateTime(emergency.createdAt))}</dd>
        </dl>
        <h3 style="font-family:var(--display);font-size:13px;margin-top:14px">Audit timeline</h3>
        <div class="timeline">${events.map((event) => `<div class="tl-item"><b>${esc(titleCase(event.status))}</b>
          <span>${esc(event.note)}</span><time>${esc(fmtTime(event.createdAt))}</time></div>`).join('') || '<small class="micro-copy">No events.</small>'}</div>`,
        `<button class="btn primary small" data-act="open-intel" data-id="${esc(emergency.id)}">Open intelligence</button>
         ${dispatch ? `<button class="btn ghost small" data-act="fit-dispatch" data-id="${esc(dispatch.id)}">Show route</button>` : ''}
         <button class="btn ghost small" data-act="modal-close">Close</button>`);
    } catch (err) {
      toastError(err, 'Cannot load emergency');
    }
  }

  /* --------------------------------------------------------------- actions */

  function selectDispatch(dispatchId, showLive) {
    if (!state.dispatches.some((d) => d.id === dispatchId)) {
      const found = state.dispatches.find((d) => d.id === dispatchId);
      if (!found) {
        toast('Dispatch no longer active', 'The trip may have completed. Refresh history for the final record.', 'warn');
        return;
      }
    }
    state.selectedDispatchId = dispatchId;
    if (showLive) {
      if (state.screen !== 'live') switchScreen('live');
      renderLive();
      fitRoute();
    } else {
      renderActiveDispatch();
      drawOpsMap();
    }
  }

  async function togglePause() {
    const paused = !(state.sim && state.sim.paused);
    await setSimulationPaused(paused);
  }

  async function setSimulationPaused(paused) {
    try {
      const status = await api('/simulation', { method: 'POST', body: { paused } });
      state.sim = status;
      renderSimChrome();
      renderGlobal();
      toast(paused ? 'Simulation paused' : 'Simulation running',
        paused ? 'Dijkstra geometry is frozen; data remains in SQLite.' : 'Movement continues along stored route geometry.');
    } catch (err) {
      toastError(err, 'Simulation control failed');
    }
  }

  async function setSimulationSpeed(speed) {
    try {
      const status = await api('/simulation', { method: 'POST', body: { speed: Number(speed) } });
      state.sim = status;
      renderSimChrome();
      renderGlobal();
      toast('Simulation speed', 'Movement multiplier set to ' + speed + 'x.');
    } catch (err) {
      toastError(err, 'Speed change failed');
    }
  }

  async function completeTrip(dispatchId) {
    try {
      showBusy('Completing trip', 'Releasing the ambulance and closing the lifecycle…');
      await api('/complete-trip', { method: 'POST', body: { dispatchId } });
      toast('Trip completed', 'Ambulance released. The audit trail is preserved.');
      await refreshAfterChange();
    } catch (err) {
      toastError(err, 'Cannot complete trip');
    } finally {
      hideBusy();
    }
  }

  async function retryDispatch(emergencyId) {
    try {
      const result = await api('/dispatch', { method: 'POST', body: { emergencyId } });
      if (result.dispatched) {
        toast('Emergency dispatched', 'The priority queue assigned the next eligible resources.');
        await refreshAfterChange();
        await loadIntel(emergencyId, true);
      } else {
        toast('Still queued', result.message || 'No eligible resources are free.', 'warn');
        await refreshAfterChange();
      }
    } catch (err) {
      toastError(err, 'Dispatch retry failed');
    }
  }

  async function importOsm(mode) {
    const labels = { live: 'live Overpass', cache: 'cached OSM', fallback: 'synthetic fallback' };
    if (mode === 'fallback') {
      openModal('Use synthetic fallback?', `
        <p>The fallback grid is generated by Java and is <strong>not real Erode road data</strong>. It exists only so the interface can be demonstrated without Overpass.</p>
        <p class="micro-copy" style="margin-top:8px">Every screen will label it SYNTHETIC_FALLBACK.</p>`,
        `<button class="btn danger small" data-act="confirm-import" data-mode="fallback">Use synthetic grid</button>
         <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
      return;
    }
    await performImport(mode);
  }

  async function performImport(mode) {
    closeModal();
    showBusy('Importing ' + (mode === 'live' ? 'OpenStreetMap' : 'OSM cache'),
      mode === 'live' ? 'Calling Overpass. This can take one to three minutes; keep this tab open.' : 'Reading the local OSM response and rebuilding the Java graph…');
    try {
      const data = await api('/osm/import', { method: 'POST', body: { mode } });
      state.lastRecalc = data.recalculation || [];
      toast('Map data updated', (data.result && data.result.message) || 'Road network reloaded.', data.success ? '' : 'warn');
      await loadCore();
      await Promise.all([loadNetwork(), loadAnalytics()]);
      if (state.screen === 'settings') { await loadSettings(); renderSettings(); }
      renderGlobal();
      renderCurrentScreen();
      drawOpsMap();
      await searchRoads(state.roadQuery || '');
    } catch (err) {
      toastError(err, 'OSM import failed');
    } finally {
      hideBusy();
    }
  }

  async function resetDemo(seedHistory) {
    closeModal();
    showBusy('Resetting demonstration data', 'Deleting calls, dispatches and closures; preserving the imported OSM network…');
    try {
      const data = await api('/demo/reset', { method: 'POST', body: { seedHistory: !!seedHistory } });
      state.selectedDispatchId = null;
      state.intelEmergencyId = null;
      state.intelDetail = null;
      state.lastRecalc = [];
      state.previewEdge = null;
      toast('Demo data reset', data.message || 'Operational records cleared; road network preserved.');
      await loadCore();
      await Promise.all([loadNetwork(), loadAnalytics(), loadHistory(), loadSettings()]);
      renderGlobal();
      renderSettings();
      renderCurrentScreen();
      drawOpsMap();
    } catch (err) {
      toastError(err, 'Demo reset failed');
    } finally {
      hideBusy();
    }
  }

  async function saveAreaSettings(event) {
    event.preventDefault();
    const body = {
      operating_area_min_lat: Number($('#areaMinLat').value),
      operating_area_min_lon: Number($('#areaMinLon').value),
      operating_area_max_lat: Number($('#areaMaxLat').value),
      operating_area_max_lon: Number($('#areaMaxLon').value)
    };
    if (Object.values(body).some((v) => !Number.isFinite(v))) {
      toast('Invalid area', 'All four operating-area fields must be numbers.', 'warn');
      return;
    }
    try {
      await api('/settings', { method: 'POST', body });
      toast('Operating area saved', 'Incident validation now uses the new box. Roads are not re-imported automatically.');
      await loadSettings();
      await loadNetwork();
      renderGlobal();
      renderSettings();
      renderIntake();
    } catch (err) {
      toastError(err, 'Area update rejected');
    }
  }

  async function saveSimulationSettings(event) {
    event.preventDefault();
    const body = {
      simulation_speed: Number($('#settingSpeed').value),
      return_policy: $('#settingReturn').value,
      demo_admission: $('#settingAdmission').value === 'true',
      simulation_paused: $('#settingPaused').value === 'true'
    };
    try {
      await api('/settings', { method: 'POST', body });
      await loadCore();
      await loadSettings();
      renderGlobal();
      renderSettings();
      toast('Simulation settings saved', 'Speed, return policy, admission and pause state persisted in SQLite.');
    } catch (err) {
      toastError(err, 'Simulation settings rejected');
    }
  }

  function confirmReset() {
    const seedHistory = $('#resetHistory') && $('#resetHistory').checked;
    openModal('Reset demonstration dataset?', `
      <p>This deletes every emergency, dispatch, audit event and road closure, and restores the five demonstration ambulances and hospitals.</p>
      <p class="micro-copy" style="margin-top:8px">The imported OpenStreetMap road network is preserved. This cannot be undone.</p>
      ${seedHistory ? '<p class="micro-copy" style="margin-top:8px;color:var(--amber)">Three labelled DEMO HISTORY records will be inserted.</p>' : ''}`,
      `<button class="btn danger small" data-act="confirm-reset" data-seed="${seedHistory ? 'true' : 'false'}">Reset now</button>
       <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
  }

  /* --------------------------------------------------------- event wiring */

  function switchScreen(screen) {
    if (!SCREEN_META[screen]) return;
    state.screen = screen;
    $$('.nav-item').forEach((button) => {
      const active = button.dataset.screen === screen;
      button.classList.toggle('active', active);
      button.setAttribute('aria-current', active ? 'page' : 'false');
    });
    $$('.screen').forEach((section) => section.classList.toggle('active', section.dataset.screen === screen));
    const meta = SCREEN_META[screen];
    $('#pageEyebrow').textContent = meta[0];
    $('#pageTitle').textContent = meta[1];
    state.manualOpen = false;
    updateManualPanel(state.intelDetail);

    if (screen === 'live') {
      renderLive();
      setTimeout(() => { if (opsMap) { opsMap.invalidateSize(); drawOpsMap(); } }, 80);
    } else if (screen === 'intake') {
      renderIntake();
      setTimeout(() => { if (intakeMap) intakeMap.invalidateSize(); }, 80);
    } else if (screen === 'intelligence') {
      ensureIntel();
    } else if (screen === 'ambulances') {
      renderAmbulances();
    } else if (screen === 'hospitals') {
      renderHospitals();
    } else if (screen === 'network') {
      loadNetwork().then(() => {
        renderNetwork();
        searchRoads(state.roadQuery || '');
      }).catch((err) => toastError(err, 'Cannot load network'));
    } else if (screen === 'history') {
      renderHistory();
      Promise.all([loadAnalytics(), loadHistory()])
        .then(() => renderHistory())
        .catch((err) => toastError(err, 'Cannot load analytics'));
    } else if (screen === 'settings') {
      loadSettings().then(() => renderSettings()).catch((err) => toastError(err, 'Cannot load settings'));
    }
  }

  async function handleAction(action, element) {
    const id = element.dataset.id;
    switch (action) {
      case 'queue-open':
      case 'open-intel':
        openIntel(id);
        break;
      case 'queue-dispatch':
        await retryDispatch(id);
        break;
      case 'open-trip': {
        const dispatch = state.dispatches.find((d) => d.id === id);
        if (dispatch) {
          selectDispatch(id, true);
          openIntel(dispatch.emergencyId);
        }
        break;
      }
      case 'select-dispatch':
        selectDispatch(id, true);
        break;
      case 'fit-route':
        fitRoute();
        break;
      case 'fit-dispatch': {
        const dispatch = state.dispatches.find((d) => d.id === id);
        if (dispatch) selectDispatch(id, true);
        else toast('Trip not active', 'This dispatch is no longer in the active set.', 'warn');
        break;
      }
      case 'complete-trip':
        openModal('Complete this trip?', `
          <p>Manual completion is allowed only after the emergency has reached ARRIVED_AT_HOSPITAL.</p>
          <p class="micro-copy" style="margin-top:8px">The ambulance is released and the emergency is marked COMPLETED. The audit trail is retained.</p>`,
          `<button class="btn primary small" data-act="confirm-complete" data-id="${esc(id)}">Complete trip</button>
           <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
        break;
      case 'confirm-complete':
        await completeTrip(id);
        break;
      case 'recalc':
        await recalculateRoutes();
        break;
      case 'set-paused':
        await setSimulationPaused(element.dataset.paused === 'true');
        break;
      case 'edit-base': {
        const amb = state.ambulances.find((a) => a.id === id);
        if (!amb) break;
        openModal('Edit base · ' + amb.id, `
          <label class="field"><span>Base label</span><input id="modalBaseLabel" value="${esc(amb.baseLocation)}"></label>
          <div class="form-grid" style="padding:0">
            <label class="field"><span>Latitude</span><input id="modalBaseLat" type="number" step="0.0000001" value="${esc(amb.baseLat)}"></label>
            <label class="field"><span>Longitude</span><input id="modalBaseLon" type="number" step="0.0000001" value="${esc(amb.baseLon)}"></label>
          </div>
          <p class="micro-copy">Base coordinates must fall inside the operating area. This is a demonstration position, not a live station.</p>`,
          `<button class="btn primary small" data-act="modal-save-base" data-id="${esc(amb.id)}">Save base</button>
           <button class="btn ghost small" data-act="modal-close">Cancel</button>`);
        break;
      }
      case 'save-base':
        await saveAmbulanceBase(id);
        break;
      case 'modal-save-base': {
        const label = $('#modalBaseLabel');
        const lat = $('#modalBaseLat');
        const lon = $('#modalBaseLon');
        const body = { baseLocation: label.value.trim(), lat: Number(lat.value), lon: Number(lon.value) };
        if (!Number.isFinite(body.lat) || !Number.isFinite(body.lon)) {
          toast('Invalid base', 'Latitude and longitude must be numbers.', 'warn');
          break;
        }
        try {
          await api('/ambulances/' + encodeURIComponent(id) + '/base', { method: 'PUT', body });
          closeModal();
          toast('Base updated', id + ' now starts from the configured point.');
          await refreshAfterChange();
        } catch (err) {
          toastError(err, 'Base update rejected');
        }
        break;
      }
      case 'amb-map': {
        const amb = state.ambulances.find((a) => a.id === id);
        if (!amb) break;
        switchScreen('live');
        setTimeout(() => {
          if (opsMap) opsMap.flyTo([amb.currentLat, amb.currentLon], 16, { duration: 0.8 });
        }, 120);
        break;
      }
      case 'edit-hospital':
        openHospitalEdit(id);
        break;
      case 'save-hospital':
        await saveHospital(id);
        break;
      case 'save-config-hospital':
        await saveConfigHospital(id);
        break;
      case 'hospital-map': {
        const hosp = state.hospitals.find((h) => h.id === id);
        if (!hosp || hosp.lat == null) break;
        switchScreen('live');
        setTimeout(() => {
          if (opsMap) opsMap.flyTo([hosp.lat, hosp.lon], 16, { duration: 0.8 });
        }, 120);
        break;
      }
      case 'bed': {
        const hospital = state.hospitals.find((h) => h.id === id);
        if (!hospital) break;
        const delta = Number(element.dataset.delta || 0);
        const next = Math.max(0, Math.min(num(hospital.totalBeds), num(hospital.occupiedBeds) + delta));
        try {
          await api('/hospitals/' + encodeURIComponent(id) + '/beds', {
            method: 'POST', body: { totalBeds: num(hospital.totalBeds), occupiedBeds: next }
          });
          toast('Demo occupancy updated', hospital.name + ': ' + next + ' / ' + hospital.totalBeds + ' occupied.');
          await refreshAfterChange();
          renderHospitals();
        } catch (err) {
          toastError(err, 'Bed update rejected');
        }
        break;
      }
      case 'toggle-hospital': {
        const operational = element.dataset.operational === 'true';
        try {
          await api('/hospitals/' + encodeURIComponent(id) + '/operational', {
            method: 'POST', body: { operational }
          });
          toast('Hospital ' + (operational ? 'opened' : 'closed'), 'Routing eligibility updated for automatic dispatch.');
          await refreshAfterChange();
          renderHospitals();
        } catch (err) {
          toastError(err, 'Hospital status rejected');
        }
        break;
      }
      case 'preview-edge':
        previewEdge(id);
        break;
      case 'close-edge':
        openClosureModal(id, true);
        break;
      case 'reopen-edge':
        await setClosure(id, false, 'Reopened by operator');
        break;
      case 'confirm-closure': {
        const reasonInput = $('#closureReason');
        const reason = reasonInput && reasonInput.value.trim() ? reasonInput.value.trim() : 'SIMULATED road closure';
        closeModal();
        await setClosure(id, true, reason);
        break;
      }
      case 'history-detail':
        await showEmergencyModal(id);
        break;
      case 'confirm-reset':
        await resetDemo(element.dataset.seed === 'true');
        break;
      case 'confirm-import':
        await performImport(element.dataset.mode || 'cache');
        break;
      case 'modal-close':
        closeModal();
        break;
    }
  }

  function bindEvents() {
    $$('.nav-item').forEach((button) => {
      button.addEventListener('click', () => switchScreen(button.dataset.screen));
    });

    document.addEventListener('click', (event) => {
      const element = event.target.closest('[data-act]');
      if (!element) return;
      event.preventDefault();
      handleAction(element.dataset.act, element).catch((err) => toastError(err));
    });

    document.addEventListener('change', (event) => {
      const target = event.target;
      if (target.matches('[data-traffic-edge]')) {
        setTraffic(target.dataset.trafficEdge, Number(target.value));
      } else if (target.matches('[data-availability]')) {
        const id = target.dataset.availability;
        api('/ambulances/' + encodeURIComponent(id) + '/availability', {
          method: 'POST', body: { available: target.checked }
        }).then(() => {
          toast('Availability changed', id + ' is now ' + (target.checked ? 'AVAILABLE' : 'OUT OF SERVICE') + '.');
          return refreshAfterChange();
        }).then(() => { if (state.screen === 'ambulances') renderAmbulances(); })
          .catch((err) => toastError(err, 'Availability rejected'));
      } else if (target.matches('[data-layer]')) {
        state.layers[target.dataset.layer] = target.checked;
        applyLayerVisibility();
      } else if (target.id === 'simSpeed') {
        setSimulationSpeed(target.value);
      } else if (target.id === 'intelEmergencySelect') {
        state.intelEmergencyId = target.value || null;
        state.intelDetail = null;
        if (state.intelEmergencyId) loadIntel(state.intelEmergencyId);
        else renderIntelligence();
      }
    });

    $$('[data-mapstyle]').forEach((button) => {
      button.addEventListener('click', () => setMapStyle(button.dataset.mapstyle));
    });

    $('#zoomInBtn').addEventListener('click', () => opsMap && opsMap.zoomIn());
    $('#zoomOutBtn').addEventListener('click', () => opsMap && opsMap.zoomOut());
    $('#resetViewBtn').addEventListener('click', () => opsMap && opsMap.setView(ERODE_CENTER, DEFAULT_ZOOM));
    $('#fitRouteBtn').addEventListener('click', () => fitRoute());
    $('#refreshBtn').addEventListener('click', manualRefresh);
    $('#topSimBtn').addEventListener('click', togglePause);
    $('#simPauseBtn').addEventListener('click', togglePause);

    $('#gpsBtn').addEventListener('click', useBrowserGps);
    $('#pickMapBtn').addEventListener('click', () => {
      $('#pickMapBtn').classList.add('active');
      toast('Map pick active', 'Click anywhere inside the Erode operating area.');
    });
    $('#intakeForm').addEventListener('submit', submitIntake);

    $('#openIntelBtn').addEventListener('click', () => {
      const d = getSelectedDispatch();
      if (d) openIntel(d.emergencyId);
      else if (state.emergencies.length) openIntel(state.emergencies[0].id);
      else switchScreen('intelligence');
    });
    $('#intelRefresh').addEventListener('click', () => {
      if (state.intelEmergencyId) loadIntel(state.intelEmergencyId);
      else ensureIntel();
    });
    $('#manualToggle').addEventListener('click', () => {
      state.manualOpen = !state.manualOpen;
      const btn = $('#manualToggle');
      btn.textContent = state.manualOpen ? 'Hide manual override' : 'Manual override…';
      updateManualPanel(state.intelDetail);
    });
    $('#manualForm').addEventListener('submit', submitManual);

    $('#roadSearchBtn').addEventListener('click', () => searchRoads($('#roadSearch').value.trim()));
    $('#roadSearch').addEventListener('keydown', (event) => {
      if (event.key === 'Enter') { event.preventDefault(); searchRoads($('#roadSearch').value.trim()); }
    });
    $('#recalcRoutes').addEventListener('click', recalculateRoutes);

    $('#areaForm').addEventListener('submit', saveAreaSettings);
    $('#simForm').addEventListener('submit', saveSimulationSettings);
    $('#resetDemoBtn').addEventListener('click', confirmReset);
    $$('[data-import]').forEach((button) => {
      button.addEventListener('click', () => importOsm(button.dataset.import));
    });

    $('#modalClose').addEventListener('click', closeModal);
    $('#modalBackdrop').addEventListener('click', (event) => {
      if (event.target === $('#modalBackdrop')) closeModal();
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') closeModal();
    });
  }

  /* --------------------------------------------------------------- clock */

  function updateClock() {
    const time = $('#clockTime');
    const date = $('#clockDate');
    if (!time || !date) return;
    const now = new Date();
    time.textContent = now.toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour12: false });
    date.textContent = now.toLocaleDateString('en-IN', {
      timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric'
    }).toUpperCase() + ' · IST';
  }

  /* -------------------------------------------------------------- polling */

  async function pollCore() {
    if (state.polling || state.busy || state.mutating || document.hidden) return;
    state.polling = true;
    try {
      await loadCore();
      renderGlobal();
      if (state.screen === 'live') {
        renderLive();
      } else if (state.screen === 'ambulances') {
        renderAmbulances();
      } else if (state.screen === 'intelligence') {
        renderIntelSelect();
        if (!state.manualOpen && state.intelEmergencyId && Date.now() - state.intelFetchedAt > 4500) {
          await loadIntel(state.intelEmergencyId, true);
        }
      }
    } catch (err) {
      state.apiError = true;
      renderGlobal();
    } finally {
      state.polling = false;
    }
  }

  async function slowRefresh() {
    if (state.busy || state.mutating || document.hidden) return;
    try {
      await Promise.all([loadNetwork(), loadAnalytics()]);
      renderGlobal();
      if (state.screen === 'live') renderLiveKpis();
      if (state.screen === 'network') renderNetwork();
      if (state.screen === 'history') { await loadHistory(); renderHistory(); }
    } catch (err) {
      /* The fast poll surfaces connectivity errors. */
    }
  }

  /* ---------------------------------------------------------------- start */

  async function init() {
    initMaps();
    bindEvents();
    updateClock();
    setInterval(updateClock, 1000);
    renderIntakeReadout();
    try {
      await loadCore();
      await Promise.all([loadNetwork(), loadAnalytics(), loadSettings()]);
      renderGlobal();
      renderCurrentScreen();
      drawOpsMap();
    } catch (err) {
      toastError(err, 'UyirPath could not load');
      renderGlobal();
    }
    setInterval(pollCore, 2500);
    setInterval(slowRefresh, 20000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

