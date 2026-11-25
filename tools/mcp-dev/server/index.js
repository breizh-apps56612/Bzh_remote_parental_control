#!/usr/bin/env node
// Télécommande — outils de développement (MCP local, stdio).
// Relie Claude à l'appli Box de la TV (port 7363, TLS mutuel, jumelage par code) et à la télécommande
// Android TV de la box (port 6466/6467, jumelage par code), depuis un PC du même réseau.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const tls = require('tls');
const crypto = require('crypto');
const forge = require('node-forge');

const DIR = path.join(os.homedir(), '.telecommande-dev');
const STATE = path.join(DIR, 'state.json');
let state = {};
try { state = JSON.parse(fs.readFileSync(STATE, 'utf8')); } catch (_) { state = {}; }
function save() { fs.mkdirSync(DIR, { recursive: true }); fs.writeFileSync(STATE, JSON.stringify(state, null, 1)); }

// ---------- Identité du PC (clé RSA + certificat auto-signé) ----------
function identity() {
  if (state.key && state.cert) return state;
  const kp = forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 });
  const cert = forge.pki.createCertificate();
  cert.publicKey = kp.publicKey;
  cert.serialNumber = '01' + crypto.randomBytes(8).toString('hex');
  cert.validity.notBefore = new Date(Date.now() - 86400000);
  cert.validity.notAfter = new Date(Date.now() + 20 * 365 * 86400000);
  const attrs = [{ name: 'commonName', value: 'atvremote' }];
  cert.setSubject(attrs); cert.setIssuer(attrs);
  cert.sign(kp.privateKey, forge.md.sha256.create());
  state.key = forge.pki.privateKeyToPem(kp.privateKey);
  state.cert = forge.pki.certificateToPem(cert);
  save();
  return state;
}
function certDer() { return Buffer.from(forge.asn1.toDer(forge.pki.certificateToAsn1(forge.pki.certificateFromPem(identity().cert))).getBytes(), 'binary'); }
const sha256hex = (b) => crypto.createHash('sha256').update(b).digest('hex');
const myFp = () => sha256hex(certDer());

function host(h) {
  const v = h || state.host;
  if (!v) throw new Error("Adresse de la box inconnue : lancez d'abord tv_find (ou donnez host).");
  return v;
}

function tlsOpen(h, port, timeout = 6000) {
  const id = identity();
  return new Promise((resolve, reject) => {
    const s = tls.connect({ host: h, port, key: id.key, cert: id.cert, rejectUnauthorized: false, timeout });
    const fail = (e) => { s.destroy(); reject(e); };
    s.once('secureConnect', () => { s.setTimeout(0); s.removeListener('error', fail); resolve(s); });
    s.once('error', fail);
    s.once('timeout', () => fail(new Error(`${h}:${port} ne répond pas`)));
  });
}

// ---------- Appli Box (JSON ligne par ligne, port 7363) ----------
async function boxCall(cmd, params = {}, opts = {}) {
  const h = host(opts.host);
  const s = await tlsOpen(h, 7363);
  const boxFp = sha256hex(s.getPeerCertificate(true).raw);
  const pinned = state.boxes && state.boxes[h];
  if (pinned && pinned !== boxFp && !opts.pairing) { s.destroy(); throw new Error('Sécurité : la box ne présente pas l\'identité attendue (réinstallée ? refaites tv_pair_start).'); }
  return new Promise((resolve, reject) => {
    let buf = '';
    const timer = setTimeout(() => { s.destroy(); reject(new Error('La box ne répond pas (délai dépassé)')); }, opts.timeout || 30000);
    s.on('data', (d) => {
      buf += d.toString('utf8');
      const i = buf.indexOf('\n');
      if (i >= 0) { clearTimeout(timer); s.end(); try { const r = JSON.parse(buf.slice(0, i)); r._boxFp = boxFp; resolve(r); } catch (e) { reject(e); } }
    });
    s.on('error', (e) => { clearTimeout(timer); reject(e); });
    s.on('end', () => { if (!buf.includes('\n')) { clearTimeout(timer); reject(new Error('La box a fermé la connexion')); } });
    s.write(JSON.stringify(Object.assign({}, params, { id: crypto.randomBytes(4).toString('hex'), cmd })) + '\n');
  });
}
async function box(cmd, params) {
  const r = await boxCall(cmd, params);
  if (!r.ok) throw new Error(r.error || 'Refusé par la box');
  delete r._boxFp; delete r.id;
  return r;
}

