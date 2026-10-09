// PhonePilote — espace admin (lecture seule).
// Sécurité : la vraie barrière est côté Supabase (RLS + public.is_admin()). Cette page n'a que la clé anon
// publique ; un compte non admin ne reçoit aucune donnée des autres utilisateurs.
"use strict";

const CFG = window.PP_CONFIG || {};
const URL_ = String(CFG.supabaseUrl || "").replace(/\/+$/, "");
const KEY = CFG.anonKey || "";
const DOMAIN = CFG.accountDomain && !CFG.accountDomain.startsWith("__") ? CFG.accountDomain : "phonepilote.xydhub.tech";
const CONFIGURED = URL_.startsWith("https://") && KEY && !KEY.startsWith("__");

const IDLE_MS = 30 * 60 * 1000; // déconnexion après 30 min d'inactivité
const STORE = "pp_admin_session";  // sessionStorage : effacé à la fermeture de l'onglet

const $ = (id) => document.getElementById(id);
let session = null;
let data = { users: [], devices: [], commands: [], locations: [], versions: [] };
let tab = "devices";
let idleTimer = null;

// ---------------------------------------------------------------- Utilitaires

/** Même normalisation que l'app : « 01 97 00 00 00 » → « 2290197000000 ». */
function normalizePhone(input) {
  let d = input.replace(/\D/g, "");
  if (d.startsWith("00")) d = d.slice(2);
  if (!d.startsWith("229") || d.length <= 10) d = "229" + d;
  return d.length >= 11 && d.length <= 13 ? d : null;
}

function prettyPhone(p) {
  if (!p || !p.startsWith("229")) return p || "";
  return "+229 " + p.slice(3).match(/.{1,2}/g).join(" ");
}

function fmtDate(v) {
  if (!v) return "—";
  const d = new Date(v);
  return isNaN(d) ? String(v) : d.toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" });
}

function ago(v) {
  if (!v) return "—";
  const s = (Date.now() - new Date(v).getTime()) / 1000;
  if (s < 60) return "à l'instant";
  if (s < 3600) return `il y a ${Math.floor(s / 60)} min`;
  if (s < 86400) return `il y a ${Math.floor(s / 3600)} h`;
  return `il y a ${Math.floor(s / 86400)} j`;
}

/** Construit un élément ; le texte passe toujours par textContent (pas d'injection HTML possible). */
function el(tag, props = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v == null ? "" : String(v);
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return e;
}

const pill = (ok, yes = "Oui", no = "Non") => el("span", { class: "pill " + (ok ? "ok" : "bad"), text: ok ? yes : no });

// ---------------------------------------------------------------- Supabase (REST)

async function api(path, { method = "GET", body, auth = true } = {}) {
  const headers = { apikey: KEY, "Content-Type": "application/json" };
  if (auth) {
    await ensureFresh();
    headers.Authorization = "Bearer " + session.access_token;
  }
  const r = await fetch(URL_ + path, { method, headers, body: body ? JSON.stringify(body) : undefined, cache: "no-store", credentials: "omit" });
  const text = await r.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* réponse non JSON */ }
  if (!r.ok) {
    const msg = (json && (json.msg || json.message || json.error_description || json.error)) || `Erreur ${r.status}`;
    const err = new Error(msg);
    err.status = r.status;
    throw err;
  }
  return json;
}

function saveSession(s) {
  session = s;
  try {
    if (s) sessionStorage.setItem(STORE, JSON.stringify(s));
    else sessionStorage.removeItem(STORE);
  } catch { /* stockage indisponible : session en mémoire seulement */ }
}

function toSession(j) {
  return {
    access_token: j.access_token,
    refresh_token: j.refresh_token,
    expires_at: j.expires_at ? j.expires_at * 1000 : Date.now() + (j.expires_in || 3600) * 1000,
    label: (j.user && (j.user.user_metadata?.name || prettyPhone(j.user.user_metadata?.phone) || j.user.email)) || "",
  };
}

async function ensureFresh() {
  if (!session) throw Object.assign(new Error("Non connecté"), { status: 401 });
  if (session.expires_at - 60000 > Date.now()) return;
  try {
    const j = await api("/auth/v1/token?grant_type=refresh_token", { method: "POST", body: { refresh_token: session.refresh_token }, auth: false });
    saveSession({ ...toSession(j), label: session.label });
  } catch (e) {
    saveSession(null);
    throw Object.assign(new Error("Session expirée"), { status: 401 });
  }
}

