// DYLAN VPN : serveur d'inscription et de session (Cloudflare Worker).
// Le jeton GitHub reste ICI, dans les secrets du Worker, jamais dans l'APK.
//
// Secrets (wrangler secret put NOM) : GH_TOKEN, PEPPER, ADMIN_KEY
// Variables (wrangler.toml)         : GH_OWNER, GH_REPO, GH_BRANCH
//
// Un fichier par utilisateur : chat/users/<nom>.json dans la branche GH_BRANCH.
// Créer un fichier qui existe déjà échoue côté GitHub : l'unicité du nom est donc atomique.

const NAME_RE = /^[a-z0-9_]{3,20}$/;
const enc = new TextEncoder();
const dec = new TextDecoder();

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

const b64 = (bytes) => {
  const u = new Uint8Array(bytes);
  let out = "";
  for (let i = 0; i < u.length; i += 0x8000) out += String.fromCharCode(...u.subarray(i, i + 0x8000));
  return btoa(out);
};
const b64u = (bytes) => b64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const unb64u = (s) => unb64(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
const randomB64 = (n) => b64(crypto.getRandomValues(new Uint8Array(n)));

async function pbkdf2(password, salt) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  // 100 000 itérations : maximum accepté par Cloudflare Workers
  return b64(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 100000 }, key, 256));
}

async function hmacHex(secret, text) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(await crypto.subtle.sign("HMAC", key, enc.encode(text)));
}

async function hmacRaw(keyBytes, text) {
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(text)));
}

async function sha256Hex(text) {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// ----- accès GitHub -----

function gh(env, path, init = {}) {
  return fetch(`https://api.github.com/repos/${env.GH_OWNER}/${env.GH_REPO}/contents/${path}`, {
    ...init,
    headers: {
      authorization: `Bearer ${env.GH_TOKEN}`,
      accept: "application/vnd.github+json",
      "user-agent": "dylan-vpn-server",
      "content-type": "application/json",
    },
  });
}

async function getUser(env, name) {
  const r = await gh(env, `chat/users/${name}.json?ref=${env.GH_BRANCH}`);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("GitHub " + r.status);
  const f = await r.json();
  return { sha: f.sha, data: JSON.parse(dec.decode(unb64(f.content.replace(/\n/g, "")))) };
}

// sha absent = création (échoue si le fichier existe) ; sha présent = mise à jour
async function putUser(env, name, data, sha) {
  const body = {
    message: (sha ? "maj " : "inscription ") + name,
    content: b64(enc.encode(JSON.stringify(data))),
    branch: env.GH_BRANCH,
  };
  if (sha) body.sha = sha;
  const r = await gh(env, `chat/users/${name}.json`, { method: "PUT", body: JSON.stringify(body) });
  if (r.status === 422 || r.status === 409) return "conflict";
  if (!r.ok) throw new Error("GitHub " + r.status);
  return "ok";
}

// ----- fichier des comptes de l'app (vpn/users.txt, format « login|catégorie|sel|enveloppe ») -----

const USERS_PATH = "vpn/users.txt";
const RESERVED = new Set(["admin"]);

async function getUsersFile(env) {
  const r = await gh(env, `${USERS_PATH}?ref=${env.GH_BRANCH}`);
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("GitHub " + r.status);
  const f = await r.json();
  return { sha: f.sha, text: dec.decode(unb64(f.content.replace(/\n/g, ""))) };
}

const hasLogin = (text, name) => text.split("\n").some((l) => l.split("|")[0] === name);

// Même format que Keyring.createUserLine de l'app. PBKDF2 limité à 100 000 itérations ici : le nombre
// est écrit dans le champ sel (« sel~100000 »), que l'app sait lire.
async function basicLine(env, name, password) {
  const iterations = 100000;
  const keys = { basic: b64u(unb64u(env.BASIC_KEY)) };
  if (env.USERS_KEY) keys.personal = b64u(await hmacRaw(unb64u(env.USERS_KEY), "dylan-personal|" + name));
  const payload = enc.encode(JSON.stringify({ c: "basic", k: keys }));
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const material = await crypto.subtle.importKey("raw", enc.encode(name + ":" + password), "PBKDF2", false, ["deriveKey"]);
  const aes = await crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"]
  );
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv, additionalData: enc.encode(`dylan-user|${name}|basic`), tagLength: 128 },
      aes,
      payload
    )
  );
  const sealed = new Uint8Array(iv.length + ct.length);
  sealed.set(iv);
  sealed.set(ct, iv.length);
  return `${name}|basic|${b64u(salt)}~${iterations}|${b64u(sealed)}`;
}