async function findBoxes() {
  const subnets = new Set();
  for (const list of Object.values(os.networkInterfaces())) for (const a of list || [])
    if (a.family === 'IPv4' && !a.internal && /^(10|172|192)\./.test(a.address)) subnets.add(a.address.split('.').slice(0, 3).join('.'));
  const hosts = [];
  for (const sn of subnets) for (let i = 1; i < 255; i++) hosts.push(`${sn}.${i}`);
  const found = [];
  const probe = (h, port) => new Promise((res) => {
    const s = net.connect({ host: h, port }); s.setTimeout(500);
    const done = (ok) => { s.destroy(); res(ok); };
    s.once('connect', () => done(true)); s.once('timeout', () => done(false)); s.once('error', () => done(false));
  });
  let idx = 0;
  await Promise.all(Array.from({ length: 96 }, async () => {
    while (idx < hosts.length) { const h = hosts[idx++]; if (await probe(h, 7363)) found.push(h); }
  }));
  return { subnets: [...subnets], boxes: found };
}

// ---------- Télécommande Android TV (protobuf, ports 6467 / 6466) ----------
function varint(v) { const out = []; v = BigInt(v); while (v > 0x7fn) { out.push(Number((v & 0x7fn) | 0x80n)); v >>= 7n; } out.push(Number(v)); return Buffer.from(out); }
class PW {
  constructor() { this.parts = []; }
  v(f, val) { this.parts.push(varint((f << 3) | 0), varint(val)); return this; }
  b(f, buf) { this.parts.push(varint((f << 3) | 2), varint(buf.length), buf); return this; }
  s(f, str) { return this.b(f, Buffer.from(str, 'utf8')); }
  m(f, fn) { const w = new PW(); fn(w); return this.b(f, w.buf()); }
  buf() { return Buffer.concat(this.parts); }
}
function parse(buf) {
  const m = {}; let i = 0;
  const rv = () => { let r = 0n, sh = 0n; for (;;) { const b = buf[i++]; r |= BigInt(b & 0x7f) << sh; if (!(b & 0x80)) return r; sh += 7n; } };
  while (i < buf.length) {
    const tag = rv(); const f = Number(tag >> 3n); const wt = Number(tag & 7n);
    let val;
    if (wt === 0) val = rv(); else if (wt === 2) { const n = Number(rv()); val = buf.slice(i, i + n); i += n; } else if (wt === 1) { i += 8; continue; } else if (wt === 5) { i += 4; continue; } else break;
    (m[f] = m[f] || []).push(val);
  }
  return m;
}
const delim = (b) => Buffer.concat([varint(b.length), b]);
class DelimReader {
  constructor(sock) { this.buf = Buffer.alloc(0); this.waiters = []; this.msgs = []; this.closed = false;
    sock.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this.pump(); });
    const end = () => { this.closed = true; this.waiters.splice(0).forEach((w) => w.reject(new Error('Connexion fermée par la TV'))); };
    sock.on('close', end); sock.on('error', end); }
  pump() {
    for (;;) {
      let len = 0, sh = 0, i = 0, ok = false;
      while (i < this.buf.length) { const b = this.buf[i++]; len |= (b & 0x7f) << sh; sh += 7; if (!(b & 0x80)) { ok = true; break; } }
      if (!ok || this.buf.length < i + len) return;
      const msg = this.buf.slice(i, i + len); this.buf = this.buf.slice(i + len);
      const w = this.waiters.shift(); if (w) w.resolve(msg); else this.msgs.push(msg);
    }
  }
  next(timeout = 15000) {
    if (this.msgs.length) return Promise.resolve(this.msgs.shift());
    if (this.closed) return Promise.reject(new Error('Connexion fermée par la TV'));
    return new Promise((resolve, reject) => {
      const w = { resolve: (m) => { clearTimeout(t); resolve(m); }, reject: (e) => { clearTimeout(t); reject(e); } };
      const t = setTimeout(() => { this.waiters = this.waiters.filter((x) => x !== w); reject(new Error('La TV ne répond pas')); }, timeout);
      this.waiters.push(w);
    });
  }
}

