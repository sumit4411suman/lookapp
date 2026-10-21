#!/usr/bin/env python3
"""Exercises the protocol end to end against sim_relay.py with a pinned-certificate client."""
import hashlib, hmac, http.client, json, os, socket, ssl, subprocess, sys, tempfile, threading
sys.path.insert(0, os.path.dirname(__file__)); import sim_relay

d = tempfile.mkdtemp(); c, k = d + "/c.pem", d + "/k.pem"
subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=t", "-keyout", k, "-out", c],
               check=True, capture_output=True)
der = ssl.PEM_cert_to_DER_cert(open(c).read()); pin = hashlib.sha256(der).hexdigest()
secret = os.urandom(32); srv, relay = sim_relay.serve(c, k, secret.hex(), 0, door="closed")
port = srv.server_address[1]; threading.Thread(target=srv.serve_forever, daemon=True).start()

def call(method, path, pin_hex=pin):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    cn = http.client.HTTPSConnection("127.0.0.1", port, context=ctx, timeout=4); cn.connect()
    got = hashlib.sha256(cn.sock.getpeercert(binary_form=True)).hexdigest()
    if got != pin_hex: cn.close(); raise ssl.SSLError("pin mismatch")
    cn.request(method, path); r = cn.getresponse(); body = r.read().decode(); cn.close()
    return r.status, (json.loads(body) if body else {})

def mac(nonce, key=secret): return hmac.new(key, ("unlock|" + nonce).encode(), hashlib.sha256).hexdigest()
ok = []
def check(name, cond): ok.append(cond); print(("PASS " if cond else "FAIL ") + name)

s, j = call("GET", "/challenge"); n = j["nonce"]
check("challenge returns 32-hex nonce", s == 200 and len(n) == 32)
s, j = call("POST", f"/unlock?nonce={n}&mac={mac(n)}")
check("valid MAC accepted, relay active", s == 200 and j["accepted"] and j["relay"] == "active" and relay.unlock_count == 1)
s, _ = call("POST", f"/unlock?nonce={n}&mac={mac(n)}")
check("replayed nonce rejected (409), no second unlock", s == 409 and relay.unlock_count == 1)
n = call("GET", "/challenge")[1]["nonce"]
s, _ = call("POST", f"/unlock?nonce={n}&mac={mac(n, os.urandom(32))}")
check("wrong secret rejected (401)", s == 401 and relay.unlock_count == 1)
s, _ = call("POST", f"/unlock?nonce={n}&mac={mac(n)}")
check("nonce burned by a failed attempt (409)", s == 409 and relay.unlock_count == 1)
s, _ = call("POST", "/unlock?nonce=" + "0" * 32 + "&mac=" + "0" * 64)
check("unknown nonce rejected (409)", s == 409)
try: call("GET", "/challenge", pin_hex="00" * 32); wrong = False
except ssl.SSLError: wrong = True
check("wrong certificate pin refused by client", wrong)
for _ in range(4):  # one failure is already counted above
    n = call("GET", "/challenge")[1]["nonce"]; call("POST", f"/unlock?nonce={n}&mac={'00' * 32}")
s, _ = call("GET", "/challenge")
check("lockout after 5 failures (429)", s == 429)
n_before = relay.unlock_count; s, _ = call("POST", f"/unlock?nonce={n}&mac={mac(n)}")
check("locked out: even a correct MAC does not unlock", s == 429 and relay.unlock_count == n_before)
plain = socket.create_connection(("127.0.0.1", port), timeout=3); plain.sendall(b"GET /challenge HTTP/1.1\r\nHost: x\r\n\r\n")
try: data = plain.recv(100)
except Exception: data = b""
check("plaintext HTTP gets no answer", b"nonce" not in data)
sys.exit(0 if all(ok) else 1)