// ---------------------------------------------------------------- Connexion

async function login(ev) {
  ev.preventDefault();
  const ident = $("ident").value.trim();
  const pass = $("pass").value;
  $("loginErr").textContent = "";
  let email = ident;
  if (!ident.includes("@")) {
    const p = normalizePhone(ident);
    if (!p) { $("loginErr").textContent = "Numéro invalide."; return; }
    email = `${p}@${DOMAIN}`;
  }
  $("loginBtn").disabled = true;
  try {
    const j = await api("/auth/v1/token?grant_type=password", { method: "POST", body: { email, password: pass }, auth: false });
    saveSession(toSession(j));
    const ok = await api("/rest/v1/rpc/is_admin", { method: "POST", body: {} });
    if (ok !== true) {
      await logout(false);
      $("loginErr").textContent = "Ce compte n'a pas les droits administrateur.";
      return;
    }
    $("pass").value = "";
    await showApp();
  } catch (e) {
    $("loginErr").textContent = e.status === 400 ? "Identifiants incorrects." : e.message;
  } finally {
    $("loginBtn").disabled = false;
  }
}

async function logout(show = true) {
  const s = session;
  saveSession(null);
  data = { users: [], devices: [], commands: [], locations: [], versions: [] };
  $("table").replaceChildren();
  $("stats").replaceChildren();
  if (s) {
    fetch(URL_ + "/auth/v1/logout", { method: "POST", headers: { apikey: KEY, Authorization: "Bearer " + s.access_token } }).catch(() => {});
  }
  if (show) showLogin();
}

function showLogin(msg = "") {
  clearTimeout(idleTimer);
  $("app").hidden = true;
  $("login").hidden = false;
  $("loginErr").textContent = msg;
  $("ident").focus();
}

async function showApp() {
  $("login").hidden = true;
  $("app").hidden = false;
  $("who").textContent = session.label;
  resetIdle();
  await load();
}

function resetIdle() {
  clearTimeout(idleTimer);
  if (session) idleTimer = setTimeout(() => logout(true).then(() => showLogin("Déconnecté après 30 min d'inactivité.")), IDLE_MS);
}

// ---------------------------------------------------------------- Données

async function load() {
  $("appErr").textContent = "";
  $("refresh").disabled = true;
  try {
    const [users, devices, commands, locations, versions] = await Promise.all([
      api("/rest/v1/rpc/admin_users", { method: "POST", body: {} }),
      api("/rest/v1/devices?select=*&order=last_seen.desc&limit=2000"),
      api("/rest/v1/commands?select=*&order=created_at.desc&limit=500"),
      api("/rest/v1/locations?select=*&order=created_at.desc&limit=500"),
      api("/rest/v1/app_versions?select=*&order=version_code.desc&limit=50"),
    ]);
    data = { users: users || [], devices: devices || [], commands: commands || [], locations: locations || [], versions: versions || [] };
    renderStats();
    render();
  } catch (e) {
    if (e.status === 401) return showLogin("Session expirée, reconnectez-vous.");
    if (e.status === 403) { await logout(false); return showLogin("Accès refusé."); }
    $("appErr").textContent = e.message;
  } finally {
    $("refresh").disabled = false;
  }
}

function renderStats() {
  const day = Date.now() - 86400000;
  const d = data.devices;
  const items = [
    [data.users.length, "Comptes"],
    [d.length, "Téléphones"],
    [d.filter((x) => x.admin_active).length, "Protection active"],
    [d.filter((x) => new Date(x.last_seen) > day).length, "Vus < 24 h"],
    [data.commands.filter((x) => x.status === "pending" || x.status === "sent").length, "Commandes en cours"],
    [data.locations.filter((x) => new Date(x.created_at) > day).length, "Positions < 24 h"],
  ];
  $("stats").replaceChildren(...items.map(([n, l]) => el("div", { class: "glass stat" }, el("b", { text: n }), el("span", { text: l }))));
}

// ---------------------------------------------------------------- Tableaux