// Ajoute la ligne en réessayant si une autre inscription écrit en même temps.
async function appendUserLine(env, name, line) {
  for (let i = 0; i < 6; i++) {
    const f = await getUsersFile(env);
    if (!f) return "noinit";
    if (hasLogin(f.text, name)) return "taken";
    const text = f.text.replace(/\n*$/, "\n") + line + "\n";
    const r = await gh(env, USERS_PATH, {
      method: "PUT",
      body: JSON.stringify({ message: "Inscription " + name, content: b64(enc.encode(text)), branch: env.GH_BRANCH, sha: f.sha }),
    });
    if (r.ok) return "ok";
    if (r.status !== 409 && r.status !== 422) throw new Error("GitHub " + r.status);
    await new Promise((resolve) => setTimeout(resolve, 250 * (i + 1)));
  }
  return "busy";
}

async function deleteUser(env, name) {
  const found = await getUser(env, name);
  if (!found) return;
  await gh(env, `chat/users/${name}.json`, {
    method: "DELETE",
    body: JSON.stringify({ message: "annulation " + name, sha: found.sha, branch: env.GH_BRANCH }),
  });
}

// ----- routes -----

async function check(env, body) {
  const name = String(body.name || "").trim().toLowerCase();
  if (!NAME_RE.test(name)) return json({ available: false, reason: "3 à 20 caractères : a-z, 0-9, _" });
  if (RESERVED.has(name)) return json({ available: false, reason: "Ce nom est réservé" });
  const users = await getUsersFile(env);
  if (users && hasLogin(users.text, name)) return json({ available: false, reason: "Ce nom est déjà pris" });
  return json({ available: (await getUser(env, name)) === null });
}

async function register(env, body) {
  const name = String(body.name || "").trim().toLowerCase();
  const password = String(body.password || "");
  const phone = String(body.phone || "").replace(/\D/g, "");
  const deviceId = String(body.deviceId || "");
  if (!NAME_RE.test(name)) return json({ error: "Nom invalide" }, 400);
  if (password.length < 8) return json({ error: "Mot de passe : 8 caractères minimum" }, 400);
  if (phone.length < 8 || phone.length > 15) return json({ error: "Numéro invalide" }, 400);
  if (deviceId.length < 16) return json({ error: "Identifiant d'appareil invalide" }, 400);

  if (!env.BASIC_KEY) return json({ error: "Serveur non configuré" }, 503);
  if (RESERVED.has(name)) return json({ error: "Ce nom est réservé" }, 409);
  const existing = await getUsersFile(env);
  if (!existing) return json({ error: "Le dépôt n'est pas encore initialisé" }, 503);
  if (hasLogin(existing.text, name)) return json({ error: "Ce nom est déjà pris" }, 409);

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const record = {
    name,
    salt: b64(salt),
    hash: await pbkdf2(password, salt),
    // le numéro n'est jamais stocké en clair : empreinte + fin masquée pour l'admin
    phoneHash: await hmacHex(env.PEPPER, phone),
    phoneMasked: "•••• " + phone.slice(-2),
    deviceId,
    createdAt: Date.now(),
    lastLogin: 0,
    session: null,
    fails: 0,
    lockUntil: 0,
  };
  const result = await putUser(env, name, record, null);
  if (result === "conflict") return json({ error: "Ce nom est déjà pris" }, 409);

  // le nom est à toi : on donne maintenant l'accès au VPN en ajoutant ta ligne dans vpn/users.txt
  let outcome;
  try {
    outcome = await appendUserLine(env, name, await basicLine(env, name, password));
  } catch (e) {
    outcome = "error";
  }
  if (outcome !== "ok") {
    await deleteUser(env, name).catch(() => {});
    if (outcome === "taken") return json({ error: "Ce nom est déjà pris" }, 409);
    return json({ error: "Inscription impossible pour le moment, réessaie" }, 503);
  }
  return json({ ok: true });
}

