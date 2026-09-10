(ns clinicops.store
  "ClinicStore -- the append-only audit ledger and source-of-truth for
  ISIC-862 outpatient clinic operations coordination.

  This actor coordinates the logistics and administration of clinic operations:
  appointment scheduling, referral coordination, non-clinical supply requests,
  administrative staff shifts, and facility safety concerns.

  Behind the `Store` protocol so the backend is a swap, not a rewrite
  (mirrors `cerealops.store`, cloud-itonami-isic-0111):

    - `MemStore`     -- atom-backed. Deterministic default for
                        dev/tests/demo (no deps). `audit-log` used to be a
                        plain vector on the record itself, so `append-audit`
                        returned a NEW record via `update this :audit-log
                        conj ...` rather than mutating anything -- which
                        meant the fact was silently lost unless the caller
                        captured and re-threaded the returned value. Every
                        actual caller (this actor's graph nodes) closes
                        over a `store-inst` reference and can't re-thread a
                        return value through a channel this way, so the
                        audit ledger was structurally dead code outside a
                        test that captured the return itself
                        (`store_contract_test.clj`). Now the ledger lives
                        in an atom, so any holder of the SAME store value
                        observes every append, matching every sibling
                        actor's `MemStore` (e.g. `cerealops.store`).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store. Pure `.cljc`, so it runs offline AND can
                        be pointed at a real Datomic Local or a
                        kotoba-server pod by swapping `langchain.db`'s
                        `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/clinicops/store_contract_test.clj). In production this would
  connect to a real backend (ERP, calendar, etc.) via connectors -- the
  in-memory version is for testing and demo."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (-appointment [store appt-id] "Get appointment by ID")
  (-provider [store provider-id] "Get provider by ID")
  (-append-audit [store event] "Append an audit event. Returns the store.")
  (-audit-log [store] "Get all audit events, in append order."))

(defrecord MemStore [appointments providers audit-log-atom]
  Store
  (-appointment [_ appt-id] (get appointments appt-id))
  (-provider [_ provider-id] (get providers provider-id))
  (-append-audit [this event]
    (swap! audit-log-atom conj (merge event {:timestamp #?(:clj (System/currentTimeMillis)
                                                            :cljs (.now js/Date))}))
    this)
  (-audit-log [_] @audit-log-atom))

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
  (MemStore. appointments providers (atom [])))

(def demo-appointments
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
               :status :scheduled}})

(def demo-providers
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
              :availability [9 10 11 13 14 15 16]}})

(defn demo-store
  "Create a demo store with sample clinic data."
  []
  (make-store :appointments demo-appointments :providers demo-providers))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Each appointment/provider is stored as an opaque EDN string blob (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand a
  caller-defined record into sub-entities -- the same blob convention
  every sibling DatomicStore already uses for its own opaque payloads.
  The identity-schema builder, EDN-blob codec and seq-keyed event-log
  read/append are the shared kotoba-lang/langchain-store machinery
  (ADR-2607141600)."
  (ls/identity-schema [:appointment/id :provider/id :ledger/seq]))

(defrecord DatomicStore [conn]
  Store
  (-appointment [_ appt-id]
    (when appt-id
      (ls/dec* (d/q '[:find ?p .
                      :in $ ?id
                      :where [?e :appointment/id ?id] [?e :appointment/payload ?p]]
                    (d/db conn) appt-id))))
  (-provider [_ provider-id]
    (when provider-id
      (ls/dec* (d/q '[:find ?p .
                      :in $ ?id
                      :where [?e :provider/id ?id] [?e :provider/payload ?p]]
                    (d/db conn) provider-id))))
  (-append-audit [this event]
    (let [event' (merge event {:timestamp #?(:clj (System/currentTimeMillis)
                                              :cljs (.now js/Date))})]
      (ls/append-blob! conn :ledger/seq :ledger/fact (count (-audit-log this)) event'))
    this)
  (-audit-log [_] (ls/read-stream conn :ledger/seq :ledger/fact)))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `appointments` /
  `providers` (id -> record maps); empty when omitted."
  [& {:keys [appointments providers]
      :or {appointments {} providers {}}}]
  (let [conn (d/create-conn schema)]
    (doseq [[id a] appointments]
      (d/transact! conn [{:appointment/id id :appointment/payload (ls/enc a)}]))
    (doseq [[id p] providers]
      (d/transact! conn [{:provider/id id :provider/payload (ls/enc p)}]))
    (->DatomicStore conn)))

(defn demo-datomic-store
  "A DatomicStore seeded with the same sample clinic data as `demo-store`."
  []
  (datomic-store :appointments demo-appointments :providers demo-providers))
