# Security Policy

## Threat Model

This actor handles administrative coordination for outpatient clinic logistics. The Governor enforces three HARD checks to prevent scope expansion into clinical diagnosis, treatment, or patient-care domains:

1. **Scope Boundary**: All proposals touching diagnosis, treatment, medication, clinical procedures, patient assessment, triage, discharge, vital signs, end-of-life, or clinical authority are rejected via a maximally conservative EN+JA substring scan. This is NOT a clinical safety review -- it is a scope boundary.
2. **Escalation**: `:flag-safety-concern` operations always escalate to human review. No auto-commit occurs.
3. **Verification**: Appointment/provider targets must be independently verified (`:registered?` AND `:verified?`) before operations proceed.

## No Liability

This actor is **administrative coordination only** and has no clinical authority or decision-making power whatsoever. Deployment requires:
- Local healthcare regulatory compliance review (HIPAA/patient-privacy equivalents vary by jurisdiction)
- Human-review infrastructure for `:flag-safety-concern` escalations
- Independent clinical oversight (this actor is not a substitute for clinical judgment or regulatory oversight)

## Responsible Disclosure

If you discover a security issue, please email security@junkawasaki.com with:
- Description of the issue
- Steps to reproduce
- Impact assessment

## No Warranty

This software is provided AS-IS. See LICENSE (AGPL-3.0-or-later).
