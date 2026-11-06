"""Durable synthetic sidecar identities. No dispatcher or payment facade exists."""

from contextlib import closing
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import sqlite3
import uuid

from . import LabRefusal
from .binding import ContentBinding
from .contracts import FrozenAcpCheckout, identity
from .jws import VerifiedCheckoutArtifact


@dataclass(frozen=True)
class LabScope:
    tenant_id: str
    run_id: str
    principal_id: str

    def __post_init__(self):
        for value in (self.tenant_id, self.run_id, self.principal_id):
            identity(value)


@dataclass(frozen=True)
class LabOperation:
    operation_id: str
    owner: str
    state: str
    commerce_state: str
    binding_id: str
    artifact_id: str
    financial_state: str = "UNKNOWN"
    native_ap2_authority_verified: bool = False


class SyntheticSidecarLedger:
    def __init__(self, database: Path):
        self._path = str(database)
        try:
            with closing(sqlite3.connect(self._path)) as connection:
                connection.executescript("""
                CREATE TABLE IF NOT EXISTS checkouts(
                    tenant TEXT NOT NULL, run TEXT NOT NULL, merchant TEXT NOT NULL, session TEXT NOT NULL,
                    revision INTEGER NOT NULL, artifact TEXT NOT NULL, payload TEXT NOT NULL,
                    binding TEXT, owner TEXT,
                    PRIMARY KEY(tenant,run,merchant,session));
                CREATE TABLE IF NOT EXISTS operations(
                    tenant TEXT NOT NULL, run TEXT NOT NULL, merchant TEXT NOT NULL, session TEXT NOT NULL,
                    operation TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, binding TEXT NOT NULL,
                    artifact TEXT NOT NULL, state TEXT NOT NULL, commerce TEXT NOT NULL,
                    PRIMARY KEY(tenant,run,merchant,session));
                CREATE TABLE IF NOT EXISTS commands(
                    tenant TEXT NOT NULL, run TEXT NOT NULL, principal TEXT NOT NULL,
                    merchant TEXT NOT NULL, session TEXT NOT NULL, command TEXT NOT NULL,
                    command_key TEXT NOT NULL, digest TEXT NOT NULL, response TEXT NOT NULL,
                    PRIMARY KEY(tenant,run,principal,merchant,session,command,command_key));
                """)
        except sqlite3.Error:
            raise LabRefusal("SIDECAR_STORAGE_UNAVAILABLE") from None

    def _transaction(self, action):
        try:
            with closing(sqlite3.connect(self._path, isolation_level=None, timeout=10)) as connection:
                connection.row_factory = sqlite3.Row
                connection.execute("BEGIN IMMEDIATE")
                try:
                    result = action(connection)
                    connection.commit()
                    return result
                except BaseException:
                    connection.rollback()
                    raise
        except sqlite3.Error:
            raise LabRefusal("SIDECAR_STORAGE_UNAVAILABLE") from None

    @staticmethod
    def _scope(scope, merchant, session):
        identity(merchant)
        identity(session)
        return (scope.tenant_id, scope.run_id, merchant, session)

    def register_checkout(self, scope: LabScope, frozen: FrozenAcpCheckout, artifact: VerifiedCheckoutArtifact, now):
        frozen.assert_current(now)
        if (artifact.merchant_id, artifact.origin, artifact.session_id, artifact.revision, artifact.payload_sha256) != (
                frozen.merchant_id, frozen.origin, frozen.session_id, frozen.revision, frozen.payload_sha256):
            raise LabRefusal("SIDECAR_ARTIFACT_CONTEXT_CHANGED")
        key = self._scope(scope, frozen.merchant_id, frozen.session_id)

        def register(connection):
            current = connection.execute("SELECT * FROM checkouts WHERE tenant=? AND run=? AND merchant=? AND session=?", key).fetchone()
            if current:
                if (current["revision"], current["artifact"], current["payload"]) == (frozen.revision, artifact.artifact_id, frozen.payload_sha256):
                    return
                if frozen.revision <= current["revision"]:
                    raise LabRefusal("SIDECAR_CHECKOUT_REVISION_CONFLICT")
                # Any changed snapshot invalidates prior bindings; an already
                # created operation remains retained and can never be replaced.
                connection.execute("UPDATE checkouts SET revision=?,artifact=?,payload=?,binding=NULL,owner=NULL WHERE tenant=? AND run=? AND merchant=? AND session=?",
                                   (frozen.revision, artifact.artifact_id, frozen.payload_sha256, *key))
            else:
                connection.execute("INSERT INTO checkouts VALUES(?,?,?,?,?,?,?,NULL,NULL)",
                                   (*key, frozen.revision, artifact.artifact_id, frozen.payload_sha256))
        self._transaction(register)

    def _current(self, connection, scope, binding):
        key = self._scope(scope, binding.merchant_id, binding.session_id)
        current = connection.execute("SELECT * FROM checkouts WHERE tenant=? AND run=? AND merchant=? AND session=?", key).fetchone()
        if current is None:
            raise LabRefusal("SIDECAR_RESOURCE_NOT_FOUND")
        if (current["revision"], current["artifact"], current["payload"]) != (binding.revision, binding.artifact_id, binding.payload_sha256):
            raise LabRefusal("SIDECAR_CHECKOUT_CHANGED")
        if binding.authority != "CONTENT_BOUND_AUTHORITY_UNVERIFIED":
            raise LabRefusal("SIDECAR_AUTHORITY_CLAIM_REFUSED")
        return key, current

    def _command(self, connection, scope, binding, owner, command, key, action):
        if not isinstance(key, str) or not 16 <= len(key) <= 128 or any(not 32 <= ord(char) <= 126 for char in key):
            raise LabRefusal("SIDECAR_COMMAND_KEY_INVALID")
        identity(owner)
        digest = hashlib.sha256(json.dumps([binding.binding_id, owner], separators=(",", ":")).encode()).hexdigest()
        command_scope = (scope.tenant_id, scope.run_id, scope.principal_id, binding.merchant_id, binding.session_id, command, key)
        stored = connection.execute("SELECT digest,response FROM commands WHERE tenant=? AND run=? AND principal=? AND merchant=? AND session=? AND command=? AND command_key=?", command_scope).fetchone()
        if stored:
            if stored["digest"] != digest:
                raise LabRefusal("SIDECAR_IDEMPOTENCY_CONFLICT")
            return json.loads(stored["response"])
        response = action()
        connection.execute("INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)", (*command_scope, digest, json.dumps(response, sort_keys=True)))
        return response

    def bind(self, scope: LabScope, binding: ContentBinding, owner: str, key: str, now):
        binding.assert_active(now)
        def bind_current(connection):
            scoped, current = self._current(connection, scope, binding)

            def mutate():
                operation = connection.execute("SELECT * FROM operations WHERE tenant=? AND run=? AND merchant=? AND session=?", scoped).fetchone()
                if operation and (operation["binding"] != binding.binding_id or operation["owner"] != owner):
                    raise LabRefusal("SIDECAR_EXISTING_OPERATION_UNRESOLVED")
                if current["binding"] and (current["binding"] != binding.binding_id or current["owner"] != owner):
                    raise LabRefusal("SIDECAR_OWNER_OR_BINDING_CONFLICT")
                connection.execute("UPDATE checkouts SET binding=?,owner=? WHERE tenant=? AND run=? AND merchant=? AND session=?", (binding.binding_id, owner, *scoped))
                return {"binding_id": binding.binding_id, "owner": owner, "authority": binding.authority}
            return self._command(connection, scope, binding, owner, "bind", key, mutate)
        return self._transaction(bind_current)

    def begin_completion(self, scope: LabScope, binding: ContentBinding, owner: str, key: str, now) -> LabOperation:
        binding.assert_active(now)
        def begin(connection):
            scoped, current = self._current(connection, scope, binding)
            if current["binding"] != binding.binding_id or current["owner"] != owner:
                raise LabRefusal("SIDECAR_OWNER_OR_BINDING_CONFLICT")

            def mutate():
                operation = connection.execute("SELECT * FROM operations WHERE tenant=? AND run=? AND merchant=? AND session=?", scoped).fetchone()
                if operation:
                    if operation["binding"] != binding.binding_id or operation["owner"] != owner:
                        raise LabRefusal("SIDECAR_EXISTING_OPERATION_UNRESOLVED")
                    return dict(operation)
                operation_id = str(uuid.uuid4())
                connection.execute("INSERT INTO operations VALUES(?,?,?,?,?,?,?,?,?,?)", (*scoped, operation_id, owner, binding.binding_id, binding.artifact_id, "IN_PROGRESS", "ready_for_payment"))
                return dict(connection.execute("SELECT * FROM operations WHERE tenant=? AND run=? AND merchant=? AND session=?", scoped).fetchone())
            response = self._command(connection, scope, binding, owner, "complete", key, mutate)
            return LabOperation(response["operation"], response["owner"], response["state"], response["commerce"],
                                response["binding"], response["artifact"])
        return self._transaction(begin)

    def observe(self, scope: LabScope, merchant: str, session: str, operation_id: str, commerce_state: str, timed_out: bool = False) -> LabOperation:
        projection = project_state(commerce_state, admitted=True)
        scoped = self._scope(scope, merchant, session)

        def update(connection):
            operation = connection.execute("SELECT * FROM operations WHERE tenant=? AND run=? AND merchant=? AND session=?", scoped).fetchone()
            if operation is None or operation["operation"] != operation_id:
                raise LabRefusal("SIDECAR_RESOURCE_NOT_FOUND")
            state = "UNKNOWN" if timed_out or projection["financial_state"] == "UNKNOWN" else operation["state"]
            # No commerce state can establish financial success in this lab.
            connection.execute("UPDATE operations SET state=?,commerce=? WHERE tenant=? AND run=? AND merchant=? AND session=?", (state, commerce_state, *scoped))
            return LabOperation(operation_id, operation["owner"], state, commerce_state,
                                operation["binding"], operation["artifact"])
        return self._transaction(update)


def project_state(acp_state: str, admitted: bool = False) -> dict:
    states = {"incomplete", "not_ready_for_payment", "requires_escalation", "authentication_required",
              "ready_for_payment", "pending_approval", "complete_in_progress", "completed", "canceled", "in_progress", "expired"}
    if acp_state not in states:
        raise LabRefusal("ACP_STATE_REFUSED")
    return {"commerce_state": acp_state, "financial_state": "UNKNOWN" if admitted else "NOT_OBSERVED",
            "authentication_blocked": acp_state == "authentication_required", "release_permitted": False,
            "native_ap2_authority_verified": False}
