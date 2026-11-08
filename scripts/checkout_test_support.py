"""Isolated real API process support for checkout HTTP and graph integration tests."""
from concurrent.futures import ThreadPoolExecutor, TimeoutError
import json
import os
from pathlib import Path
import secrets
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


class CheckoutApiProcess:
    def __init__(self, origin=None, *, financial=False):
        for name in ("PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD", "JAVA_HOME"):
            if not os.environ.get(name):
                raise RuntimeError("Local PostgreSQL and Java configuration is required.")
        self.run_id, self.graph_token, self.ui_token = str(uuid4()), secrets.token_hex(32), secrets.token_hex(32)
        self.environment = dict(os.environ, PAYMENTS_GRAPH_TOKEN=self.graph_token, PAYMENTS_UI_TOKEN=self.ui_token,
                                PAYMENTS_RUN_ID=self.run_id, PAYMENTS_PORT="0")
        self.environment.pop("PAYMENTS_CHECKOUT_DEMO_ORIGIN", None)
        self.environment.pop("PAYMENTS_EXTERNAL_CHECKOUT_FINANCE", None)
        if origin is not None:
            self.environment["PAYMENTS_CHECKOUT_DEMO_ORIGIN"] = origin
        if financial:
            self.environment["PAYMENTS_EXTERNAL_CHECKOUT_FINANCE"] = "true"
        self.process = None
        self.start()

    def start(self):
        classpath = str(ROOT / "target/classes") + os.pathsep + (ROOT / "target/classpath.txt").read_text().strip()
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        self.process = subprocess.Popen([str(java), "-cp", classpath, "io.agenticpayments.http.Main"],
                                        cwd=ROOT, env=self.environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        with ThreadPoolExecutor(max_workers=1) as pool:
            ready = pool.submit(self.process.stdout.readline)
            try:
                line = ready.result(timeout=20)
            except TimeoutError:
                self.stop()
                raise RuntimeError("Checkout API startup deadline exceeded.") from None
        if " at http://127.0.0.1:" not in line:
            self.stop()
            raise RuntimeError("Checkout API startup failed.")
        self.base = line.strip().split(" at ", 1)[1]

    def stop(self):
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.process.stdout.close()
        self.process.stderr.close()
        self.process = None

    def restart(self):
        self.stop()
        self.start()

    def request(self, purchase, suffix="", body=None, *, raw=None, key=None, token=None):
        headers = {"Authorization": "Bearer " + (self.graph_token if token is None else token)}
        payload = json.dumps(body).encode() if body is not None else raw
        if payload is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": key or str(uuid4())})
        request = Request(self.base + "/internal/v0.1/checkout-purchases/" + purchase + suffix, data=payload, headers=headers)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except HTTPError as response:
            return response.status, json.load(response)

    def payment(self, path, body=None, *, raw=None, key=None, token=None):
        headers = {"Authorization": "Bearer " + (self.graph_token if token is None else token)}
        payload = json.dumps(body).encode() if body is not None else raw
        if payload is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": key or str(uuid4())})
        request = Request(self.base + "/internal/v0.1" + path, data=payload, headers=headers)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.load(response), dict(response.headers)
        except HTTPError as response:
            return response.status, json.load(response), dict(response.headers)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.stop()


def template(operation="CREATE", revision=0):
    return {"operation": operation, "expectedRevision": revision, "merchantId": "merchant-demo",
            "configurationId": "checkout-demo", "currency": "usd",
            "itemIds": ["protected-item-97"] if operation in {"CREATE", "UPDATE"} else []}
