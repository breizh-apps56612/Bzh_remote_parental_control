#!/usr/bin/env python3
"""Téléphone de test pour l'appli Box (même protocole que l'appli Télécommande) : TLS mutuel + JSON ligne par ligne.

  python3 boxcli.py pair_start             → la TV affiche un code (ou demande la validation du parent)
  python3 boxcli.py pair 123456            → appairage SPAKE2 avec le code lu sur la TV
  python3 boxcli.py status | get_rules | usage '{"from":0,"to":0}' | box_log | diag | apps …
  python3 boxcli.py nocert                 → vérifie que la box refuse une connexion sans certificat
  python3 boxcli.py flood 12               → ouvre N connexions muettes puis tente « status » (test de blocage)
  python3 boxcli.py profond 5000           → envoie un JSON imbriqué N fois (la Box doit refuser sans planter)
Stress test (résultat en JSON sur une ligne) :
  python3 boxcli.py rafale 300             → N « status » d'affilée sur une connexion (réponses, temps médian / p95)
  python3 boxcli.py parallele 6 50         → C téléphones appairés en même temps, N commandes chacun
  python3 boxcli.py poubelle 60            → N connexions qui envoient n'importe quoi (octets, ligne géante, commandes inconnues)
  python3 boxcli.py fuzz_rules 150         → N set_rules aux valeurs absurdes, puis relecture des règles
  python3 boxcli.py pair_spam 20           → N demandes d'appairage d'un téléphone inconnu (doivent être freinées)
Options : BOX=127.0.0.1:7363 (défaut), ID=dossier du certificat de test (défaut : ./id, créé au besoin).
"""
import hashlib, hmac, json, os, random, secrets, socket, ssl, subprocess, sys, threading, time

HOST, PORT = os.environ.get("BOX", "127.0.0.1:7363").split(":")
PORT = int(PORT)
ID = os.environ.get("ID", os.path.join(os.path.dirname(os.path.abspath(__file__)), "id"))
CERT, KEY = os.path.join(ID, "c.pem"), os.path.join(ID, "k.pem")

# --- SPAKE2 identique à shared/Spake2.kt (RFC 3526 groupe 14) ---
P = int("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
        "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16)
Q, G = (P - 1) // 2, 2
sha = lambda b: hashlib.sha256(b).digest()

def element(label):
    seed, out, i = label.encode(), b"", 0
    while len(out) < 320: out += sha(seed + bytes([i])); i += 1
    return pow(int.from_bytes(out[:320], "big") % P, 2, P)

M, N = element("telecommande SPAKE2 M"), element("telecommande SPAKE2 N")

def ensure_id():
    if not os.path.exists(CERT):
        os.makedirs(ID, exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", KEY, "-out", CERT,
                        "-days", "365", "-subj", "/CN=TestPhone"], check=True, capture_output=True)

def my_fp():
    der = ssl.PEM_cert_to_DER_cert(open(CERT).read())
    return hashlib.sha256(der).hexdigest()

def conn(cert=True):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    if cert: ctx.load_cert_chain(CERT, KEY)
    s = ctx.wrap_socket(socket.create_connection((HOST, PORT), timeout=60))
    return s, hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()

def send(f, cmd, **p):
    f.write((json.dumps({"id": "1", "cmd": cmd, **p}) + "\n").encode()); f.flush()
    return json.loads(f.readline())

def call(cmd, **p):
    s, _ = conn(); f = s.makefile("rwb")
    try: return send(f, cmd, **p)
    finally: s.close()

def pair(code):
    s, box = conn(); f = s.makefile("rwb"); phone = my_fp()
    w = int.from_bytes(sha(("telecommande-pair:" + code).encode()), "big") % Q
    x = secrets.randbelow(Q - 1) + 1
    X = format(pow(G, x, P) * pow(M, w, P) % P, "x")
    r1 = send(f, "pair_spake", x=X)
    if not r1.get("ok"): return r1
    Y = r1["y"]
    k = pow(int(Y, 16) * pow(pow(N, w, P), -1, P) % P, x, P)
    key = sha(("spake2|%s|%s|%s|%s|%s|%s" % (phone, box, X, Y, format(k, "x"), format(w, "x"))).encode())
    mac = lambda l: hmac.new(key, l.encode(), hashlib.sha256).hexdigest()
    r2 = send(f, "pair_spake_confirm", confirm=mac("confirm-phone"), name="Test Analyse")
    if r2.get("ok"): r2["box_prouve_le_code"] = hmac.compare_digest(r2.get("confirm", ""), mac("confirm-box"))
    s.close(); return r2

