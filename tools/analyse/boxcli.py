#!/usr/bin/env python3
"""Téléphone de test pour l'appli Box (même protocole que l'appli Télécommande) : TLS mutuel + JSON ligne par ligne.

  python3 boxcli.py pair_start             → la TV affiche un code (ou demande la validation du parent)
  python3 boxcli.py pair 123456            → appairage SPAKE2 avec le code lu sur la TV
  python3 boxcli.py status | get_rules | usage '{"from":0,"to":0}' | box_log | diag | apps …
  python3 boxcli.py nocert                 → vérifie que la box refuse une connexion sans certificat
  python3 boxcli.py flood 12               → ouvre N connexions muettes puis tente « status » (test de blocage)
Options : BOX=127.0.0.1:7363 (défaut), ID=dossier du certificat de test (défaut : ./id, créé au besoin).
"""
import hashlib, hmac, json, os, secrets, socket, ssl, subprocess, sys, time

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

if __name__ == "__main__":
    ensure_id()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "pair": print(json.dumps(pair(sys.argv[2]), ensure_ascii=False))
    elif cmd == "nocert":
        try: conn(False); print("ATTENTION : connexion acceptée sans certificat")
        except Exception as e: print("refusé (normal) :", e)
    elif cmd == "flood":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 12
        socks = [socket.create_connection((HOST, PORT)) for _ in range(n)]; time.sleep(2)
        try: print("pendant :", json.dumps(call("status"), ensure_ascii=False)[:100])
        except Exception as e: print("pendant : BLOQUÉ —", e)
        for s in socks: s.close()
    else:
        params = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
        print(json.dumps(call(cmd, **params), ensure_ascii=False)[:6000])