let pairing = null; // { sock, reader }
const outer = () => new PW().v(1, 2).v(2, 200);
async function pairExpect(reader, field) {
  const m = parse(await reader.next());
  const status = m[2] ? Number(m[2][0]) : 200;
  if (status !== 200) throw new Error(`Jumelage refusé par la TV (statut ${status})`);
  if (!m[field]) throw new Error('Réponse inattendue de la TV pendant le jumelage');
  return m;
}
async function remotePairStart(h) {
  if (pairing) { pairing.sock.destroy(); pairing = null; }
  const sock = await tlsOpen(h, 6467);
  const reader = new DelimReader(sock);
  const send = (w) => sock.write(delim(w.buf()));
  send(outer().m(10, (w) => w.s(1, 'atvremote').s(2, 'PC Claude')));
  await pairExpect(reader, 11);
  send(outer().m(20, (w) => w.m(1, (e) => e.v(1, 3).v(2, 6)).v(3, 1)));
  await pairExpect(reader, 20);
  send(outer().m(30, (w) => w.m(1, (e) => e.v(1, 3).v(2, 6)).v(2, 1)));
  await pairExpect(reader, 31);
  pairing = { sock, reader, host: h };
}
const hexBytes = (hex) => { hex = hex.replace(/^0x/i, ''); if (hex.length % 2) hex = '0' + hex; return Buffer.from(hex, 'hex'); };
function atvSecret(cm, ce, sm, se, code) {
  if (!/^[0-9A-F]{6}$/.test(code)) return null;
  const h = crypto.createHash('sha256').update(hexBytes(cm)).update(hexBytes(ce)).update(hexBytes(sm)).update(hexBytes(se))
    .update(Buffer.from(code.slice(2), 'hex')).digest();
  return h[0] === parseInt(code.slice(0, 2), 16) ? h : null;
}
async function remotePairConfirm(code) {
  if (!pairing) throw new Error("Lancez d'abord remote_pair_start");
  code = String(code).trim().toUpperCase();
  const cpub = forge.pki.certificateFromPem(identity().cert).publicKey;
  const peer = pairing.sock.getPeerCertificate();
  const secret = atvSecret(cpub.n.toString(16), cpub.e.toString(16), peer.modulus, peer.exponent, code);
  if (!secret) throw new Error('Code incorrect (vérifiez les 6 caractères affichés sur la TV)');
  pairing.sock.write(delim(outer().m(40, (w) => w.b(1, secret)).buf()));
  await pairExpect(pairing.reader, 41);
  state.remote = Object.assign(state.remote || {}, { [pairing.host]: true }); save();
  pairing.sock.destroy(); pairing = null;
}