async function login(env, body) {
  const name = String(body.name || "").trim().toLowerCase();
  const password = String(body.password || "");
  const deviceId = String(body.deviceId || "");
  if (!NAME_RE.test(name)) return json({ error: "Identifiants incorrects" }, 401);

  const found = await getUser(env, name);
  if (!found) return json({ error: "Identifiants incorrects" }, 401);
  const u = found.data;

  if (u.lockUntil && Date.now() < u.lockUntil) {
    return json({ error: "Compte verrouillé quelques minutes après trop d'essais" }, 429);
  }
  const hash = await pbkdf2(password, unb64(u.salt));
  if (!safeEqual(hash, u.hash)) {
    u.fails = (u.fails || 0) + 1;
    if (u.fails >= 5) {
      u.lockUntil = Date.now() + 10 * 60 * 1000;
      u.fails = 0;
    }
    await putUser(env, name, u, found.sha);
    return json({ error: "Identifiants incorrects" }, 401);
  }

  // une seule session par compte : ce jeton remplace le précédent, l'ancien appareil sera déconnecté
  const token = randomB64(32);
  u.session = { tokenHash: await sha256Hex(token), deviceId, at: Date.now() };
  u.lastLogin = Date.now();
  u.fails = 0;
  u.lockUntil = 0;
  if ((await putUser(env, name, u, found.sha)) === "conflict") {
    return json({ error: "Réessaie dans un instant" }, 409);
  }
  return json({ ok: true, token });
}

// L'app appelle ceci régulièrement : si un autre appareil s'est connecté, valid = false.
async function session(env, body) {
  const name = String(body.name || "").trim().toLowerCase();
  const found = NAME_RE.test(name) ? await getUser(env, name) : null;
  const s = found && found.data.session;
  const valid = !!s && safeEqual(s.tokenHash, await sha256Hex(String(body.token || "")));
  return json({ valid });
}

// Admin : métadonnées seulement (jamais le mot de passe, jamais le numéro complet).
async function adminUser(env, request, body) {
  const key = request.headers.get("x-admin-key") || "";
  if (!env.ADMIN_KEY || !safeEqual(key, env.ADMIN_KEY)) return json({ error: "Interdit" }, 403);
  const name = String(body.name || "").trim().toLowerCase();
  const found = NAME_RE.test(name) ? await getUser(env, name) : null;
  if (!found) return json({ error: "Introuvable" }, 404);
  const u = found.data;
  return json({
    name: u.name,
    phoneMasked: u.phoneMasked,
    deviceId: u.deviceId,
    createdAt: u.createdAt,
    lastLogin: u.lastLogin,
    online: !!u.session,
    sessionDevice: u.session ? u.session.deviceId : null,
  });
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "POST seulement" }, 405);
    let body;
    try {
      body = await request.json();
    } catch (e) {
      return json({ error: "JSON invalide" }, 400);
    }
    try {
      switch (new URL(request.url).pathname) {
        case "/check": return await check(env, body);
        case "/register": return await register(env, body);
        case "/login": return await login(env, body);
        case "/session": return await session(env, body);
        case "/admin/user": return await adminUser(env, request, body);
        default: return json({ error: "Route inconnue" }, 404);
      }
    } catch (e) {
      return json({ error: "Erreur serveur" }, 500);
    }
  },
};
