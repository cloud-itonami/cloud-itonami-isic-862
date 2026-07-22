# Operator Guide: Outpatient Clinic Operations Coordinator

## Overview

The Outpatient Clinic Operations Coordinator is a back-office coordination
actor that:

1. **Coordinates appointment/referral/supply/shift logistics** -- scheduling,
   referral paperwork handoff, non-clinical supply requests, administrative
   shift-roster proposals
2. **Escalates facility safety concerns** -- equipment malfunctions, facility
   hazards, always routed to a human
3. **Maintains transparency** -- an append-only audit ledger traces every
   commit/hold/approval-rejected decision

The actor is **not** a clinical decision-maker. It has **no clinical
authority or decision-making power whatsoever**: diagnosis, treatment,
medication, patient assessment, triage, and end-of-life decisions all remain
exclusively human/clinical staff authority. The actor **proposes**
administrative/logistics actions and escalates when human input is needed.

## Operating the Actor

### Prerequisites

1. **Appointment/Provider Registration** -- the target appointment or
   provider must already be `:registered?` AND `:verified?` in the Store
   before any proposal referencing it can proceed
2. **Authorized User** -- the operator must be authenticated and authorized
3. **Clear Request Type** -- specify what you're doing:
   - `:schedule-appointment` -- appointment scheduling/rescheduling logistics
   - `:coordinate-referral-logistics` -- referral paperwork/scheduling handoff
   - `:coordinate-supply-request` -- non-clinical supply coordination
   - `:schedule-staff-shift-proposal` -- administrative shift proposal
   - `:flag-safety-concern` -- facility/operational safety escalation

### Workflow

1. **Submit Request**
   ```clojure
   {:op :schedule-appointment
    :appt-id "appt-001"
    :patch {:new-time "14:30"}}
   ```

2. **Actor Processes** -- a compiled `langgraph-clj` `StateGraph`
   (`clinicops.operation/build`, run via `langgraph.graph/run*`):
   - `:intake` -- request enters the graph
   - `:advise` -- `ClinicAdvisor` drafts a proposal (`clinicops.advisor`)
   - `:govern` -- the independent Governor checks the three HARD
     invariants and the confidence/always-escalate gate
     (`clinicops.governor`)
   - `:decide` -- the rollout-phase policy is applied on top of the
     Governor's verdict (`clinicops.phase`)
   - `:request-approval` -- reached only when the disposition is
     `:escalate`; the graph is checkpointed and **paused** here
     (`interrupt-before`) until a human operator resumes it
   - `:commit` / `:hold` -- terminal nodes; every commit/hold/
     approval-rejected decision fact is appended to the Store's audit
     ledger (`clinicops.store/append-audit`)

   For backward compatibility with the pre-StateGraph call shape, this
   repo still exposes `clinicops.operation/process-request`
   (`advisor-inst governor-inst store-inst current-phase request`) as a
   convenience wrapper: it compiles and runs the graph to completion (or
   the first interrupt) in one call and translates the result back to
   `{:status :held|:escalated|:committed ...}`. New integrations should
   prefer `build` + `langgraph.graph/run*` directly, since only that path
   supports genuine human-in-the-loop resume.

3. **Outcomes**
   - **`:committed`** -- the proposal committed; a `:proposal-committed`
     audit fact lands in the ledger
   - **`:escalated`** -- the graph is paused at `:request-approval`
     pending a human decision (`:proposal-escalated` audit fact); resume
     with:
     ```clojure
     (langgraph.graph/run* actor
       {:approval {:status :approved|:rejected :by operator-id}}
       {:thread-id tid :resume? true})
     ```
   - **`:held`** -- a HARD Governor violation (`:proposal-rejected` audit
     fact, cites `:violations`) or an approver rejection
     (`:approval-rejected`) -- both land in the ledger

### Escalation Scenarios

**Automatic escalation (always human sign-off):**
- `:flag-safety-concern` -- never auto-commits at any rollout phase
- Low-confidence proposals (below `clinicops.governor/confidence-floor`)

**Hard blocks (no override):**
- Unregistered or unverified appointment/provider target
- Any proposal whose `:effect` isn't `:propose`
- Any proposal whose content touches diagnosis/treatment/medication/
  clinical-procedure/patient-assessment/triage/discharge/end-of-life/
  clinical-authority territory (maximally conservative EN+JA scan)

### Resuming Escalated Operations

`clinicops.operation/build` compiles a real `langgraph-clj` `StateGraph`
(`interrupt-before #{:request-approval}`, checkpoint-based resume). An
`:escalate` disposition means the graph run has been checkpointed and
**paused** at `:request-approval` -- not merely "the caller should not
commit": no further node runs until a human operator resumes the SAME
thread:

```clojure
;; kick off the operation -- may pause at :request-approval
(langgraph.graph/run* actor {:request request :phase current-phase} {:thread-id tid})

;; ... human review happens out of band ...

;; resume with a decision -- the graph continues from the checkpoint
(langgraph.graph/run* actor {:approval {:status :approved :by operator-id}}
                       {:thread-id tid :resume? true})
;; or, to reject:
(langgraph.graph/run* actor {:approval {:status :rejected :by operator-id}}
                       {:thread-id tid :resume? true})
```

`build`'s default `:checkpointer` is an in-memory
`langgraph.checkpoint/mem-checkpointer` (per-process only); production
deployments should pass a persistent checkpointer (see
`langgraph.checkpoint/datomic-checkpointer`) so a paused operation survives
a process restart.

## Audit & Transparency

Every graph run accumulates an `:audit` vector (proposal/decision facts).
The `:commit` and `:hold` terminal nodes append the resulting decision fact
to the Store's append-only ledger (`clinicops.store/append-audit`)
themselves -- ledger-writing is not a caller responsibility;
`(store/audit-log store)` is always the authoritative, immutable record of
every committed/rejected/escalated decision.

- Every proposal produces a trace, regardless of outcome
- Every hold cites the specific Governor rule(s) violated (`:violations`)
- Every escalation cites its reason (confidence or always-escalate op)

## Integration

The actor provides a standard protocol (`clinicops.store/Store`) for backend
integration:

- **Appointment lookup** -- `(store/appointment store appt-id)`
- **Provider lookup** -- `(store/provider store provider-id)`
- **Ledger read** -- `(store/audit-log store)`
- **Ledger append** -- `(store/append-audit store event)` (called by the
  compiled graph's `:commit`/`:hold` nodes; not normally called directly)

Implementations include in-memory `MemStore` (default, `clinicops.store`)
and `DatomicStore` (`langchain.db`-backed via `kotoba-lang/langchain-store`,
the same seam point all cloud-itonami actors use) -- both pass the same
store-contract test (`test/clinicops/store_contract_test.clj`).

## Safety Guarantees

- **No clinical decisions** -- diagnosis, treatment, medication, and
  patient-assessment decisions remain exclusively human/clinical authority
- **No suppressed safety concerns** -- facility safety concerns cannot be
  hidden or delayed; `:flag-safety-concern` always escalates
- **No unlogged operations** -- every commit/hold/approval-rejected decision
  is recorded in the audit ledger
- **No direct execution** -- the governor gates every proposal; the actor
  never directly actuates anything

The actor is safe because:
1. It never decides clinically -- it proposes administrative logistics only
2. It always escalates safety concerns and low-confidence proposals
3. It never hides information -- the audit ledger is append-only
4. Every action is auditable
