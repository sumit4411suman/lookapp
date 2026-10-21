#!/usr/bin/env python3
"""Simulated door relay speaking the docs/DESIGN.md protocol. For testing only: it drives no hardware.
Usage: sim_relay.py CERT.pem KEY.pem SECRET_HEX [PORT] [--door none|closed|open]"""
import hashlib, hmac, json, secrets, ssl, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

NONCE_TTL, PULSE, MAX_FAILS, LOCKOUT = 10.0, 1.0, 5, 60.0

class Relay:
    def __init__(self, secret, door="none"):
        self.secret, self.door = secret, door
        self.nonces, self.fails, self.locked_until, self.pulse_end = {}, 0, 0.0, 0.0
        self.unlock_count = 0
        self.lock = threading.Lock()

    def active(self): return time.monotonic() < self.pulse_end

def make_handler(relay):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def send(self, code, obj):
            b = json.dumps(obj, separators=(",", ":")).encode()
            self.send_response(code); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        def do_GET(self):
            u = urlparse(self.path)
            with relay.lock:
                if u.path == "/challenge":
                    if time.monotonic() < relay.locked_until: return self.send(429, {"error": "locked"})
                    n = secrets.token_hex(16); relay.nonces[n] = time.monotonic()
                    return self.send(200, {"nonce": n})
                if u.path == "/status":
                    return self.send(200, {"relay": "active" if relay.active() else "idle", "door": relay.door, "fw": "sim"})
            self.send(404, {})
        def do_POST(self):
            u = urlparse(self.path)
            if u.path != "/unlock": return self.send(404, {})
            q = parse_qs(u.query)
            with relay.lock:
                now = time.monotonic()
                if now < relay.locked_until: return self.send(429, {"error": "locked"})
                nonce, mac = q.get("nonce", [""])[0], q.get("mac", [""])[0]
                born = relay.nonces.pop(nonce, None)           # single use, consumed even if MAC is wrong
                if born is None or now - born > NONCE_TTL: return self.send(409, {"error": "nonce"})
                want = hmac.new(relay.secret, ("unlock|" + nonce).encode(), hashlib.sha256).hexdigest()
                if not hmac.compare_digest(want, mac):
                    relay.fails += 1
                    if relay.fails >= MAX_FAILS: relay.locked_until, relay.fails = now + LOCKOUT, 0
                    return self.send(401, {"error": "auth"})
                relay.fails = 0; relay.unlock_count += 1; relay.pulse_end = now + PULSE
                self.send(200, {"accepted": True, "relay": "active", "door": relay.door})
    return H

def serve(cert, key, secret_hex, port=8443, door="none"):
    relay = Relay(bytes.fromhex(secret_hex), door)
    srv = ThreadingHTTPServer(("0.0.0.0", port), make_handler(relay))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key); srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    return srv, relay

if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    door = sys.argv[sys.argv.index("--door") + 1] if "--door" in sys.argv else "none"
    if door in a: a.remove(door)
    srv, _ = serve(a[0], a[1], a[2], int(a[3]) if len(a) > 3 else 8443, door)
    print("simulated relay listening; Ctrl-C to stop"); srv.serve_forever()
