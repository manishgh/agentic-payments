"""Synthetic ACP loopback merchant for the opt-in durable lifecycle demonstration.

Its in-memory merchant state does not survive peer restart. It never completes payments.
"""
from __future__ import annotations

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import socket
from threading import RLock, Thread
from uuid import uuid4

CREDENTIAL = "synthetic-checkout-demo"
CANARY = "SYNTHETIC_PROTECTED_CHECKOUT_DOCUMENT_97"


class SyntheticCheckoutPeer:
    def __init__(self, port=0):
        self.lock = RLock()
        self.requests = []
        self.sessions = {}
        self.commands = {}
        self.drop_next_mutation = False
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                self.handle_checkout()

            def do_POST(self):
                self.handle_checkout()

            def handle_checkout(self):
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 <= length <= 16384:
                        return self.reply(400, {"code": "invalid_request"})
                    body = self.rfile.read(length)
                    if (self.headers.get("Authorization") != "Bearer " + CREDENTIAL
                            or self.headers.get("API-Version") != "2026-04-17"):
                        return self.reply(401, {"code": "unauthorized"})
                    key = self.headers.get("Idempotency-Key")
                    if self.command == "POST" and not key:
                        return self.reply(400, {"code": "idempotency_key_required"})
                    with peer.lock:
                        peer.requests.append({"method": self.command, "path": self.path, "body": body, "key": key})
                        identity = (self.command, self.path, key)
                        digest = hashlib.sha256(body).hexdigest()
                        old = peer.commands.get(identity) if self.command == "POST" else None
                        if old is not None:
                            if old[0] != digest:
                                return self.reply(422, {"code": "idempotency_conflict"})
                            return self.reply(old[1], old[2], replayed=True)
                        status, result = self.operate(body)
                        if self.command == "POST" and status in (200, 201):
                            peer.commands[identity] = (digest, status, result)
                            if peer.drop_next_mutation:
                                peer.drop_next_mutation = False
                                self.close_connection = True
                                self.connection.shutdown(socket.SHUT_RDWR)
                                return
                        self.reply(status, result)
                except (ValueError, TypeError, KeyError, OSError):
                    self.close_connection = True

            def operate(self, body):
                if self.command == "POST" and self.path == "/checkout_sessions":
                    request = json.loads(body)
                    if (set(request) != {"line_items", "currency", "capabilities"}
                            or request["currency"] not in ("usd", "eur") or request["capabilities"] != {}
                            or not request["line_items"] or any(set(item) != {"id"} for item in request["line_items"])):
                        return 400, {"code": "invalid_request"}
                    identity = "cs_" + uuid4().hex
                    value = {"id": identity, "status": "ready_for_payment", "currency": request["currency"],
                             "protocol": {"version": "2026-04-17"}, "line_items": [], "totals": [],
                             "fulfillment_options": [], "messages": [], "links": [], "capabilities": {},
                             "metadata": {"synthetic_private_value": CANARY, "revision": 1}}
                    peer.sessions[identity] = value
                    return 201, value
                match = re.fullmatch(r"/checkout_sessions/([A-Za-z0-9_-]+)(/cancel)?", self.path)
                if match is None or match[1] not in peer.sessions:
                    return 404, {"code": "not_found"}
                value = peer.sessions[match[1]]
                if self.command == "GET" and match[2] is None and not body:
                    return 200, value
                if self.command == "POST":
                    if match[2] and body:
                        return 400, {"code": "invalid_request"}
                    if not match[2]:
                        request = json.loads(body)
                        if set(request) != {"line_items"} or not request["line_items"]:
                            return 400, {"code": "invalid_request"}
                    value = {**value, "status": "canceled" if match[2] else "ready_for_payment",
                             "metadata": {"synthetic_private_value": CANARY, "revision": value["metadata"]["revision"] + 1}}
                    peer.sessions[match[1]] = value
                    return 200, value
                return 405, {"code": "method_not_allowed"}

            def reply(self, status, value, replayed=False):
                body = json.dumps(value, separators=(",", ":")).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for header in ("Request-Id", "Idempotency-Key"):
                    if self.headers.get(header) is not None:
                        self.send_header(header, self.headers[header])
                if replayed:
                    self.send_header("Idempotent-Replayed", "true")
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def origin(self):
        return "http://127.0.0.1:" + str(self.server.server_address[1])

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18181)
    args = parser.parse_args()
    with SyntheticCheckoutPeer(args.port) as peer:
        print("Synthetic checkout peer: " + peer.origin, flush=True)
        try:
            peer.thread.join()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