# ---------- Stress test ----------

def _pct(v, p):
    v = sorted(v); return round(v[min(len(v) - 1, int(len(v) * p))], 1) if v else None

def rafale(n):
    s, _ = conn(); f = s.makefile("rwb"); ok = 0; t = []
    try:
        for i in range(n):
            t0 = time.monotonic(); r = send(f, "status"); t.append((time.monotonic() - t0) * 1000)
            ok += bool(r.get("ok"))
    except Exception as e: return {"ok": ok, "n": n, "erreur": str(e)[:100], "ms_median": _pct(t, .5), "ms_p95": _pct(t, .95)}
    finally: s.close()
    return {"ok": ok, "n": n, "ms_median": _pct(t, .5), "ms_p95": _pct(t, .95), "ms_max": _pct(t, 1)}

def parallele(c, n):
    res = {"ok": 0, "refus": 0, "erreurs": 0}; lock = threading.Lock(); t = []
    def un():
        try:
            s, _ = conn(); f = s.makefile("rwb")
            try:
                for _ in range(n):
                    t0 = time.monotonic(); r = send(f, "status")
                    with lock:
                        t.append((time.monotonic() - t0) * 1000); res["ok" if r.get("ok") else "erreurs"] += 1
            finally: s.close()
        except Exception:
            with lock: res["refus"] += 1   # connexion refusée (limite par appareil, voulue) ou coupée
    th = [threading.Thread(target=un) for _ in range(c)]
    [x.start() for x in th]; [x.join(120) for x in th]
    res.update(attendu=c * n, ms_p95=_pct(t, .95)); return res

def poubelle(n):
    fh, fp = os.environ.get("FLOOD", "%s:%d" % (HOST, PORT)).split(":")
    sortes = {"octets": 0, "geante": 0, "tls_octets": 0, "inconnue": 0, "types": 0}
    for i in range(n):
        k = list(sortes)[i % len(sortes)]
        try:
            if k == "octets":   # avant même TLS
                s = socket.create_connection((fh, int(fp)), timeout=10); s.sendall(os.urandom(random.randint(1, 4000))); s.close()
            else:
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
                ctx.load_cert_chain(CERT, KEY)
                s = ctx.wrap_socket(socket.create_connection((fh, int(fp)), timeout=15)); s.settimeout(15)
                if k == "geante": s.sendall(b'{"id":"1","cmd":"status","x":"' + b"a" * 400_000 + b'"}\n')
                elif k == "tls_octets": s.sendall(os.urandom(3000) + b"\n\xff\xfe\n")
                elif k == "inconnue": s.sendall(b'{"id":"1","cmd":"rm -rf /"}\n{"cmd":null}\n[1,2,3]\n"texte"\n')
                else: s.sendall(b'{"id":{"a":1},"cmd":["status"],"rules":"x"}\n{"id":"1","cmd":"set_rules","rules":[]}\n')
                try: s.recv(200)
                except Exception: pass
                s.close()
            sortes[k] += 1
        except Exception: sortes[k] += 1   # coupé par la Box : c'est ce qu'on veut
    return {"envoyes": n, **sortes}

def _junk():
    return random.choice([random.randint(-10**12, 10**12), -1, 2**63 - 1, "texte", None, [], [1, [2]], {"a": 1}, True, 1e308, "😀" * 50])

