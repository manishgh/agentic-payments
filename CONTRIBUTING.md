# Contributing

The project is currently in documentation and design. Public licensing is not yet selected; do not describe this repository as licensed open source or publish packages until terms are established.

## Change workflow

1. Read README.md, AGENTS.md and the relevant backlog task.
2. Define a bounded change and its acceptance evidence.
3. Use a focused branch and commit; preserve unrelated work.
4. Update requirements/design/tests together when contracts change.
5. Obtain an independent review appropriate to the change.
6. Run applicable checks and record results in the PR.

Plan reviewer: senior engineer/product delivery reviewer using Sol 6.1 high. Design reviewer: senior architect using Astra for difficult protocol, concurrency or security questions. Code reviewer: separate senior engineer using Sol 6.1 high, escalating complex findings to Astra. An author cannot mark their own work independently reviewed.

## Publication

Remote: https://github.com/manishgh/agentic-payments.git. Push reviewed milestone branches; never rewrite remote history. Branch publication is not merge, deployment or live-payment authorization. Tags/packages require a passing versioned conformance report and resolved release terms.

## Required evidence

Documentation: working relative links, consistent statuses, no invented implementation claims, git diff whitespace check. Design: resolved review findings and explicit open limitations. Implementation: runnable build, relevant tests, failure/recovery cases and no secrets. Project conformance reports must name version, profile, adapter and test scope.
