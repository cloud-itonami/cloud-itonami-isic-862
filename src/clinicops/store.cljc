(ns clinicops.store
  "ClinicStore -- the append-only audit ledger and source-of-truth for
  ISIC-862 outpatient clinic operations coordination.

  This actor coordinates the logistics and administration of clinic operations:
  appointment scheduling, referral coordination, non-clinical supply requests,
  administrative staff shifts, and facility safety concerns.

  The store is a simple MemStore with a string-keyed directory of appointments,
  providers, and resources, plus an append-only audit ledger of all decisions.
  In production this would connect to a real backend (ERP, calendar, etc.)
  via connectors -- the in-memory version is for testing and demo.")

(defprotocol Store
  (-appointment [store appt-id] "Get appointment by ID")
  (-provider [store provider-id] "Get provider by ID")
  (-append-audit [store event] "Append an audit event")
  (-audit-log [store] "Get all audit events"))

(defrecord MemStore [appointments providers audit-log]
  Store
  (-appointment [_ appt-id] (get appointments appt-id))
  (-provider [_ provider-id] (get providers provider-id))
  (-append-audit [this event]
    (update this :audit-log conj (merge event {:timestamp (System/currentTimeMillis)})))
  (-audit-log [_] audit-log))

(defn appointment
  "Retrieve appointment record by ID."
  [st appt-id]
  (-appointment st appt-id))

(defn provider
  "Retrieve provider record by ID."
  [st provider-id]
  (-provider st provider-id))

(defn append-audit
  "Append an audit event to the ledger."
  [st event]
  (-append-audit st event))

(defn audit-log
  "Get the full audit ledger."
  [st]
  (-audit-log st))

;; ===== MemStore constructor & demo data =====

(defn make-store
  "Create a new in-memory store with optional initial appointments and providers."
  [& {:keys [appointments providers]
      :or {appointments {}
           providers {}}}]
  (MemStore. appointments providers []))

(defn demo-store
  "Create a demo store with sample clinic data."
  []
  (make-store
    :appointments
    {"appt-001" {:appt-id "appt-001"
                 :patient-name "田中太郎"
                 :provider-id "doc-001"
                 :appointment-time "2026-07-15T14:00:00Z"
                 :appointment-type :checkup
                 :registered? true
                 :verified? true
                 :status :scheduled}
     "appt-002" {:appt-id "appt-002"
                 :patient-name "山田花子"
                 :provider-id "doc-002"
                 :appointment-time "2026-07-15T15:30:00Z"
                 :appointment-type :follow-up
                 :registered? true
                 :verified? true
                 :status :scheduled}}
    :providers
    {"doc-001" {:provider-id "doc-001"
                :name "佐藤医生"
                :specialty :general-practice
                :registered? true
                :verified? true
                :availability [10 11 12 14 15 16 17]}
     "doc-002" {:provider-id "doc-002"
                :name "松田歯科医"
                :specialty :dentistry
                :registered? true
                :verified? true
                :availability [9 10 11 13 14 15 16]}}))
