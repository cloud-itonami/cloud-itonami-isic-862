# cloud-itonami-isic-862

**ISIC-862: Medical and dental practice activities — outpatient clinic coordination actor**

A langgraph-clj StateGraph actor for outpatient clinic (doctor/dentist office) back-office operations coordination. This is an **administrative/facility coordination actor only** — it has no clinical authority or decision-making power whatsoever.

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

- **`clinicops.store`** — append-only audit ledger and source-of-truth for appointments, providers, and resources (MemStore for demo, backend connectors in production)
- **`clinicops.advisor`** — LLM-backed intelligence node that drafts proposals (deterministic mock for demo, real LLM in production)
- **`clinicops.governor`** — independent governance layer enforcing three HARD checks and scope exclusions (never trusts advisor)
- **`clinicops.phase`** — 0→3 rollout policy (phase 0: read-only, phase 3: supervised auto-commit for 4 non-safety ops with always-escalating safety concerns)
- **`clinicops.operation`** — langgraph-clj StateGraph orchestrating intake → advise → govern → decide → commit | hold | escalate
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
# Install dependencies (requires nearby orgs/kotoba-lang/langgraph-clj and others)
clojure -M:deps

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Demo Output

The demo (`clinicops.sim/-main`) exercises:
- Phase 3 auto-commit for all 4 non-safety operations
- Phase 3 always-escalating safety-concern flag
- All HARD-hold scenarios: unregistered appointment, wrong `:effect`, scope-excluded clinical content
- Phase 1 approval-gating for all operations

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

## License

EPL-2.0 (matching cloud-itonami fleet governance)
