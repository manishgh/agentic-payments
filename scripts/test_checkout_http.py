"""Actual optional checkout routes, PostgreSQL durability and independent loopback merchant."""
from concurrent.futures import ThreadPoolExecutor
import json
import unittest
from uuid import uuid4

from integrations.checkout_demo import CANARY, CREDENTIAL, SyntheticCheckoutPeer
from scripts.checkout_test_support import CheckoutApiProcess, template
from workflows.checkout_workflow import safe_view


class CheckoutHttpTests(unittest.TestCase):
    def setUp(self):
        self.peer = SyntheticCheckoutPeer()
        self.addCleanup(self.peer.close)
        self.api = CheckoutApiProcess(self.peer.origin)
        self.addCleanup(self.api.stop)
        self.purchase = str(uuid4())

    def stage(self, body=None, key=None):
        status, view = self.api.request(self.purchase, "/commands", template() if body is None else body, key=key)
        self.assertEqual(200, status, view)
        self.assertEqual(view, safe_view(view, self.api.run_id, self.purchase))
        return view

    def dispatch(self, view):
        status, result = self.api.request(self.purchase, "/commands/" + view["commandId"] + "/dispatch", {})
        self.assertEqual(200, status, result)
        return result

    def test_four_operations_survive_api_restart_without_duplicate_merchant_mutation(self):
        key = str(uuid4())
        first = self.stage(key=key)
        self.assertEqual("STAGED", first["commandState"])
        self.assertFalse(self.peer.requests)
        self.api.restart()
        created = self.dispatch(first)
        self.assertEqual(("RECORDED", "OK", 2), (created["commandState"], created["outcomeCode"], created["revision"]))
        self.api.restart()
        replay = self.stage(key=key)
        self.assertEqual(first["commandId"], replay["commandId"])
        self.assertEqual(created, self.dispatch(first))
        self.assertEqual(1, len(self.peer.requests))
        latest = created
        for operation in ("RETRIEVE", "UPDATE", "CANCEL"):
            latest = self.dispatch(self.stage(template(operation, latest["revision"])))
            self.assertEqual("OK", latest["outcomeCode"])
        self.assertEqual("canceled", latest["remoteStatus"])
        self.assertEqual(["POST", "GET", "POST", "POST"], [r["method"] for r in self.peer.requests])
        for secret in (CANARY, CREDENTIAL, "protected-item-97", self.peer.origin, next(iter(self.peer.sessions))):
            self.assertNotIn(secret, json.dumps(latest))
        self.assertEqual(404, self.api.request(self.purchase, "/responses/" + latest["responseReference"])[0])

    def test_concurrent_dispatch_claims_once_and_local_key_conflicts(self):
        key = str(uuid4())
        first = self.stage(key=key)
        with ThreadPoolExecutor(max_workers=4) as pool:
            replies = list(pool.map(lambda _: self.api.request(self.purchase, "/commands/" + first["commandId"] + "/dispatch", {}), range(4)))
        self.assertTrue(all(status == 200 for status, _ in replies))
        self.assertEqual(1, len(self.peer.requests))
        changed = template(); changed["itemIds"] = ["different-item"]
        self.assertEqual(409, self.api.request(self.purchase, "/commands", changed, key=key)[0])
        self.assertEqual(1, len(self.peer.requests))

    def test_unknown_create_never_retries_or_adopts_new_key_after_restart(self):
        self.peer.drop_next_mutation = True
        staged = self.stage()
        unknown = self.dispatch(staged)
        self.assertEqual(("UNKNOWN", True, "TRUSTED_RESOLUTION_REQUIRED"),
                         (unknown["commandState"], unknown["mutationBlocked"], unknown["recovery"]))
        self.api.restart()
        self.assertEqual("UNKNOWN", self.dispatch(staged)["commandState"])
        self.assertEqual(409, self.api.request(self.purchase, "/commands", template())[0])
        self.assertEqual(1, len(self.peer.requests))
        self.assertEqual(1, len(self.peer.sessions))

    def test_route_validation_auth_and_owner_checks_precede_peer_effects(self):
        path = "/commands"
        self.assertEqual(401, self.api.request(self.purchase, path, template(), token="invalid")[0])
        self.assertEqual(403, self.api.request(self.purchase, path, template(), token=self.api.ui_token)[0])
        missing = template(); del missing["expectedRevision"]
        for body in (missing, {**template(), "origin": self.peer.origin}, {**template(), "expectedRevision": True}):
            self.assertEqual(400, self.api.request(self.purchase, path, body)[0])
        raw = json.dumps(template()).replace('"expectedRevision": 0', '"expectedRevision": 0, "expectedRevision": 0').encode()
        self.assertEqual(400, self.api.request(self.purchase, path, raw=raw)[0])
        staged = self.stage()
        self.assertEqual(404, self.api.request(self.purchase, token=self.api.ui_token)[0])
        self.assertEqual(400, self.api.request(self.purchase, "/commands/" + staged["commandId"] + "/dispatch", {"retry": True})[0])
        with CheckoutApiProcess(self.peer.origin) as other:
            self.assertEqual(404, other.request(self.purchase)[0])
        self.assertFalse(self.peer.requests)

    def test_unregistered_lifecycle_is_explicitly_unsupported(self):
        with CheckoutApiProcess() as disabled:
            status, value = disabled.request(self.purchase, "/commands", template())
            self.assertEqual(422, status)
            self.assertEqual("UNSUPPORTED_CAPABILITY", value["error"]["code"])
        self.assertFalse(self.peer.requests)


if __name__ == "__main__":
    unittest.main()
