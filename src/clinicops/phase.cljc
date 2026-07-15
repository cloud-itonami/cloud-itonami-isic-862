(ns clinicops.phase
  "ClinicPhase -- the 0→3 rollout policy for ISIC-862 clinic operations.

  Phase 0: read-only audit of historical proposals
  Phase 1: assisted logistics (appointment scheduling only, approval-gated)
  Phase 2: assisted coordination (+ referral/supply/shift, still approval-gated)
  Phase 3: supervised auto (4 non-safety ops auto-commit when clean,
           safety concerns always escalate to human)

  :flag-safety-concern is NEVER auto-committed at any phase -- two layers
  (governor + phase) enforce the same invariant.")

(def phase-config
  {0 {:name :read-only
      :description "Audit-only; no proposals accepted"
      :auto #{}
      :escalate #{:schedule-appointment :coordinate-referral-logistics
                  :coordinate-supply-request :schedule-staff-shift-proposal
                  :flag-safety-concern}}

   1 {:name :assisted-logistics-phase-1
      :description "Appointment scheduling only, approval-gated"
      :auto #{}
      :escalate #{:schedule-appointment :coordinate-referral-logistics
                  :coordinate-supply-request :schedule-staff-shift-proposal
                  :flag-safety-concern}}

   2 {:name :assisted-coordination-phase-2
      :description "Appointment + referral + supply + shift, still approval-gated"
      :auto #{}
      :escalate #{:schedule-appointment :coordinate-referral-logistics
                  :coordinate-supply-request :schedule-staff-shift-proposal
                  :flag-safety-concern}}

   3 {:name :supervised-auto-phase-3
      :description "4 non-safety ops auto-commit when clean, safety always escalates"
      :auto #{:schedule-appointment :coordinate-referral-logistics
              :coordinate-supply-request :schedule-staff-shift-proposal}
      :escalate #{:flag-safety-concern}}})

(defn phase-for
  "Get phase config for the given phase number."
  [phase-num]
  (get phase-config phase-num))

(defn can-auto-commit?
  "Check if the given op can auto-commit in this phase."
  [phase-num op]
  (let [config (phase-for phase-num)]
    (contains? (:auto config) op)))

(defn should-escalate?
  "Check if the given op should escalate in this phase."
  [phase-num op]
  (let [config (phase-for phase-num)]
    (contains? (:escalate config) op)))