// Actions à distance (mode test uniquement). Le vrai contrôle d'accès est côté base :
// admin_send_command refuse si le téléphone n'est pas en test_mode, et n'est exécutable que par un admin.
function deviceActions(x) {
  const box = el("span", { class: "rowact" });
  const toggle = el("button", { class: "ghost", type: "button", "data-act": "test", "data-id": x.id,
    text: x.test_mode ? "Test : ON" : "Test : OFF", title: "Autoriser les actions à distance sur ce téléphone" });
  if (x.test_mode) toggle.classList.add("on");
  box.append(toggle);
  if (x.test_mode) {
    box.append(
      el("button", { class: "ghost", type: "button", "data-act": "locate", "data-id": x.id, text: "Localiser" }),
      el("button", { class: "ghost", type: "button", "data-act": "lock", "data-id": x.id, text: "Verrouiller" }),
    );
  }
  return box;
}

async function deviceAction(btn) {
  const id = btn.dataset.id;
  const act = btn.dataset.act;
  const dev = data.devices.find((d) => d.id === id);
  if (act === "lock" && !confirm("Verrouiller ce téléphone de test maintenant ?")) return;
  $("appNote").textContent = "";
  $("appErr").textContent = "";
  btn.disabled = true;
  const old = btn.textContent;
  btn.textContent = "…";
  try {
    if (act === "test") {
      await api("/rest/v1/rpc/admin_set_test_mode", { method: "POST", body: { device: id, enabled: !dev.test_mode } });
    } else {
      // La fonction vérifie les droits via la base, crée la commande puis envoie le push FCM.
      const r = await api("/functions/v1/command", { method: "POST", body: { device: id, action: act } });
      const what = act === "lock" ? "Verrouillage" : "Localisation";
      $("appNote").textContent = r && r.pushed
        ? `${what} envoyé au téléphone par push. Résultat dans l'onglet Commandes${act === "locate" ? " et Positions" : ""} (actualisez dans quelques secondes).`
        : `${what} enregistré, mais push non envoyé (${(r && r.push_error) || "raison inconnue"}). Le téléphone l'exécutera à l'ouverture de l'app.`;
    }
    await load();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = old;
    $("appErr").textContent = e.message;
  }
}

// null = version de l'app trop ancienne pour le signaler.
const screenLockPill = (v) => (v == null ? el("span", { class: "pill", text: "?" }) : pill(v, "Oui", "Aucun"));

function locationState(x) {
  if (!x.location_ok) return el("span", { class: "pill bad", text: "Refusée" });
  if (x.location_enabled === false) {
    return el("span", { class: "pill bad", text: "Coupée " + (x.location_off_since ? ago(x.location_off_since) : ""), title: "Interrupteur Localisation du téléphone désactivé" });
  }
  return pill(true, "Oui");
}

function lastPosition(l) {
  if (!l) return "—";
  return el("a", { href: `https://www.google.com/maps?q=${encodeURIComponent(l.lat + "," + l.lng)}`, target: "_blank", rel: "noopener noreferrer", text: ago(l.created_at) });
}

const owners = () => new Map(data.users.map((u) => [u.id, u]));
const devName = (id, devs) => { const x = devs.get(id); return x ? `${x.manufacturer} ${x.model}`.trim() || id.slice(0, 8) : id.slice(0, 8); };
const ownerLabel = (id, users) => { const u = users.get(id); return u ? (u.name ? `${u.name} · ${prettyPhone(u.phone)}` : prettyPhone(u.phone)) : id.slice(0, 8); };

