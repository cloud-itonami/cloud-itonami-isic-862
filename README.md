# cloud-itonami-isic-862

**ISIC-862: Medical and dental practice activities — outpatient clinic coordination actor**

**Maturity: `:implemented`.** `src/clinicops/` implements the `ClinicAdvisor`
(`clinicops.advisor`, a real `Advisor` protocol + `MockAdvisor`) and the
independent Governor (`clinicops.governor`), composed by `clinicops.operation`
following the itonami actor pattern: `intake -> advise -> govern -> decide ->
commit | request-approval -> commit | hold`, compiled to a real
`langgraph-clj` `StateGraph` (`langgraph.graph/state-graph` + `compile-graph`,
mirroring `cerealops.operation`, cloud-itonami-isic-0111) with
`interrupt-before #{:request-approval}` and checkpoint-based human-in-the-loop
resume for escalated operations. Every commit/hold/approval-rejected decision
fact is appended to `clinicops.store`'s append-only audit ledger
(`audit-log`/`append-audit`), implemented on both `MemStore` (now atom-backed)
and a `DatomicStore` (backed by `langchain.db` via `kotoba-lang/langchain-store`)
that pass the same store-contract test (`test/clinicops/store_contract_test.clj`).
15 tests / 112 assertions green (`clojure -M:dev:test`); the demo runner
(`clojure -M:dev:run`) drives the actor through commit, escalate, and
hard-hold paths, printing each decision.

This is an **administrative/facility coordination actor only** — it has no clinical authority or decision-making power whatsoever.

## Scope

This actor coordinates the logistics and administration of clinic operations:
- **Appointment scheduling** — appointment scheduling/rescheduling logistics (never clinical triage or urgency decisions)
- **Referral coordination logistics** — administrative logistics of sending/receiving referrals (paperwork, scheduling handoff — never the clinical decision to refer or reviewing referral clinical content)
- **Non-clinical supply coordination** — office supplies, administrative forms (never medication or medical/dental equipment or instruments)
- **Administrative staff shifts** — administrative shift-roster proposals (never clinical staffing adequacy decisions)
- **Facility safety flagging** — equipment maintenance, facility hazards (never patient-safety or clinical-emergency content)

### Permanently out-of-scope

- Diagnosis, clinical assessment, or treatment planning
- Medication administration, prescribing, or pharmaceutical handling
- Patient safety decisions, clinical triage, admission/discharge decisions
- Vital signs monitoring or patient assessment
- Clinical procedures or techniques
- Physical restraint or seclusion decisions
- End-of-life or DNR decisions
- Any clinical-authority overrides

## Module Architecture

- **`clinicops.store`** — `Store` protocol: appointment/provider directory + append-only
  audit ledger, implemented by `MemStore` (in-memory, default) and `DatomicStore`
  (`langchain.db`-backed, via `kotoba-lang/langchain-store`)
- **`clinicops.advisor`** — `Advisor` protocol + `MockAdvisor` (deterministic mock for
  demo; a real-LLM `Advisor` implementation is the documented next seam, same as
  every sibling cloud-itonami actor's advisor)
- **`clinicops.governor`** — independent governance layer enforcing three HARD checks
  and scope exclusions (never trusts the advisor)
- **`clinicops.phase`** — 0→3 rollout policy (phase 0: read-only, phase 3: supervised
  auto-commit for 4 non-safety ops with always-escalating safety concerns)
- **`clinicops.operation`** — compiles the `langgraph-clj` `StateGraph`: advise → govern
  → decide → commit | request-approval → commit | hold, with `interrupt-before` +
  checkpoint-based resume for escalated operations. `process-request` is kept as a
  backward-compatible convenience wrapper around the compiled graph.
- **`clinicops.sim`** — demo driver exercising all scenarios

## Three HARD Checks (Permanent, Un-Overridable)

1. **Appointment-slot/resource unverified** — target appointment/provider record must exist AND be independently `:registered?`/`:verified?` in the store before any proposal may proceed
2. **Effect not `:propose`** — every proposal must have `:effect :propose`; any other value is rejected outright
3. **Scope exclusion** — any proposal with content touching diagnosis, treatment, medication, clinical procedures, patient assessment, triage, discharge, vital signs, end-of-life, or clinical authority is HARD-blocked via substring scan

## Phase Rollout

- **Phase 0** (read-only): audit only; no proposals accepted
- **Phase 1** (assisted-logistics): appointment scheduling only, approval-gated
- **Phase 2** (assisted-coordination): appointment + referral + supply + shift, still approval-gated
- **Phase 3** (supervised-auto): 4 non-safety ops auto-commit when clean; `:flag-safety-concern` **always escalates** to human, never auto-commits

## Build & Test

```bash
# Run tests (langgraph/langchain-store resolved via local sibling checkouts)
clojure -M:dev:test

# Run the linter (clj-kondo, 0 errors)
clojure -M:lint

# Run the demo -- drives the compiled StateGraph end-to-end
clojure -M:dev:run
```

`:dev` pins the transitive `langchain` dependency to the in-monorepo local
checkout (`../../kotoba-lang/langchain`) for offline workspace development;
a standalone fork should override `deps.edn`'s `:local/root` coordinates
with git coordinates (see `deps.edn`'s own comment).

## Demo Output

The demo (`clinicops.sim/-main`) exercises:
- Phase 3 auto-commit for all 4 non-safety operations
- Phase 3 always-escalating safety-concern flag
- HARD-hold scenarios: unregistered appointment, scope-excluded clinical content
- Phase 1 approval-gating for all operations

Every commit/escalate/hold decision in the demo appends a real fact to the
Store's audit ledger (`clinicops.store/audit-log`) via the compiled graph's
`:commit`/`:hold` nodes.

## Human-in-the-loop resume

`process-request` is a backward-compatible wrapper that runs the compiled
graph to completion (or first interrupt) in one call. For a genuine
approve/reject resume cycle, use `clinicops.operation/build` +
`langgraph.graph/run*` directly — see [`docs/operator-guide.md`](docs/operator-guide.md)
and `test/clinicops/operation_graph_test.clj`.

## Differences from isic-861 (Hospital Coordination)

While both hospitals (861) and outpatient clinics (862) are clinically-central industries, this actor is adapted for clinic-specific logistics:
- **Operations reframed**: "appointment scheduling" instead of "bed assignment", "referral logistics" instead of "visitor access"
- **Same governance discipline**: three HARD checks, same phase rollout, safety concerns always escalate
- **Same scope-exclusion tuning**: maximally conservative clinical-content scanning, verified to not self-block legitimate facility safety concerns

## References

- ADR-2607152800: Cloud-itonami ISIC-861 Hospital Coordination (sibling, reference implementation)
- ADR-2607152700: Cloud-itonami ISIC-873 Residential Care Coordination
- ADR-2607121000: Cloud-itonami Wave Definition (outpatient clinics in Wave 4)
- ADR-2607152500: Wave 4 Rollout Amendment (quality guardrails)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor)

## License

AGPL-3.0-or-later.
