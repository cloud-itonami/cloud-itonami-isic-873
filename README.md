# cloud-itonami-isic-873

**Residential Care Activities for the Elderly and Disabled** — ISIC Rev.4 class 873.

A coordination-only actor for elderly/disabled residential care facilities, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-care-note, schedule-family-visit, coordinate-supply-request, schedule-staff-shift-proposal, flag-safety-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Resident verified** — target must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — medication, clinical diagnosis, care-plan changes, physical restraint, end-of-life decisions, and safety-authority overrides are permanently blocked.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: care-note logging only (approval-gated)
  - Phase 2: + family visit, supply, shift proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (safety concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/eldercareops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/eldercareops/advisor_test.clj` — advisor proposal shape and consistency
- `test/eldercareops/phase_test.clj` — rollout phase logic
- `test/eldercareops/governor_contract_test.clj` — full graph integration, audit trail
- `test/eldercareops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `eldercareops.store` — SSoT (MemStore, String-keyed resident directory, append-only ledger)
- `eldercareops.advisor` — contained intelligence node (mock + real-LLM seam)
- `eldercareops.governor` — independent compliance layer
- `eldercareops.phase` — staged rollout (0→3)
- `eldercareops.operation` — langgraph-clj StateGraph
- `eldercareops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and ADR-2607152300 for design decisions.
