"""Atomic workflow identity registration, separate from graph checkpoints and commerce truth."""
from __future__ import annotations

import sqlite3
from threading import RLock
from typing import Protocol

from workflows.payment_workflow import WorkflowError, canonical_uuid


class CheckoutWorkflowBindings(Protocol):
    def reserve(self, identity: str, fingerprint: str) -> None: ...
    def command(self, identity: str, fingerprint: str) -> str | None: ...
    def remember(self, identity: str, fingerprint: str, command_id: str) -> None: ...


class SqliteCheckoutWorkflowBindings:
    """Local reference adapter. Immutable CAS; no reset, delete or rebind operation."""
    def __init__(self, filename):
        self._lock = RLock()
        self._db = sqlite3.connect(str(filename), timeout=10, check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("CREATE TABLE IF NOT EXISTS checkout_workflow_bindings "
                         "(identity TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, command_id TEXT)")
        self._db.commit()

    def close(self):
        self._db.close()

    def reserve(self, identity, fingerprint):
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            self._db.execute("INSERT OR IGNORE INTO checkout_workflow_bindings VALUES(?,?,NULL)", (identity, fingerprint))
            self._read(identity, fingerprint)

    def _read(self, identity, fingerprint):
        row = self._db.execute("SELECT fingerprint,command_id FROM checkout_workflow_bindings WHERE identity=?", (identity,)).fetchone()
        if row is None or row[0] != fingerprint:
            raise WorkflowError("CHECKOUT_WORKFLOW_BINDING_CONFLICT")
        return row[1]

    def command(self, identity, fingerprint):
        with self._lock:
            value = self._read(identity, fingerprint)
            return None if value is None else canonical_uuid(value)

    def remember(self, identity, fingerprint, command_id):
        canonical_uuid(command_id)
        with self._lock, self._db:
            self._db.execute("BEGIN IMMEDIATE")
            previous = self._read(identity, fingerprint)
            if previous is not None and previous != command_id:
                raise WorkflowError("CHECKOUT_COMMAND_IDENTITY_CHANGED")
            self._db.execute("UPDATE checkout_workflow_bindings SET command_id=? WHERE identity=?", (command_id, identity))
