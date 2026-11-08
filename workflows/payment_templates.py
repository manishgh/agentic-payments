"""Immutable, versioned request policies; preparations contain only registered safe selectors."""
from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Iterable, Mapping, Protocol

from workflows.payment_workflow import PROFILES
from workflows.runtime_contract import (
    ACTIVITY_VERSION, EXTERNAL_ACTIVITY_VERSION, EXTERNAL_PROFILE, EXTERNAL_TEMPLATE_VERSION,
    TEMPLATE_VERSION, CheckoutSource, RUNTIME_CATALOG, StartWorkflow, fail,
)


@dataclass(frozen=True)
class TemplateKey:
    profile: str
    template_version: str
    activity_version: str


@dataclass(frozen=True)
class QuotePreparation:
    profile: str
    template_version: str
    activity_version: str
    operation: str
    checkout_source: CheckoutSource | None = None


class RequestTemplatePolicy(Protocol):
    def prepare(self, start: StartWorkflow) -> QuotePreparation: ...


class RequestTemplateCatalog(Protocol):
    def prepare(self, start: StartWorkflow) -> QuotePreparation: ...


def validate_preparation(start: StartWorkflow, prepared: QuotePreparation) -> QuotePreparation:
    """A replacement cannot redirect quoting or change any registered selector."""
    external = start.profile == EXTERNAL_PROFILE
    if (type(prepared) is not QuotePreparation or prepared.profile != start.profile
            or prepared.template_version != start.template_version
            or prepared.activity_version != start.activity_version
            or prepared.operation != ("quote_external" if external else "quote")
            or type(prepared.checkout_source) is not (CheckoutSource if external else type(None))
            or prepared.checkout_source != start.checkout_source):
        fail("INVALID_QUOTE_PREPARATION")
    if external:
        prepared.checkout_source.checked()
    return prepared


@dataclass(frozen=True)
class _FixturePolicy:
    def prepare(self, start):
        return QuotePreparation(start.profile, TEMPLATE_VERSION, ACTIVITY_VERSION, "quote")


@dataclass(frozen=True)
class _ExternalPolicy:
    def prepare(self, start):
        return QuotePreparation(EXTERNAL_PROFILE, EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION,
                                "quote_external", start.checkout_source)


_SUPPORTED_KEYS = frozenset(
    [TemplateKey(profile, TEMPLATE_VERSION, ACTIVITY_VERSION) for profile in PROFILES]
    + [TemplateKey(EXTERNAL_PROFILE, EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION)])


@dataclass(frozen=True, init=False)
class TemplateCatalog:
    """Explicit immutable registrations, without discovery, fallback, or replacement in place."""
    _entries: Mapping[TemplateKey, RequestTemplatePolicy]

    def __init__(self, registrations: Iterable[tuple[TemplateKey, RequestTemplatePolicy]]):
        entries = {}
        for key, policy in registrations:
            if (type(key) is not TemplateKey
                    or any(type(value) is not str for value in (key.profile, key.template_version, key.activity_version))
                    or key not in _SUPPORTED_KEYS or key in entries
                    or not callable(getattr(policy, "prepare", None))):
                fail("INVALID_TEMPLATE_REGISTRATION")
            entries[key] = policy
        object.__setattr__(self, "_entries", MappingProxyType(entries))

    def prepare(self, start: StartWorkflow) -> QuotePreparation:
        start.checked(RUNTIME_CATALOG.resolve(start.runtime).capabilities)
        policy = self._entries.get(TemplateKey(start.profile, start.template_version, start.activity_version))
        if policy is None:
            fail("UNSUPPORTED_REQUEST_TEMPLATE")
        return validate_preparation(start, policy.prepare(start))


def default_template_catalog() -> TemplateCatalog:
    return TemplateCatalog(
        [(TemplateKey(profile, TEMPLATE_VERSION, ACTIVITY_VERSION), _FixturePolicy()) for profile in PROFILES]
        + [(TemplateKey(EXTERNAL_PROFILE, EXTERNAL_TEMPLATE_VERSION, EXTERNAL_ACTIVITY_VERSION), _ExternalPolicy())])
