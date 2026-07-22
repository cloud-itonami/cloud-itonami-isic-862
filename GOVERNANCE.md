# Governance: cloud-itonami-isic-862 Governor

The actor enforces three HARD, permanent, un-overridable checks via `clinicops.governor`.

## Hard Check 1: Appointment/Resource Unverified

- **Scope**: `:schedule-appointment` and any op referencing an appointment/provider record
- **Rule**: Target must exist AND be independently `:registered?` AND `:verified?` in the Store, re-derived from the Store's own fields (never trusts the proposal's own claim)
- **Bypass**: None

## Hard Check 2: Effect Not `:propose`

- **Scope**: All operations
- **Rule**: Effect must be `:propose`
- **Other values**: Rejected outright -- any other effect value is, by construction, a claim to directly actuate/commit outside governance
- **Bypass**: None

## Hard Check 3: Scope Exclusion

- **Blocked content** (maximally conservative -- clinics are inherently clinical settings):
  - Diagnosis / clinical assessment
  - Treatment planning / care-plan changes
  - Medication administration, dosing, prescribing, pharmaceutical handling
  - Clinical procedures (surgical, extraction, implant, filling, crown, root canal)
  - Patient assessment / vital-signs monitoring
  - Triage, admission, discharge decisions
  - Physical restraint, seclusion, sedation
  - End-of-life / DNR / advance-directive / code-status decisions
  - Clinical-authority overrides (license suspension, compliance enforcement, investigations)
- **Pattern**: EN+JA substring scan across the proposal's op/summary/rationale/cites/value (`clinicops.governor/scope-excluded-terms`)
- **Allowed operations** (closed allowlist):
  1. `:schedule-appointment` -- appointment scheduling/rescheduling logistics
  2. `:coordinate-referral-logistics` -- administrative referral paperwork/scheduling handoff
  3. `:coordinate-supply-request` -- non-clinical consumable supply coordination
  4. `:schedule-staff-shift-proposal` -- administrative shift proposal (never binding)
  5. `:flag-safety-concern` -- facility/operational safety escalation (always escalates)
- **Legitimate escalation**: `:flag-safety-concern` can describe a facility hazard (e.g. "elevator malfunction") without self-blocking, since the scope-exclusion scan targets clinical/patient-care content, not the word "safety" itself
- **Bypass**: None

## Escalation Gate (Soft, Human Sign-Off)

- `:flag-safety-concern` -- ALWAYS escalates, regardless of confidence
- LLM confidence below the Governor's floor (`clinicops.governor/confidence-floor`)

## Operation Phases

- **Phase 0** (read-only): audit only, every proposal escalates
- **Phase 1** (assisted-logistics): appointment scheduling only, approval-gated
- **Phase 2** (assisted-coordination): + referral + supply + shift, still approval-gated
- **Phase 3** (supervised-auto): 4 non-safety ops auto-commit when clean; `:flag-safety-concern` never auto-commits at any phase

## Enforcement

The Governor is applied inside the compiled `langgraph-clj` StateGraph's `:govern` node, before any proposal can reach `:decide`/`:commit`. All three HARD checks must pass; any failure immediately routes to `:hold` with no override mechanism.

**No override mechanism exists.** This is intentional and by design.
