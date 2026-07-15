(ns clinicops.governor
  "ClinicGovernor -- the independent compliance layer for
  ISIC-862 outpatient clinic operations coordination. The advisor has no
  notion of whether an appointment/resource is actually registered and
  verified, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently drifted
  into a permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- ADMINISTRATIVE/FACILITY
  COORDINATION ONLY (appointment scheduling, referral coordination logistics,
  non-clinical supply coordination, staff shift proposals, facility safety-concern
  flagging). It NEVER performs or authorizes:
    - diagnosis, assessment, or clinical decision-making
    - treatment planning or care-plan changes
    - medication administration, dosing, prescribing, or any pharma handling
    - medical procedures (clinical techniques, instrument use, etc.)
    - patient safety decisions, triage, admission/discharge decisions
    - vital signs monitoring or patient assessment
    - physical restraint, seclusion, or mobility restrictions
    - end-of-life or DNR decisions
    - any clinical-authority overrides

  Clinics are inherently clinical settings, so scope exclusions are
  MAXIMALLY CONSERVATIVE -- any clinical/patient-care content
  whatsoever (even phrased as a \"safety concern\") is a HARD block.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Appointment-slot/resource unverified    -- the target appointment/
                                          provider/resource record must
                                          exist AND be independently
                                          confirmed :registered?/:verified?
                                          in the store before ANY proposal
                                          for it may commit or even escalate.
                                          Never trusts a proposal's own claim
                                          about the resource -- re-derived from
                                          the resource's own store record.
    2. Effect not :propose              -- every proposal's :effect MUST
                                          be :propose. Any other effect value
                                          is, by construction, a claim to
                                          directly actuate/commit outside
                                          governance -- HARD block.
    3. Scope exclusion                  -- ANY proposal (regardless of op)
                                          whose op, rationale, summary,
                                          citations or draft value touches
                                          diagnosis/treatment/medication/
                                          clinical-decision/patient-care/
                                          clinical-procedure/triage/discharge/
                                          vital-signs/end-of-life/clinical-
                                          authority territory is a HARD,
                                          PERMANENT block. Clinics are
                                          clinically central, so MAXIMALLY-
                                          CONSERVATIVE scope exclusions apply.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is :flag-safety-concern -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `clinicops.phase` independently agrees: :flag-safety-concern is
  never a member of any phase's :auto set either -- two layers, not one."
  (:require [clojure.string :as str]
            [clinicops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist for clinic ADMINISTRATIVE/FACILITY
  COORDINATION ONLY. An op outside this set is a scope violation by construction."
  #{:schedule-appointment :coordinate-referral-logistics :coordinate-supply-request
    :schedule-staff-shift-proposal :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area. Clinics are clinically
  central, so scope exclusions are MAXIMALLY CONSERVATIVE. Covers
  diagnosis, treatment, medication, clinical procedures, patient
  assessment, triage/discharge decisions, end-of-life, or clinical-
  authority enforcement. Scanned across the proposal's op/summary/
  rationale/cites/value, never trusting the advisor's own intent."
  ;; Diagnosis & clinical assessment
  ["diagnosis" "diagnos" "診断" "assessment" "clinical assessment"
   "clinical-assessment" "臨床評価" "evaluate patient" "patient evaluation"
   ;; Treatment & care planning
   "treatment" "treatment plan" "treatment-plan" "care plan" "care-plan"
   "ケアプラン" "therapeutic" "therapy plan" "medical decision"
   ;; Medication & pharmaceutical
   "medicatio" "薬" "dosing" "処方" "prescription" "rx" "pharma" "drug"
   "iv fluid" "infusion" "inject" "intravenous" "subcutaneous"
   "antibiotic" "drug administration" "medication administration"
   ;; Clinical procedures
   "procedure" "手術" "extraction" "implant" "filling" "crown" "root canal"
   "surgical" "incision" "suture" "clinical technique"
   ;; Patient assessment & monitoring
   "vital sign" "vital-sign" "vitals" "blood pressure" "heart rate"
   "respiration" "temperature" "blood glucose" "o2 saturation"
   "patient assessment" "clinical assessment" "dental exam assessment"
   ;; Triage, admission, discharge
   "triage" "admission" "discharge" "discharge-ready" "readiness"
   "患者分類" "入退院" "urgency" "priority level"
   ;; Physical restrictions
   "physical restraint" "physical-restraint" "restraint" "拘束" "身体拘束"
   "seclusion" "隔離" "sedation"
   ;; End-of-life & clinical authority
   "end of life" "end-of-life" "dnr" "do not resuscitate" "終末期"
   "advance directive" "code status" "palliative" "hospice"
   "clinical decision" "clinical-decision" "clinical authority"
   "license suspension" "license-suspension" "compliance enforcement"
   "investigat" "complaint" "patient safety" "patient-safety"
   "医療安全" "emergency"])

;; ----------------------------- checks -----------------------------

(defn- appointment-unverified-violations
  "The target appointment/resource must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:appt-id` claim without a store lookup."
  [{:keys [appt-id]} st]
  (let [a (store/appointment st appt-id)]
    (when-not (and a (:registered? a) (:verified? a))
      [{:rule :appointment-unverified
        :detail (str appt-id " は未登録または未検証の予約 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches diagnosis/treatment/medication/clinical/
  patient-assessment/triage/discharge/end-of-life/clinical-authority
  territory is a HARD, PERMANENT block. Never trusts the advisor's own
  framing."
  [proposal]
  (when-not (allowed-ops (:op proposal))
    (return [{:rule :op-not-allowed
              :detail (str "Op " (pr-str (:op proposal)) " is not in the closed allowlist")}]))

  (let [blob (text-blob proposal)]
    (when (some (fn [term] (str/includes? blob term))
                scope-excluded-terms)
      [{:rule :scope-exclusion
        :detail "Proposal content touches permanently out-of-scope clinical/patient-care territory"}])))

;; ===== Governor =====

(defrecord ClinicGovernor []
  Object)

(defn check-proposal
  "Run all three HARD checks + escalation gates on a proposal.
  Returns {:held? false} if all clear, or {:held? true :violations [..]} if blocked."
  [governor proposal store]
  (let [violations (concat
                     (appointment-unverified-violations proposal store)
                     (effect-not-propose-violations proposal)
                     (scope-exclusion-violations proposal))]
    (if (seq violations)
      {:held? true :violations violations}
      (if (or (< (:confidence proposal) confidence-floor)
              (always-escalate-ops (:op proposal)))
        {:held? false :escalate? true :reason "confidence or always-escalate op"}
        {:held? false :escalate? false}))))

(defn make-governor [] (ClinicGovernor.))
