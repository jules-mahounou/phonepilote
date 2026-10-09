// PhonePilote — envoi d'une commande à distance (Edge Function Supabase).
//
// 1. Appelle admin_send_command AVEC LE JETON DE L'APPELANT : c'est la base qui vérifie
//    qu'il est admin et que le téléphone est en mode test (garde-fou agréments).
// 2. Envoie le push FCM (HTTP v1) au téléphone avec la clé de compte de service.
//    Google le garde 28 jours (maximum) si le téléphone est éteint ou hors ligne.
//    Si le push échoue, la commande reste en attente : l'app la récupère à sa prochaine ouverture.
//
// Secret requis : FCM_SERVICE_ACCOUNT (contenu JSON de la clé de compte de service Firebase).
// SUPABASE_URL, SUPABASE_ANON_KEY et SUPABASE_SERVICE_ROLE_KEY sont fournis par Supabase.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ALLOWED_ORIGIN = Deno.env.get("ADMIN_ORIGIN") ?? "https://phonepilote.xydhub.tech";

const cors = {
  "Access-Control-Allow-Origin": ALLOWED_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

// ---------------------------------------------------------------- Jeton Google (OAuth2, JWT RS256)

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
let cachedToken: { value: string; exp: number } | null = null;

const b64url = (data: ArrayBuffer | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function googleToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.value;

  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${b64url(sig)}`;

  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const j = await r.json();
  if (!r.ok) throw new Error(`OAuth Google : ${j.error_description ?? j.error ?? r.status}`);
  cachedToken = { value: j.access_token, exp: now + (j.expires_in ?? 3600) };
  return cachedToken.value;
}

async function sendPush(fcmToken: string, data: Record<string, string>): Promise<string | null> {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!raw) return "FCM_SERVICE_ACCOUNT absent";
  const sa = JSON.parse(raw) as ServiceAccount;
  const token = await googleToken(sa);
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ message: { token: fcmToken, data, android: { priority: "HIGH", ttl: "2419200s" } } }),
  });
  if (r.ok) return null;
  const j = await r.json().catch(() => ({}));
  return `FCM ${r.status} : ${j?.error?.message ?? "erreur"}`;
}

// ---------------------------------------------------------------- Requête

async function rest(path: string, key: string, auth: string, init: RequestInit = {}) {
  const headers: Record<string, string> = { apikey: key, "Content-Type": "application/json", ...(init.headers as Record<string, string> ?? {}) };
  if (auth) headers.Authorization = auth;
  const r = await fetch(`${SUPABASE_URL}${path}`, { ...init, headers });
  const text = await r.text();
  const body = text ? JSON.parse(text) : null;
  return { ok: r.ok, status: r.status, body };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json(405, { error: "Méthode non autorisée" });

  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return json(401, { error: "Non connecté" });

  let device = "", action = "";
  try {
    ({ device, action } = await req.json());
  } catch {
    return json(400, { error: "Requête invalide" });
  }
  if (!/^[0-9a-f-]{36}$/i.test(device ?? "")) return json(400, { error: "Téléphone invalide" });

  // 1. Contrôles (admin + mode test) et création de la commande, avec le jeton de l'appelant.
  const created = await rest("/rest/v1/rpc/admin_send_command", ANON_KEY, auth, {
    method: "POST",
    body: JSON.stringify({ device, action }),
  });
  if (!created.ok) {
    return json(created.status === 401 ? 401 : 403, { error: created.body?.message ?? "Action refusée" });
  }
  const commandId = created.body as string;

  // 2. Push FCM (jeton du téléphone lu avec la clé service, jamais exposée au navigateur).
  // Les clés « sb_secret_… » ne sont pas des JWT : uniquement l'en-tête apikey.
  const svc = SERVICE_KEY.startsWith("eyJ") ? `Bearer ${SERVICE_KEY}` : "";
  const dev = await rest(`/rest/v1/devices?select=fcm_token&id=eq.${device}`, SERVICE_KEY, svc);
  const fcmToken = dev.body?.[0]?.fcm_token as string | undefined;

  let pushError: string | null = "Aucun jeton push pour ce téléphone";
  if (fcmToken) {
    try {
      pushError = await sendPush(fcmToken, { cmd: action, id: commandId });
    } catch (e) {
      pushError = String((e as Error).message ?? e);
    }
  }
  if (!pushError) {
    await rest(`/rest/v1/commands?id=eq.${commandId}&status=eq.pending`, SERVICE_KEY, svc, {
      method: "PATCH",
      headers: { Prefer: "return=minimal" },
      body: JSON.stringify({ status: "sent" }),
    });
  }

  return json(200, { id: commandId, pushed: !pushError, push_error: pushError });
});