function views() {
  const users = owners();
  const devs = new Map(data.devices.map((x) => [x.id, x]));
  // Dernière position connue par téléphone (data.locations est trié du plus récent au plus ancien).
  const lastPos = new Map();
  for (const l of data.locations) if (!lastPos.has(l.device_id)) lastPos.set(l.device_id, l);
  return {
    users: {
      rows: data.users,
      cols: ["Nom", "Numéro", "Téléphones", "Inscrit", "Dernière connexion", "Rôle"],
      row: (u) => [u.name || "—", prettyPhone(u.phone), u.devices, fmtDate(u.created_at), ago(u.last_sign_in_at),
        u.is_admin ? el("span", { class: "pill ok", text: "Admin" }) : "Utilisateur"],
      text: (u) => `${u.name} ${u.phone} ${u.id}`,
    },
    devices: {
      rows: data.devices,
      cols: ["Appareil", "Propriétaire", "IMEI", "Android", "App", "Admin", "Code écran", "Localisation", "Dernière position", "Batterie", "Secours", "Vu", "Actions"],
      row: (x) => [
        el("span", {}, el("b", { text: `${x.manufacturer} ${x.model}`.trim() || "—" }), el("br"), el("code", { class: "muted", text: x.id })),
        ownerLabel(x.owner_id, users), x.imei ? el("code", { text: x.imei }) : "—", `SDK ${x.android_sdk}`, x.app_version,
        pill(x.admin_active), screenLockPill(x.screen_lock_ok), locationState(x), lastPosition(lastPos.get(x.id)), pill(x.battery_ok),
        x.emergency_phone ? prettyPhone(x.emergency_phone) : "—", ago(x.last_seen),
        deviceActions(x),
      ],
      text: (x) => `${x.manufacturer} ${x.model} ${x.imei || ""} ${x.owner_name || ""} ${x.emergency_phone || ""} ${x.id} ${ownerLabel(x.owner_id, users)}`,
    },
    commands: {
      rows: data.commands,
      cols: ["Date", "Appareil", "Type", "Statut", "Terminée", "Résultat"],
      row: (c) => [fmtDate(c.created_at), devName(c.device_id, devs), c.kind,
        el("span", { class: "pill " + (c.status === "done" ? "ok" : c.status === "failed" ? "bad" : ""), text: c.status }),
        fmtDate(c.done_at), el("code", { text: c.result ? JSON.stringify(c.result) : "—" })],
      text: (c) => `${c.kind} ${c.status} ${devName(c.device_id, devs)} ${c.device_id}`,
    },
    locations: {
      rows: data.locations,
      cols: ["Date", "Appareil", "Origine", "Position", "Précision", "Carte"],
      row: (l) => [fmtDate(l.created_at), devName(l.device_id, devs), l.source === "periodic" ? "Automatique" : "Demandée",
        `${l.lat.toFixed(5)}, ${l.lng.toFixed(5)}`,
        l.accuracy_m != null ? `± ${Math.round(l.accuracy_m)} m` : "—",
        el("a", { href: `https://www.google.com/maps?q=${encodeURIComponent(l.lat + "," + l.lng)}`, target: "_blank", rel: "noopener noreferrer", text: "Ouvrir" })],
      text: (l) => `${devName(l.device_id, devs)} ${l.device_id}`,
    },
    versions: {
      rows: data.versions,
      cols: ["Code", "Version", "Minimum requis", "Date", "Notes"],
      row: (v) => [v.version_code, v.version_name, v.min_version_code, fmtDate(v.created_at), el("span", { class: "note", text: v.changelog || "—" })],
      text: (v) => `${v.version_name} ${v.version_code} ${v.changelog}`,
    },
  };
}

function render() {
  const v = views()[tab];
  const q = $("search").value.trim().toLowerCase();
  const rows = q ? v.rows.filter((r) => v.text(r).toLowerCase().includes(q)) : v.rows;
  $("count").textContent = `${rows.length} / ${v.rows.length}`;

  const head = el("thead", {}, el("tr", {}, ...v.cols.map((c) => el("th", { text: c }))));
  const body = el("tbody");
  if (!rows.length) {
    body.append(el("tr", {}, el("td", { class: "empty", colspan: String(v.cols.length), text: "Aucune donnée" })));
  }
  for (const r of rows) {
    const cells = v.row(r).map((c) => el("td", {}, c instanceof Node ? c : String(c ?? "—")));
    body.append(el("tr", {}, ...cells));
  }
  $("table").replaceChildren(head, body);
}

// ---------------------------------------------------------------- Démarrage

function init() {
  $("loginForm").addEventListener("submit", login);
  $("logout").addEventListener("click", () => logout(true));
  $("refresh").addEventListener("click", load);
  $("search").addEventListener("input", render);
  $("table").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-act]");
    if (b && !b.disabled) deviceAction(b);
  });
  $("tabs").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-tab]");
    if (!b) return;
    tab = b.dataset.tab;
    for (const x of $("tabs").children) x.classList.toggle("on", x === b);
    $("search").value = "";
    render();
  });
  for (const ev of ["click", "keydown", "mousemove", "touchstart", "scroll"]) {
    document.addEventListener(ev, resetIdle, { passive: true });
  }

  if (!CONFIGURED) {
    showLogin("Supabase n'est pas configuré pour ce site.");
    $("loginBtn").disabled = true;
    return;
  }
  try { session = JSON.parse(sessionStorage.getItem(STORE) || "null"); } catch { session = null; }
  if (session) {
    api("/rest/v1/rpc/is_admin", { method: "POST", body: {} })
      .then((ok) => (ok === true ? showApp() : logout(true)))
      .catch(() => logout(true));
  } else {
    showLogin();
  }
}

init();