def fuzz_rules(n):
    keys = ["enabled", "dailyTotalMin", "bedtimeEnabled", "bedtimeStart", "bedtimeEnd", "protectSettings", "requestMinutes",
            "graceMin", "showTimer", "stillThereMin", "activeFrom", "activeTo", "activeDays", "apps", "profiles", "pinHash"]
    s, _ = conn(); f = s.makefile("rwb"); acc = ref = coupe = 0
    try:
        for i in range(n):
            r = {k: _junk() for k in random.sample(keys, random.randint(1, len(keys)))}
            r["version"] = 20000 + i; r["enabled"] = False   # jamais de règle active pendant le test
            if random.random() < .3: r["apps"] = {"com.x.%d" % j: {"limitMin": _junk(), "days": _junk(), "from": _junk(), "to": _junk()} for j in range(5)}
            if random.random() < .3: r["profiles"] = [{"id": _junk(), "name": _junk(), "dailyTotalMin": _junk()} for _ in range(3)]
            r.pop("pinHash", None)   # le code parent du banc ne change pas
            try:
                x = send(f, "set_rules", rules=r); acc += bool(x.get("ok")); ref += not x.get("ok")
            except Exception:
                coupe += 1; s.close(); s, _ = conn(); f = s.makefile("rwb")
        g = send(f, "get_rules")
        lisible = bool(g.get("ok")) and isinstance(g.get("rules"), dict)
    finally: s.close()
    return {"envoyes": n, "acceptes": acc, "refuses": ref, "coupes": coupe, "regles_relues": lisible}

def pair_spam(n):
    global CERT, KEY
    autre = ID + "-inconnu"; CERT, KEY = os.path.join(autre, "c.pem"), os.path.join(autre, "k.pem")
    if not os.path.exists(CERT):
        os.makedirs(autre, exist_ok=True)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", KEY, "-out", CERT, "-days", "1",
                        "-subj", "/CN=Inconnu"], check=True, capture_output=True)
    acceptes = freines = coupes = 0
    for _ in range(n):
        try:
            r = call("pair_start")
            if r.get("ok"): acceptes += 1
            else: freines += 1
        except Exception: coupes += 1
        time.sleep(0.3)
    return {"demandes": n, "acceptees": acceptes, "freinees": freines, "coupees": coupes}

if __name__ == "__main__":
    ensure_id()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "pair": print(json.dumps(pair(sys.argv[2]), ensure_ascii=False))
    elif cmd == "nocert":
        # En TLS 1.3, le refus du certificat absent arrive après la poignée de main : on tente donc une vraie commande.
        try:
            s, _ = conn(False); f = s.makefile("rwb")
            f.write(b'{"id":"1","cmd":"status"}\n'); f.flush(); r = f.readline()
            print("ATTENTION : connexion acceptée sans certificat :", r[:120]) if r else print("refusé (normal) : connexion fermée")
        except Exception as e: print("refusé (normal) :", e)
    elif cmd == "flood":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 12
        # FLOOD=hôte:port : les connexions muettes viennent d'une autre adresse que le vrai téléphone (autre appareil du réseau)
        fh, fp = os.environ.get("FLOOD", "%s:%d" % (HOST, PORT)).split(":")
        socks = [socket.create_connection((fh, int(fp))) for _ in range(n)]; time.sleep(2)
        try: print("pendant :", json.dumps(call("status"), ensure_ascii=False)[:100])
        except Exception as e: print("pendant : BLOQUÉ —", e)
        for s in socks: s.close()
    elif cmd in ("rafale", "parallele", "poubelle", "fuzz_rules", "pair_spam"):
        a = [int(x) for x in sys.argv[2:]]
        print(json.dumps({"rafale": rafale, "parallele": parallele, "poubelle": poubelle, "fuzz_rules": fuzz_rules, "pair_spam": pair_spam}[cmd](*a), ensure_ascii=False))
    elif cmd == "profond":  # message JSON très imbriqué : la Box doit refuser sans planter
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 5000
        s, _ = conn(); f = s.makefile("rwb")
        try:
            f.write(('{"id":"1","cmd":"status","x":' + "[" * n + "]" * n + "}\n").encode()); f.flush()
            print("réponse :", f.readline().decode(errors="replace")[:200].strip() or "(connexion fermée)")
        except Exception as e: print("réponse : connexion coupée —", e)
        finally: s.close()
    else:
        params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
        out = json.dumps(call(cmd, **params), ensure_ascii=False)
        print(out if cmd == "usage" else out[:6000])  # usage : complet, pour relire le détail des séances
