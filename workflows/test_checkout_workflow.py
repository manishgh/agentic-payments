"""Replaceable commerce ports, durable workflow identity and hostile checkpoint checks."""
from contextlib import closing
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sqlite3
import tempfile
from threading import Barrier
import unittest
from uuid import uuid4

from langgraph.checkpoint.sqlite import SqliteSaver
from workflows.checkout_bindings import SqliteCheckoutWorkflowBindings
from workflows.checkout_workflow import CheckoutTemplate, CheckoutWorkflow, CONTRACT, PROFILE, safe_view
from workflows.payment_workflow import WorkflowError


class CommerceDouble:
    """Independent application double; merchant durability is exercised in live tests."""
    def __init__(self):
        self.run_id, self.scope_ref, self.principal_id = str(uuid4()), "tenant-test", "agent-test"
        self.view, self.calls, self.keys = None, [], []
        self.lose_stage, self.lose_dispatch, self.unknown = False, False, False

    def stage(self, purchase_id, key, template):
        self.calls.append("stage"); self.keys.append(key)
        if self.view is None:
            self.view = {"contractVersion": CONTRACT, "profile": PROFILE, "runId": self.run_id, "purchaseId": purchase_id,
                         "commandId": str(uuid4()), "revision": 1, "operation": template.operation, "commandState": "STAGED",
                         "outcomeCode": "NONE", "observation": "NOT_SENT", "httpStatus": 0, "remoteSessionKnown": False,
                         "remoteStatus": None, "responseReference": None, "mutationBlocked": False, "recovery": "DISPATCH_STAGED"}
        if self.lose_stage:
            self.lose_stage = False
            raise WorkflowError("LOST_STAGE_RESPONSE")
        return dict(self.view)

    def status(self, purchase_id):
        self.calls.append("status")
        return dict(self.view)

    def dispatch(self, purchase_id, command_id):
        self.calls.append("dispatch")
        self.view.update(commandState="UNKNOWN" if self.unknown else "RECORDED", observation="UNKNOWN" if self.unknown else "OBSERVED",
                         outcomeCode="TRANSPORT_UNCERTAIN" if self.unknown else "OK", revision=2,
                         mutationBlocked=self.unknown, recovery="TRUSTED_RESOLUTION_REQUIRED" if self.unknown else "NONE",
                         httpStatus=0 if self.unknown else 201, remoteSessionKnown=not self.unknown,
                         remoteStatus=None if self.unknown else "ready_for_payment")
        if self.lose_dispatch:
            self.lose_dispatch = False
            raise WorkflowError("LOST_DISPATCH_RESPONSE")
        return dict(self.view)

    def recover_claim(self, purchase_id, command_id):
        self.calls.append("recover")
        return dict(self.view)


class CheckoutWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "graph.sqlite"
        self.client, self.workflow, self.purchase = CommerceDouble(), str(uuid4()), str(uuid4())
        self.template = CheckoutTemplate("CREATE", 0, "merchant-demo", "config-demo", "usd", ("private-item-canary",))

    def invoke(self, action="start", template=None, purchase=None):
        bindings = SqliteCheckoutWorkflowBindings(self.path)
        try:
            with closing(sqlite3.connect(self.path, check_same_thread=False)) as connection:
                flow = CheckoutWorkflow(self.client, self.workflow, purchase or self.purchase, template or self.template,
                                        SqliteSaver(connection), bindings)
                return getattr(flow, action)()
        finally:
            bindings.close()

    def test_lost_stage_response_reopens_with_original_key_then_dispatches_once(self):
        self.client.lose_stage = True
        with self.assertRaises(WorkflowError):
            self.invoke()
        result = self.invoke("resume")
        self.assertEqual("RECORDED", result["commandState"])
        self.assertEqual(2, len(self.client.keys))
        self.assertEqual(1, len(set(self.client.keys)))
        self.assertEqual(1, self.client.calls.count("dispatch"))

    def test_lost_dispatch_response_reads_truth_without_second_send_on_restart(self):
        self.client.lose_dispatch = True
        with self.assertRaises(WorkflowError):
            self.invoke()
        self.assertEqual("RECORDED", self.invoke("resume")["commandState"])
        self.assertEqual("RECORDED", self.invoke("start")["commandState"])
        self.assertEqual(1, self.client.calls.count("dispatch"))

    def test_unknown_interrupt_and_explicit_recovery_never_dispatch_again(self):
        self.client.unknown = True
        self.assertEqual("UNKNOWN", self.invoke()["commandState"])
        self.assertEqual("UNKNOWN", self.invoke("resume")["commandState"])
        self.assertTrue(self.invoke("recover_claim")["mutationBlocked"])
        self.assertEqual(1, self.client.calls.count("dispatch"))
        self.assertEqual(1, self.client.calls.count("recover"))

    def test_workflow_rebinding_fails_before_any_port_call(self):
        self.invoke()
        count = len(self.client.calls)
        changed = CheckoutTemplate("CREATE", 0, "merchant-demo", "config-demo", "usd", ("different-item",))
        for options in ({"template": changed}, {"purchase": str(uuid4())}):
            with self.assertRaises(WorkflowError):
                self.invoke(**options)
        self.client.principal_id = "another-agent"
        with self.assertRaises(WorkflowError):
            self.invoke()
        self.assertEqual(count, len(self.client.calls))

    def test_status_cannot_adopt_another_command_or_regressed_revision(self):
        self.invoke()
        original = self.client.view["commandId"]
        self.client.view["commandId"] = str(uuid4())
        with self.assertRaises(WorkflowError):
            self.invoke("status")
        self.client.view["commandId"] = original
        self.client.view["revision"] = 1
        with self.assertRaises(WorkflowError):
            self.invoke("status")

    def test_safe_view_rejects_missing_or_hostile_values_and_filters_payload(self):
        self.invoke()
        value = dict(self.client.view)
        value["protectedDocument"] = "must-not-enter-checkpoint"
        self.assertNotIn("protectedDocument", safe_view(value, self.client.run_id, self.purchase))
        for key, bad in (("commandState", []), ("revision", True), ("responseReference", "raw"), ("remoteStatus", {})):
            with self.assertRaises(WorkflowError):
                safe_view({**value, key: bad}, self.client.run_id, self.purchase)
        del value["remoteStatus"]
        with self.assertRaises(WorkflowError):
            safe_view(value, self.client.run_id, self.purchase)

    def test_checkpoint_contains_only_safe_projection_and_fingerprint(self):
        self.invoke()
        with closing(sqlite3.connect(self.path)) as connection:
            raw = b"\n".join(str(row).encode() for table in ("checkpoints", "writes", "checkout_workflow_bindings")
                             for row in connection.execute(f"SELECT * FROM {table}"))
        for value in ("private-item-canary", "config-demo", "merchant-demo"):
            self.assertNotIn(value.encode(), raw)

    def test_atomic_registration_and_command_binding_reject_replacement(self):
        first, second = SqliteCheckoutWorkflowBindings(self.path), SqliteCheckoutWorkflowBindings(self.path)
        try:
            first.reserve("identity", "a" * 64)
            second.reserve("identity", "a" * 64)
            with self.assertRaises(WorkflowError):
                second.reserve("identity", "b" * 64)
            command = str(uuid4())
            first.remember("identity", "a" * 64, command)
            second.remember("identity", "a" * 64, command)
            with self.assertRaises(WorkflowError):
                second.remember("identity", "a" * 64, str(uuid4()))
            self.assertEqual(command, second.command("identity", "a" * 64))
        finally:
            first.close(); second.close()

    def test_changed_live_client_identity_rejects_before_port_calls(self):
        bindings = SqliteCheckoutWorkflowBindings(self.path)
        try:
            with closing(sqlite3.connect(self.path, check_same_thread=False)) as connection:
                flow = CheckoutWorkflow(self.client, self.workflow, self.purchase, self.template, SqliteSaver(connection), bindings)
                original = self.client.principal_id
                self.client.principal_id = "swapped-principal"
                with self.assertRaises(WorkflowError):
                    flow.start()
                self.assertEqual([], self.client.calls)
                self.client.principal_id = original
                flow.start()
                count = len(self.client.calls)
                self.client.run_id = str(uuid4())
                for action in (flow.status, flow.resume, flow.recover_claim):
                    with self.assertRaises(WorkflowError):
                        action()
                self.assertEqual(count, len(self.client.calls))
        finally:
            bindings.close()

    def test_injected_port_exception_is_sanitized_before_graph_checkpoint(self):
        secret = "SENSITIVE_INJECTED_CHECKOUT_PORT_CANARY"
        def fail(*_args):
            raise RuntimeError(secret)
        self.client.stage = fail
        with self.assertRaises(WorkflowError) as failure:
            self.invoke()
        self.assertEqual("CHECKOUT_PORT_UNCONFIRMED", str(failure.exception))
        self.assertIsNone(failure.exception.__context__)
        with closing(sqlite3.connect(self.path)) as connection:
            saver = SqliteSaver(connection)
            for checkpoint in saver.list(None):
                self.assertNotIn(secret, repr(checkpoint))
            raw = b"\n".join(str(row).encode() for table in ("checkpoints", "writes")
                             for row in connection.execute(f"SELECT * FROM {table}"))
            self.assertNotIn(secret.encode(), raw)

    def test_concurrent_conflicting_first_starts_have_one_winner_before_effects(self):
        bindings = [SqliteCheckoutWorkflowBindings(self.path) for _ in range(2)]
        connections = [sqlite3.connect(self.path, check_same_thread=False) for _ in range(2)]
        clients = [CommerceDouble(), CommerceDouble()]
        for client in clients:
            client.run_id = self.client.run_id
        templates = [self.template, CheckoutTemplate("CREATE", 0, "merchant-demo", "config-demo", "usd", ("competing-item",))]
        barrier = Barrier(2)
        def start(index):
            barrier.wait(timeout=5)
            try:
                flow = CheckoutWorkflow(clients[index], self.workflow, self.purchase, templates[index],
                                        SqliteSaver(connections[index]), bindings[index])
                flow.start()
                return True
            except WorkflowError:
                return False
        try:
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(start, (0, 1)))
            self.assertEqual(1, sum(results))
            for index, won in enumerate(results):
                self.assertEqual(1 if won else 0, clients[index].calls.count("stage"))
                self.assertEqual(1 if won else 0, clients[index].calls.count("dispatch"))
                if not won:
                    self.assertEqual([], clients[index].calls)
        finally:
            for connection in connections:
                connection.close()
            for binding in bindings:
                binding.close()

    def test_injected_registration_exceptions_cannot_reach_checkpoint_errors(self):
        secret = "SENSITIVE_CHECKOUT_REGISTRATION_BACKEND_CANARY"
        bindings = SqliteCheckoutWorkflowBindings(self.path)
        try:
            with closing(sqlite3.connect(self.path, check_same_thread=False)) as connection:
                saver = SqliteSaver(connection)
                for method in ("reserve", "command", "remember"):
                    class BrokenBindings:
                        def __getattr__(self, name):
                            if name == method:
                                def fail(*_args):
                                    raise RuntimeError(secret)
                                return fail
                            return getattr(bindings, name)
                    with self.assertRaises(WorkflowError) as failure:
                        flow = CheckoutWorkflow(self.client, self.workflow, self.purchase, self.template, saver, BrokenBindings())
                        flow.start()
                    self.assertEqual("CHECKOUT_BINDING_UNCONFIRMED", str(failure.exception))
                    self.assertIsNone(failure.exception.__context__)
                for checkpoint in saver.list(None):
                    self.assertNotIn(secret, repr(checkpoint))
                raw = b"\n".join(str(row).encode() for table in ("checkpoints", "writes")
                                 for row in connection.execute(f"SELECT * FROM {table}"))
                self.assertNotIn(secret.encode(), raw)
        finally:
            bindings.close()


if __name__ == "__main__":
    unittest.main()
