# Decision register

Updated 2026-11-06. Accepted records reflect user direction; proposed choices need design evidence, not an automatic new permission request.

| ID | Status | Decision / next evidence |
| --- | --- | --- |
| DEC-001 | Accepted | Name and GitHub repository: agentic-payments, manishgh/agentic-payments. |
| DEC-002 | Accepted | AP2-based platform payment-intent encloses embedded OpenAI ACP checkout; 3DS/provider authentication is part of payment execution. |
| DEC-003 | Accepted | Build documentation, then reviewed design, then tested implementation. |
| DEC-004 | Accepted | Deliver versioned extension specification, adapter SDK, simulator, project conformance suite and reference implementation. |
| DEC-005 | Accepted | Extensibility covers providers, payment methods, credentials, customer actions and stored-value allocations. |
| DEC-006 | Scoped design accepted | Java core/SDK, Python LangGraph, TypeScript UI, PostgreSQL. ADR 0001 selects Java21 LTS/Maven3.9.16/PG17.11 targets for first-slice design; verified distribution/dependency locks and passing build evidence now exist in toolchain.md and component locks. |
| DEC-007 | Accepted | Use Sol 6.1 high for bounded work; Astra for complex architectural/security questions. Separate plan, design and code reviewers from authors. |
| DEC-008 | Accepted | Push reviewed milestone branches incrementally to the supplied remote. Do not force-push, auto-merge or deploy. |
| DEC-009 | Implemented locally | One repository; modular core plus separate LangGraph process. Protocol contracts remain language-independent. |
| DEC-010 | Pending | Public license and patent/contribution terms. No license is selected or represented as granted. Does not block local docs/design. |
| DEC-011 | Pending evidence | Pin exact AP2/ACP versions and normative artifacts; map signed bytes, trust, methods and continuation. |
| DEC-012 | Implemented locally | Simulator-first developer preview; sandbox adapters follow; managed/live service has separate gates. |
| DEC-013 | Pending evidence | First live region/provider account. Design both EU and US profiles without claiming access or regulatory approval. |
| DEC-014 | Scoped design accepted | Original G2-S limited to reservation; separately accepted ADR0002/review0005 extended local execution scope. Reviews0006/0007 cover implemented source. Real integration remains G2-P. |

## Decision discipline

Record significant design choices in docs/adr with context, alternatives, consequences, validation and unresolved limits. Each accepted design decision must map to requirements and test cases. A draft schema is not an accepted external standard. Naming a capability does not implement it.
