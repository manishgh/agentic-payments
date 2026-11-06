# Initial risk register

Status: documentation baseline; 2026-11-06. Owners are project roles, not external service commitments. Live integration risk does not block a clearly isolated simulator.

| ID | Risk | Owner | Mitigation / required evidence | Gate |
| --- | --- | --- | --- | --- |
| R-001 | Platform extension mistaken for native AP2/ACP support | Protocol architect | Pin upstream revisions; exact field/byte mappings and negative interoperability fixtures; scope claims per profile | G2-P |
| R-002 | More than one component initiates a charge | Core engineer | Immutable execution owner and attempt; recovery traces and concurrency tests | G2-S / G3 |
| R-003 | Timeout treated as safe failure | Core engineer | Durable unknown outcome, retained exposure, authoritative reconciliation, no blind route fallback | G2-S / G3 |
| R-004 | Two agents overspend a shared budget | Core engineer | Atomic reservations, exact money and contention tests | G2-S / G3 |
| R-005 | Revoked/stale authorization races dispatch | Security architect | Explicit admission linearization, stale-worker fencing, in-flight disclosure and action revalidation | G2-S / G3 |
| R-006 | Credentials or OTPs leak into graph traces | Security engineer | References-only graph contract, redaction and synthetic canary tests | G2-S / G3 |
| R-007 | Voucher partial success cannot be undone | Payment architect | Single-tender first profile; reject multi-leg execution until compensation/exception ownership designed | Profile-specific gate |
| R-008 | Adapter capability assumed across every region/method | Integration engineer | Account/region/method/action capability intersection and explicit unsupported errors | G2-S / G2-P |
| R-009 | Provider or partner access unavailable | Product/integration owner | Fake simulator coverage; record access status; no fabricated sandbox claims | External integration only |
| R-010 | Project conformance mistaken for legal/vendor certification | Release owner | Version/profile-scoped evidence; explicit exclusions and separate live gate | G3 / G5 / M3 |
| R-011 | Public packages distributed without chosen license | Product owner | License decision and dependency inventory before package/spec release | G5 |
| R-012 | A schedule implies unattended future execution | Integration owner | Relative effort plan and durable handoff; no automation claimed unless configured | Every status report |
| R-013 | Unbounded retained evidence exposes personal information | Security/operations owner | Sanitized simulator data, scoped access and retention design before real data | G2-P / M1 |
| R-014 | Missing local Java/Python tools mask untested implementation | Build owner | Explicit toolchain bootstrap and reproducible CI before implementation completion claims | First implementation slice |

## Baseline review evidence

The [initial architect review](reviews/0001-protocol-boundaries.md), [plan](roadmap.md), [requirements](../specification/0.1.0-draft/README.md) and [conformance cases](simulator-and-conformance.md) identify the corresponding controls. Findings are closed only by linked design or executable evidence, not by appearing in this register.
