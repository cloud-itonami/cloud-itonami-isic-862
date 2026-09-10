(ns clinicops.advisor
  "ClinicAdvisor -- the *contained intelligence node* for the
  ISIC-862 outpatient clinic operations-coordination actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: appointment scheduling/rescheduling logistics, referral
  coordination logistics, non-clinical consumable supply coordination,
  staff shift proposals, and facility safety-concern flagging. CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER a
  direct actuation -- every proposal's `:effect` is always `:propose`.
  Every output is censored downstream by `clinicops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts diagnosis, treatment decisions, medication
  administration, clinical procedures, patient assessment, triage/discharge,
  vital signs monitoring, physical restraint use, end-of-life decisions,
  or clinical-authority actions -- those are permanently out of scope for
  this actor (and maximally conservatively scanned given clinics' inherent
  clinical nature), not merely un-implemented. `clinicops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :appt-id    str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-appointment-schedule
  "Draft an appointment scheduling/rescheduling logistics proposal.
  Pure scheduling logistics: available appointment slots, provider availability,
  patient calendar coordination. NEVER a clinical priority, urgency triage,
  or medical necessity decision."
  [_db {:keys [appt-id patch]}]
  {:op         :schedule-appointment
   :appt-id    appt-id
   :summary    (str appt-id " の予約スケジューリング調整: " (pr-str (keys patch)))
   :rationale  "予約可能スロットと提供者の利用可能性の物理的調整のみ。臨床的優先度判定なし。医学的必要性判定なし。"
   :cites      [appt-id]
   :effect     :propose
   :value      (merge {:appt-id appt-id} patch)
   :confidence 0.91})

(defn- propose-referral-logistics
  "Draft a referral coordination logistics proposal (administrative logistics
  of sending/receiving a referral -- paperwork, scheduling handoff). NEVER
  the clinical decision to refer or reviewing/acting on referral clinical content."
  [_db {:keys [appt-id patch]}]
  {:op         :coordinate-referral-logistics
   :appt-id    appt-id
   :summary    (str appt-id " への紹介コーディネーション: " (pr-str (keys patch)))
   :rationale  "紹介書類の行政的調整と受け取り診療所のスケジューリング手配のみ。臨床的内容の審査なし。臨床的紹介判定なし。"
   :cites      [appt-id]
   :effect     :propose
   :value      (merge {:appt-id appt-id} patch)
   :confidence 0.89})

(defn- propose-supply-request
  "Draft a NON-CLINICAL consumable supply request coordination
  (office supplies, administrative forms -- ABSOLUTELY NEVER
  medication, medical devices, clinical equipment, or medical supplies
  like instruments, masks, gloves, or any medication-related items)."
  [_db {:keys [appt-id patch]}]
  {:op         :coordinate-supply-request
   :appt-id    appt-id
   :summary    (str appt-id " に関連する事務用品リクエスト: " (pr-str (keys patch)))
   :rationale  "用紙・パンフレット・事務用品などの事務消耗品の調達調整"
   :cites      [appt-id]
   :effect     :propose
   :value      (merge {:appt-id appt-id} patch)
   :confidence 0.90})

(defn- propose-staff-shift
  "Draft a staff-shift roster PROPOSAL only (never a binding decision).
  Actual shift finalization is always done by shift supervisors."
  [_db {:keys [appt-id patch]}]
  {:op         :schedule-staff-shift-proposal
   :appt-id    appt-id
   :summary    (str appt-id " に関連するスタッフシフト提案: " (pr-str (keys patch)))
   :rationale  "行政スタッフのシフト割り当て提案のみ。確定は人間のシフト管理者が判断する。臨床スタッフ配置判定なし。"
   :cites      [appt-id]
   :effect     :propose
   :value      (merge {:appt-id appt-id} patch)
   :confidence 0.86})

(defn- propose-safety-concern
  "Draft a facility/operational safety concern flag (equipment malfunction,
  facility hazards, spill on floor, etc.). NEVER a patient-safety or clinical-
  emergency flag -- those must go through actual clinical staff/systems."
  [_db {:keys [appt-id patch]}]
  {:op         :flag-safety-concern
   :appt-id    appt-id
   :summary    (str appt-id " に関連する施設安全懸念: " (pr-str (keys patch)))
   :rationale  "施設・運用上の安全上の懸念。設備故障、施設ハザード、床のこぼれなど。患者安全・臨床緊急は含まない。"
   :cites      [appt-id]
   :effect     :propose
   :value      (merge {:appt-id appt-id} patch)
   :confidence 0.87})

;; ===== Router & mock advisor =====

(defn- route-op
  "Route the request to the appropriate proposal generator."
  [op _db request]
  (case op
    :schedule-appointment (propose-appointment-schedule _db request)
    :coordinate-referral-logistics (propose-referral-logistics _db request)
    :coordinate-supply-request (propose-supply-request _db request)
    :schedule-staff-shift-proposal (propose-staff-shift _db request)
    :flag-safety-concern (propose-safety-concern _db request)
    (throw (ex-info "Unknown op" {:op op}))))

(defrecord MockAdvisor []
  Advisor
  (-advise [_ _store {:keys [op] :as request}]
    (route-op op _store request)))

(defn mock-advisor [] (MockAdvisor.))

(defn advise
  "Request a proposal from the advisor."
  [advisor store request]
  (-advise advisor store request))
