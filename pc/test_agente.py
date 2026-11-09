"""Validation with a disposable TLS server. No real shutdown command runs."""
import http.client
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch

from agente import State, Handler, ThreadingHTTPServer, certificate


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        certificate(self.folder, "127.0.0.1")
        self.state = State(self.folder, dry_run=True)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.state = self.state
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(self.folder / "cert.pem", self.folder / "key.pem")
        self.server.socket = ctx.wrap_socket(self.server.socket, server_side=True, do_handshake_on_connect=False)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.state.cancel()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.temp.cleanup()

    def request(self, method, path, data=None, token=None, origin=None):
        # Pin the temporary CA explicitly; hostname is checked against the IP SAN.
        ctx = ssl.create_default_context(cafile=str(self.folder / "cert.pem"))
        conn = http.client.HTTPSConnection("127.0.0.1", self.server.server_port, context=ctx, timeout=4)
        headers = {"Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + token
        if origin: headers["Origin"] = origin
        try:
            conn.request(method, path, json.dumps(data) if data is not None else None, headers)
            r = conn.getresponse()
            return r.status, json.loads(r.read())
        finally:
            conn.close()

    def pair(self):
        code = self.state.new_code()
        status, data = self.request("POST", "/pair", {"code": code})
        self.assertEqual(status, 200)
        self.assertNotIn(data["token"], (self.folder / "clients.json").read_text())
        return code, data["token"]

    def test_auth_pair_consumption_and_cancel(self):
        with patch("agente.subprocess.run") as shutdown:
            self.assertEqual(self.request("POST", "/api/shutdown", {})[0], 401)
            code, token = self.pair()
            self.assertEqual(self.request("POST", "/pair", {"code": code})[0], 403)
            self.assertEqual(self.request("GET", "/api/status", token="wrong")[0], 401)
            self.assertEqual(self.request("GET", "/api/status", token=token)[0], 200)
            status, scheduled = self.request("POST", "/api/shutdown", {}, token)
            self.assertEqual(status, 200)
            self.assertGreater(scheduled["shutdown_at"], 0)
            self.assertEqual(self.request("POST", "/api/shutdown", {}, token)[1]["shutdown_at"], scheduled["shutdown_at"])
            self.assertEqual(self.request("POST", "/api/cancel", {}, token)[0], 200)
            self.assertEqual(self.state.shutdown_at, 0)
            self.assertEqual(self.request("POST", "/shell", {"command": "anything"}, token)[0], 404)
            shutdown.assert_not_called()

    def test_browser_rejected_and_rate_limit(self):
        self.assertEqual(self.request("GET", "/health", origin="https://example.com")[0], 403)
        for _ in range(5): self.assertEqual(self.request("POST", "/pair", {"code": "bad"})[0], 403)
        self.assertEqual(self.request("POST", "/pair", {"code": "bad"})[0], 429)

    def test_persistent_auth_and_expired_code(self):
        _, token = self.pair()
        another = State(self.folder, True)
        self.assertTrue(another.authorized("Bearer " + token))
        self.assertFalse(another.authorized("Bearer wrong"))
        code = another.new_code()
        data = another.load("pairing.json", {})
        data["expires"] = 0
        another.save("pairing.json", data)
        self.assertEqual(another.pair(code, "127.0.0.1")[0], 403)


if __name__ == "__main__": unittest.main()
