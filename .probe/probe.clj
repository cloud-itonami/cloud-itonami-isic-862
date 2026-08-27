(require '[clinicops.advisor :as advisor]
         '[clinicops.governor :as governor]
         '[clinicops.operation :as operation]
         '[clinicops.store :as store]
         '[langgraph.graph :as g]
         '[clojure.string :as str])

;; --- 1. approver attribution: approve path ---
(let [st (store/demo-store)
      actor (operation/build (advisor/mock-advisor) (governor/make-governor) st)
      r1 (g/run* actor {:request {:op :flag-safety-concern :appt-id "appt-001"
                                  :patch {:concern "elevator door sensor fault"}} :phase 3}
                 {:thread-id "p-approve"})
      r2 (g/run* actor {:approval {:status :approved :by "clinic-admin-01"}}
                 {:thread-id "p-approve" :resume? true})]
  (println "APPROVE r1 status" (:status r1) "frontier" (:frontier r1))
  (println "APPROVE r2 disposition" (get-in r2 [:state :disposition]))
  (println "APPROVE graph audit channel:")
  (doseq [e (get-in r2 [:state :audit])] (println "   " (pr-str e)))
  (println "APPROVE store ledger:")
  (doseq [e (store/audit-log st)] (println "   " (pr-str (dissoc e :timestamp)))))

;; --- 2. approver attribution: reject path ---
(let [st (store/demo-store)
      actor (operation/build (advisor/mock-advisor) (governor/make-governor) st)
      _ (g/run* actor {:request {:op :flag-safety-concern :appt-id "appt-002"
                                 :patch {:concern "spill in reception"}} :phase 3}
                {:thread-id "p-reject"})
      r2 (g/run* actor {:approval {:status :rejected :by "clinic-admin-02"}}
                 {:thread-id "p-reject" :resume? true})]
  (println "REJECT disposition" (get-in r2 [:state :disposition]))
  (println "REJECT graph audit channel:")
  (doseq [e (get-in r2 [:state :audit])] (println "   " (pr-str e)))
  (println "REJECT store ledger:")
  (doseq [e (store/audit-log st)] (println "   " (pr-str (dissoc e :timestamp)))))

;; --- 3. which scope terms match candidate collision texts ---
(def tb @#'clinicops.governor/text-blob)
(defn matched [p] (let [b (tb p)] (filterv #(str/includes? b %) governor/scope-excluded-terms)))
(doseq [[label req] [["emergency-exit facility"  {:op :flag-safety-concern :appt-id "appt-001" :patch {:concern "emergency exit lighting out in corridor B"}}]
                     ["restock filling cabinet"  {:op :coordinate-supply-request :appt-id "appt-002" :patch {:items ["reception forms"] :note "filling the front-desk cabinet"}}]
                     ["real clinical drift"      {:op :schedule-appointment :appt-id "appt-001" :patch {:note "patient has diagnosed hypertension; adjust treatment plan"}}]
                     ["room readiness"           {:op :schedule-staff-shift-proposal :appt-id "appt-002" :patch {:note "room turnover readiness check"}}]
                     ["plain ok"                 {:op :coordinate-supply-request :appt-id "appt-001" :patch {:items ["pens" "clipboards"]}}]]]
  (let [p (advisor/advise (advisor/mock-advisor) nil req)]
    (println "SCAN" label "->" (pr-str (matched p)))))
