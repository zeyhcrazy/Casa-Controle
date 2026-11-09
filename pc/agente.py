"""Agente local do Casa. HTTPS, pareamento temporario e comandos fixos.

Nao possui execucao remota de shell. --dry-run nunca desliga o computador.
"""
from __future__ import annotations
import argparse
import hashlib
import hmac
import ipaddress
import json
import logging
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from anydesk_control import AnyDeskControl


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class State:
    def __init__(self, folder: Path, dry_run: bool = False):
        self.folder = folder
        self.dry_run = dry_run
        self.lock = threading.RLock()
        self.attempts: dict[str, list[float]] = {}
        self.shutdown_at = 0.0
        self.cancel_event: threading.Event | None = None
        self.started = time.monotonic()
        self.anydesk = AnyDeskControl(dry_run)

    def load(self, name, default):
        path = self.folder / name
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default

    def save(self, name, value):
        path = self.folder / name
        temp = path.with_suffix(".new")
        temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, path)

    def new_code(self) -> str:
        code = f"{secrets.randbelow(1000000):06d}"
        self.save("pairing.json", {"hash": digest(code), "expires": time.time() + 600})
        return code

    def pair(self, code: str, ip: str):
        with self.lock:
            now = time.time()
            attempts = self.attempts.setdefault(ip, [])
            attempts[:] = [t for t in attempts if now - t < 600]
            # Shared attempts file also survives a service restart.
            guard = self.load("pairing-attempts.json", [])
            guard = [t for t in guard if now - t < 600]
            if len(attempts) >= 5 or len(guard) >= 20:
                return 429, {"error": "Muitas tentativas. Aguarde 10 minutos e gere um novo codigo no PC."}
            attempts.append(now)
            guard.append(now)
            self.save("pairing-attempts.json", guard)
            pairing = self.load("pairing.json", {})
            if (len(code) != 6 or not code.isascii() or not code.isdecimal()
                or now >= pairing.get("expires", 0)
                or not hmac.compare_digest(digest(code), pairing.get("hash", ""))):
                return 403, {"error": "Codigo invalido ou expirado. Gere outro no PC."}
            token = secrets.token_urlsafe(32)
            clients = self.load("clients.json", [])[-7:]
            clients.append({"hash": digest(token), "created": now})
            self.save("clients.json", clients)
            self.save("pairing.json", {})  # Code is consumed, including after restart.
            logging.info("Celular pareado: %s", ip)
            return 200, {"token": token}

    def authorized(self, header: str) -> bool:
        if not header.startswith("Bearer ") or len(header) > 256:
            return False
        value = digest(header[7:])
        with self.lock:
            return any(hmac.compare_digest(value, row["hash"])
                       for row in self.load("clients.json", []))

    def schedule(self):
        with self.lock:
            if self.shutdown_at:
                return {"message": "Desligamento ja agendado. Voce pode cancelar.", "shutdown_at": self.shutdown_at}
            event = threading.Event()
            self.cancel_event = event
            self.shutdown_at = time.time() + 15

            def wait_and_shutdown():
                if event.wait(15):
                    return
                with self.lock:
                    if self.cancel_event is not event:
                        return
                    self.cancel_event = None
                    self.shutdown_at = 0
                if self.dry_run:
                    logging.info("DRY RUN: desligamento simulado; nenhum comando executado")
                    return
                logging.info("Desligamento solicitado pelo celular")
                # No /f. The countdown is implemented above rather than by shutdown /t,
                # since Windows implies force-closing applications when /t is > 0.
                subprocess.run([str(Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/shutdown.exe"), "/s", "/t", "0"],
                               check=False, timeout=10, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

            threading.Thread(target=wait_and_shutdown, name="shutdown-delay", daemon=True).start()
            return {"message": "Desligamento em 15 segundos. Voce pode cancelar.", "shutdown_at": self.shutdown_at}

    def cancel(self):
        with self.lock:
            if self.cancel_event:
                self.cancel_event.set()
            self.cancel_event = None
            self.shutdown_at = 0
            return {"message": "Desligamento cancelado."}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Casa/0.4"
    def setup(self):
        super().setup()
        self.connection.settimeout(5)
        # The listening socket does not perform a blocking handshake itself.
        # Each connection gets its own timeout here instead.
        if isinstance(self.connection, ssl.SSLSocket):
            self.connection.do_handshake()

    def log_message(self, format, *args):
        # Do not log request headers, tokens, bodies or pairing codes.
        logging.info("%s %s", self.client_address[0], format % args)

    def respond(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def dispatch(self):
        state: State = self.server.state
        ip = ipaddress.ip_address(self.client_address[0])
        # Only RFC1918 or loopback; do not depend on ipaddress's broader is_private.
        allowed = ip.is_loopback or any(ip in ipaddress.ip_network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        if not allowed:
            return self.respond(403, {"error": "Disponivel apenas na rede local."})
        if self.headers.get("Origin"):
            return self.respond(403, {"error": "Acesso por paginas de navegador nao permitido."})
        if self.path == "/health" and self.command == "GET":
            return self.respond(200, {"service": "zeyh-casa", "dry_run": state.dry_run, "version": "0.4.0"})
        if self.path != "/pair" and not state.authorized(self.headers.get("Authorization", "")):
            return self.respond(401, {"error": "Conecte o celular ao PC pelo codigo de pareamento."})
        body = {}
        if self.command == "POST":
            try:
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError()
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4096 or self.headers.get_content_type() != "application/json":
                    raise ValueError()
                raw = self.rfile.read(size)
                if len(raw) != size:
                    raise ValueError()
                body = json.loads(raw)
                if not isinstance(body, dict):
                    raise ValueError()
            except (ValueError, UnicodeError):
                return self.respond(400, {"error": "Requisicao invalida."})
        if self.path == "/pair" and self.command == "POST":
            code = body.get("code", "")
            status, value = state.pair(code if isinstance(code, str) else "", str(ip))
            return self.respond(status, value)
        if self.path == "/api/status" and self.command == "GET":
            return self.respond(200, {"computer": socket.gethostname(), "shutdown_at": state.shutdown_at,
                                      "dry_run": state.dry_run, "uptime": int(time.monotonic() - state.started), "remote_desktop": False, "anydesk_launcher": True})
        if self.path == '/api/anydesk/open' and self.command == 'POST':
            # This endpoint accepts only an empty body: no executable paths,
            # connection targets, password changes or commands from clients.
            if body:
                return self.respond(400, {'error': 'Este comando nao recebe parametros.'})
            try:
                return self.respond(200, state.anydesk.open())
            except FileNotFoundError as e:
                return self.respond(409, {'error': str(e)})
            except OSError:
                return self.respond(500, {'error': 'Nao foi possivel abrir o AnyDesk no PC.'})
        if self.path == "/api/shutdown" and self.command == "POST":
            return self.respond(200, state.schedule())
        if self.path == "/api/cancel" and self.command == "POST":
            return self.respond(200, state.cancel())
        return self.respond(404, {"error": "Comando inexistente."})

    def do_GET(self):
        try:
            self.dispatch()
        except (TimeoutError, ConnectionError):
            self.close_connection = True

    do_POST = do_GET


def certificate(folder: Path, host: str):
    # Used once at setup. The running agent itself uses only Python's stdlib.
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    import datetime
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Zeyh Casa local")])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=730))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host)), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .sign(key, hashes.SHA256()))
    (folder / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    (folder / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert.fingerprint(hashes.SHA256()).hex()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", type=Path, default=Path(os.environ.get("LOCALAPPDATA", ".")) / "ZeyhCasa")
    p.add_argument("--init", action="store_true")
    p.add_argument("--pair", action="store_true")
    p.add_argument("--serve", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8765)
    a = p.parse_args()
    a.data.mkdir(parents=True, exist_ok=True)
    state = State(a.data, a.dry_run)
    if a.init and not (a.data / "cert.pem").exists():
        print("Certificado SHA256:", certificate(a.data, a.host))
    if a.pair:
        print("Codigo de pareamento (10 minutos):", state.new_code())
    if a.serve:
        logging.basicConfig(filename=str(a.data / "agente.log"), encoding="utf-8", level=logging.INFO,
                            format="%(asctime)s %(message)s")
        server = ThreadingHTTPServer((a.host, a.port), Handler)
        server.daemon_threads = True
        server.state = state
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(a.data / "cert.pem", a.data / "key.pem")
        server.socket = ctx.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
        try:
            logging.info("Agente pronto em %s:%s, dry_run=%s", a.host, a.port, a.dry_run)
            server.serve_forever(poll_interval=0.5)
        finally:
            state.cancel()
            server.server_close()


if __name__ == "__main__":
    main()
