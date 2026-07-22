# Business Model: Outpatient Clinic Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-862`
- ISIC Rev. 4: `862`
- Industry: Medical and dental practice activities (outpatient clinic)
- Social impact: operational-efficiency, accessibility, staff-coordination, facility-safety

## Customer

- Independent doctor/dentist practices and small outpatient clinics
- Multi-provider group practices needing shared appointment/referral coordination
- Dental offices (general practice + dentistry specialties, per this repo's demo data)
- Clinic administrators who currently coordinate scheduling/referrals/supplies by
  phone/paper and want an auditable digital record without touching clinical systems

## Offer

- Appointment scheduling/rescheduling logistics coordination
- Referral coordination logistics (paperwork, scheduling handoff)
- Non-clinical supply request coordination (office supplies, administrative forms)
- Administrative staff-shift roster proposals
- Facility/operational safety-concern flagging with mandatory human escalation
- Append-only audit trail of every coordination decision

## Revenue

- SaaS subscription (per-provider or per-clinic pricing)
- Integration fees for connecting to existing calendar/practice-management systems
- API access for multi-clinic group practices
- Audit/compliance reporting add-ons

## Trust Controls

- No clinical authority of any kind -- diagnosis, treatment, medication, and
  patient-assessment decisions remain exclusively human/clinical staff
- Every proposal is a proposal (`:effect :propose`), never a direct actuation
- Appointment/provider targets must be independently verified before any
  proposal referencing them can proceed
- `:flag-safety-concern` always escalates to a human -- confidence is never
  sufficient for a facility-safety decision to auto-commit
- A maximally conservative EN+JA scope-exclusion scan blocks any proposal
  whose content drifts toward diagnosis/treatment/medication/clinical-procedure/
  patient-assessment/triage/discharge/end-of-life/clinical-authority territory,
  even if a human operator's free-text patch accidentally introduces it
- Audit ledger is append-only and never editable

## What we do NOT do

- **Clinical diagnosis, assessment, or treatment planning** -- exclusively
  clinician authority
- **Medication administration, dosing, or prescribing** -- exclusively
  clinician/pharmacist authority
- **Patient safety, triage, or admission/discharge decisions** -- exclusively
  clinical staff authority
- **Clinical procedures or techniques** -- exclusively clinician authority
- **Clinical staffing-adequacy decisions** -- this actor proposes
  administrative shift rosters only, never judges clinical coverage adequacy
- **End-of-life / DNR / code-status decisions** -- exclusively clinician/
  patient/family authority

## Supported Operations

### Appointment Scheduling
- Propose new appointment times / reschedules for an already-registered,
  already-verified appointment record
- Never a clinical priority, urgency, or medical-necessity judgment

### Referral Coordination Logistics
- Propose the administrative paperwork/scheduling handoff for a referral
- Never the clinical decision to refer, never reviews referral clinical content

### Non-Clinical Supply Coordination
- Office supplies, administrative forms
- Never medication, medical devices, or clinical instruments/equipment

### Administrative Staff Shift Proposals
- Propose an administrative-staff shift roster entry
- Never a binding decision -- a human shift supervisor finalizes it
- Never a clinical-staffing-adequacy judgment

### Facility Safety-Concern Escalation
- Flag equipment malfunctions, facility hazards, and similar operational
  safety concerns
- ALWAYS escalates to a human -- never a patient-safety or clinical-emergency
  channel
