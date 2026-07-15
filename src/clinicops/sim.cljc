(ns clinicops.sim
  "ClinicSim -- demo driver for ISIC-862 clinic operations coordination actor.
  Exercises all five operations in all scenarios: phase-1 approval-gated,
  phase-3 auto-commit for 4 non-safety ops, always-escalating safety-concern flag,
  and all HARD-hold scenarios."
  (:require [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.operation :as operation]
            [clinicops.phase :as phase]
            [clinicops.store :as store]))

(defn demo-scenario
  "Run a single scenario: request -> proposal -> governance -> decision -> result."
  [name phase advisor-inst governor-inst store-inst request]
  (println (str "\n=== " name " ==="))
  (println (str "Phase: " phase ", Op: " (:op request)))
  (let [result (operation/process-request advisor-inst governor-inst store-inst phase request)]
    (println (str "Result: " (:status result)))
    (when-let [audit (:audit-event result)]
      (println (str "Audit: " (:event-type audit))))
    (when-let [violations (:violations result)]
      (doseq [v violations]
        (println (str "  Violation: " (:rule v) " - " (:detail v)))))
    result))

(defn -main
  "Run all demo scenarios."
  []
  (println "=== ISIC-862 Clinic Operations Coordination Actor Demo ===")

  (let [advisor-inst (advisor/mock-advisor)
        governor-inst (governor/make-governor)
        store-inst (store/demo-store)

        ;; Phase 3 scenarios - auto-commit for 4 non-safety ops, escalate for safety
        _ (println "\n--- Phase 3 (supervised-auto) ---")
        _ (demo-scenario "Normal appointment scheduling"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :schedule-appointment :appt-id "appt-001" :patch {:new-time "14:30"}})

        _ (demo-scenario "Normal referral coordination"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :coordinate-referral-logistics :appt-id "appt-002" :patch {:target-clinic "clinic-xyz"}})

        _ (demo-scenario "Normal supply request"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :coordinate-supply-request :appt-id "appt-001" :patch {:items ["forms" "pens"]}})

        _ (demo-scenario "Normal staff shift proposal"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :schedule-staff-shift-proposal :appt-id "appt-001" :patch {:staff "tom" :shift "pm"}})

        _ (demo-scenario "Safety concern - always escalates"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :flag-safety-concern :appt-id "appt-001" :patch {:concern "equipment malfunction"}})

        ;; HARD-hold scenarios
        _ (println "\n--- HARD-hold Scenarios ---")

        _ (demo-scenario "Unregistered appointment"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :schedule-appointment :appt-id "unregistered-appt" :patch {:new-time "15:00"}})

        _ (demo-scenario "Effect not :propose"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :schedule-appointment :appt-id "appt-001" :patch {} :effect :commit})

        _ (demo-scenario "Scope-excluded: clinical diagnosis"
                         3
                         advisor-inst governor-inst store-inst
                         {:op :schedule-appointment :appt-id "appt-001"
                          :patch {:note "patient has diagnosed hypertension"}})

        ;; Phase 1 scenario - everything escalates
        _ (println "\n--- Phase 1 (assisted-logistics) - all escalate ---")
        _ (demo-scenario "Phase 1: appointment (escalates)"
                         1
                         advisor-inst governor-inst store-inst
                         {:op :schedule-appointment :appt-id "appt-001" :patch {:new-time "14:30"}})]

    (println "\n=== Demo Complete ===")
    nil))

;; For nbb/CLI invocation
#?(:cljs
   (do
     (defn ^:async main []
       (-main)
       (System/exit 0))
     (.then (js/Promise.resolve)
            (fn [] (main)))))