let remote = null; // { sock, host }
const FEATURES = 623;
async function remoteConn() {
  const h = host();
  if (remote && !remote.sock.destroyed && remote.host === h) return remote;
  if (!(state.remote && state.remote[h])) throw new Error('Télécommande non jumelée : lancez remote_pair_start puis remote_pair_confirm');
  const sock = await tlsOpen(h, 6466);
  const reader = new DelimReader(sock);
  const r = { sock, host: h, app: '', on: true };
  let ready;
  const readyP = new Promise((res) => { ready = res; });
  (async () => {
    try {
      for (;;) {
        const m = parse(await reader.next(3600_000));
        if (m[1]) {
          const f = Number(parse(m[1][0])[1]?.[0] ?? FEATURES) & FEATURES;
          sock.write(delim(new PW().m(1, (w) => w.v(1, f).m(2, (d) => d.s(1, 'PC').s(2, 'Claude').v(3, 1).s(4, '1').s(5, 'atvremote').s(6, '1.0.0'))).buf()));
        } else if (m[2]) { sock.write(delim(new PW().m(2, (w) => w.v(1, FEATURES)).buf())); ready(); }
        else if (m[8]) sock.write(delim(new PW().m(9, (w) => w.v(1, parse(m[8][0])[1]?.[0] ?? 0n)).buf()));
        else if (m[20]) { const app = parse(m[20][0])[1]; if (app) { const p = parse(app[0])[12]; if (p) r.app = p[0].toString('utf8'); } }
        else if (m[40]) r.on = Number(parse(m[40][0])[1]?.[0] ?? 1n) !== 0;
      }
    } catch (_) { sock.destroy(); }
  })();
  await Promise.race([readyP, new Promise((res) => setTimeout(res, 6000))]);
  if (sock.destroyed) throw new Error('La TV a fermé la connexion de télécommande (jumelage à refaire ?)');
  remote = r;
  return r;
}
const KEYS = { UP: 19, DOWN: 20, LEFT: 21, RIGHT: 22, OK: 23, CENTER: 23, ENTER: 66, BACK: 4, HOME: 3, MENU: 82, INFO: 165,
  PLAY_PAUSE: 85, PLAY: 126, PAUSE: 127, STOP: 86, REWIND: 89, FAST_FORWARD: 90, VOL_UP: 24, VOL_DOWN: 25, MUTE: 164,
  CH_UP: 166, CH_DOWN: 167, GUIDE: 172, SEARCH: 84, SETTINGS: 176, RED: 183, GREEN: 184, YELLOW: 185, BLUE: 186 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function sendKeys(keys, delay) {
  const r = await remoteConn();
  const sent = [];
  for (const k of keys) {
    const name = String(k).toUpperCase();
    const code = KEYS[name] ?? (/^\d+$/.test(name) ? Number(name) : undefined);
    if (code === undefined) throw new Error(`Touche inconnue : ${k}. Connues : ${Object.keys(KEYS).join(', ')} ou un code Android`);
    if (code === 26 || code === 223) throw new Error('Touches marche/veille refusées');
    r.sock.write(delim(new PW().m(10, (w) => w.v(1, code).v(2, 3)).buf()));
    sent.push(name);
    await sleep(delay);
  }
  return { sent, app: r.app };
}

// ---------- Outils MCP ----------
const TOOLS = [
  ['tv_find', "Cherche sur le réseau local les box où l'appli Box (Télécommande) répond, et retient la première.", {}],
  ['tv_pair_start', "Jumelage du PC avec l'appli Box : affiche un code sur la TV (le parent valide d'abord si un code parent existe).", { host: 'string?' }],
  ['tv_pair_confirm', "Termine le jumelage avec le code à 6 chiffres affiché sur la TV.", { code: 'string' }],
  ['remote_pair_start', "Jumelage de la télécommande Android TV de la box (touches) : affiche un code de 6 caractères sur la TV.", {}],
  ['remote_pair_confirm', "Termine le jumelage de la télécommande avec le code de 6 caractères affiché sur la TV.", { code: 'string' }],
  ['tv_keys', "Appuie sur des touches de la télécommande, dans l'ordre (UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, PLAY_PAUSE, VOL_UP… ou code Android).", { keys: 'string[]', delay_ms: 'number?' }],
  ['tv_status', "État de l'appli Box : appli au premier plan, profil actif, règles, version.", {}],
  ['tv_screen', "Arbre d'accessibilité de toutes les fenêtres de la TV (textes, identifiants, focus, positions), et réglages du service.", { max: 'number?' }],
  ['tv_events', "Derniers événements d'accessibilité reçus par la TV (depuis since = horodatage ms, 0 = tous).", { since: 'number?' }],
  ['tv_log', "Journal de la TV (appli, écran, clics, touches, classification, blocages) et ce qu'elle a appris des applis.", { max: 'number?' }],
  ['tv_launch', "Ouvre une appli sur la TV par son nom de paquet (ex. com.netflix.ninja).", { pkg: 'string' }],
  ['tv_action', "Action d'accessibilité sur l'élément dont le texte contient text : click, focus (focus d'accessibilité) ou select.", { text: 'string', action: 'string?' }],
  ['tv_global', "Action globale : back, home, recents, notifications.", { action: 'string' }],
  ['tv_command', "Commande brute de l'appli Box (ex. prog_diag, prog_report, get_rules, set_profile, diag, apps).", { cmd: 'string', params: 'object?' }],
];
function schema(spec) {
  const props = {}, req = [];
  for (const [k, t0] of Object.entries(spec)) {
    const opt = t0.endsWith('?'); const t = t0.replace('?', '');
    props[k] = t === 'string[]' ? { type: 'array', items: { type: 'string' } } : { type: t };
    if (!opt) req.push(k);
  }
  return { type: 'object', properties: props, required: req };
}

async function callTool(name, a) {
  switch (name) {
    case 'tv_find': { const r = await findBoxes(); if (r.boxes.length) { state.host = state.host && r.boxes.includes(state.host) ? state.host : r.boxes[0]; save(); } return Object.assign(r, { selected: state.host || null }); }
    case 'tv_pair_start': {
      if (a.host) { state.host = a.host; save(); }
      const r = await boxCall('pair_start', {}, { pairing: true });
      if (!r.ok) throw new Error(r.error || 'Refusé');
      return { box: r.box, message: r.message || 'Code affiché sur la TV : donnez-le à tv_pair_confirm.' };
    }
    case 'tv_pair_confirm': {
      const h = host(); const code = String(a.code).replace(/\D/g, '');
      const probe = await boxCall('status', {}, { pairing: true }).catch(() => null);
      const boxFp = probe ? probe._boxFp : null;
      if (!boxFp) throw new Error('Box injoignable');
      const proof = crypto.createHmac('sha256', Buffer.from(code, 'utf8')).update(myFp() + boxFp, 'utf8').digest('hex');
      const r = await boxCall('pair_confirm', { proof, name: 'PC de développement (Claude)' }, { pairing: true });
      if (!r.ok) throw new Error(r.error || 'Code refusé');
      state.boxes = Object.assign(state.boxes || {}, { [h]: boxFp }); save();
      return { paired: true, host: h };
    }
    case 'remote_pair_start': await remotePairStart(host()); return { message: 'Code de 6 caractères affiché sur la TV : donnez-le à remote_pair_confirm.' };
    case 'remote_pair_confirm': await remotePairConfirm(a.code); return { paired: true };
    case 'tv_keys': return sendKeys(a.keys || [], Math.max(80, Math.min(5000, a.delay_ms || 350)));
    case 'tv_status': return box('status');
    case 'tv_screen': return box('dev_screen', { max: a.max || 400 });
    case 'tv_events': return box('dev_events', { since: a.since || 0 });
    case 'tv_log': return box('box_log', { max: a.max || 60000 });
    case 'tv_launch': return box('launch', { pkg: a.pkg });
    case 'tv_action': return box('dev_action', { text: a.text, action: a.action || 'click' });
    case 'tv_global': return box('dev_global', { action: a.action });
    case 'tv_command': return box(a.cmd, a.params || {});
    default: throw new Error('Outil inconnu : ' + name);
  }
}

// ---------- Serveur MCP (JSON-RPC sur stdin/stdout) ----------
const send = (o) => process.stdout.write(JSON.stringify(o) + '\n');
let inbuf = '';
process.stdin.on('data', (d) => {
  inbuf += d.toString('utf8');
  let i;
  while ((i = inbuf.indexOf('\n')) >= 0) {
    const line = inbuf.slice(0, i).trim(); inbuf = inbuf.slice(i + 1);
    if (!line) continue;
    let msg; try { msg = JSON.parse(line); } catch (_) { continue; }
    handle(msg);
  }
});
async function handle(msg) {
  const { id, method, params } = msg;
  if (id === undefined) return; // notification
  try {
    if (method === 'initialize') return send({ jsonrpc: '2.0', id, result: {
      protocolVersion: (params && params.protocolVersion) || '2025-06-18',
      capabilities: { tools: {} }, serverInfo: { name: 'telecommande-dev', version: '1.0.0' } } });
    if (method === 'ping') return send({ jsonrpc: '2.0', id, result: {} });
    if (method === 'tools/list') return send({ jsonrpc: '2.0', id, result: { tools: TOOLS.map(([name, description, spec]) => ({ name, description, inputSchema: schema(spec) })) } });
    if (method === 'tools/call') {
      try {
        const r = await callTool(params.name, params.arguments || {});
        return send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(r, (k, v) => typeof v === 'bigint' ? Number(v) : v, 1) }] } });
      } catch (e) {
        return send({ jsonrpc: '2.0', id, result: { isError: true, content: [{ type: 'text', text: String(e && e.message || e) }] } });
      }
    }
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Méthode inconnue : ' + method } });
  } catch (e) {
    send({ jsonrpc: '2.0', id, error: { code: -32603, message: String(e && e.message || e) } });
  }
}
process.on('uncaughtException', (e) => process.stderr.write('Erreur : ' + (e && e.stack || e) + '\n'));
module.exports = { atvSecret, parse, PW, varint };
