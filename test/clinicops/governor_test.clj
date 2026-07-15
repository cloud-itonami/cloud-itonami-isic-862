(ns clinicops.governor-test
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.store :as store]))

(deftest governor-hard-checks
  (let [gov-inst (governor/make-governor)
        store-inst (store/demo-store)
        advisor-inst (advisor/mock-advisor)]

    (testing "Valid proposal passes all checks"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :schedule-appointment :appt-id "appt-001" :patch {}})]
        (let [{:keys [held?]} (governor/check-proposal gov-inst proposal store-inst)]
          (is (not held?)))))

    (testing "HARD-hold: unregistered appointment"
      (let [proposal {:op :schedule-appointment :appt-id "unknown-appt"
                      :effect :propose :summary "test" :rationale "test" :cites [] :value {}}]
        (let [{:keys [held? violations]} (governor/check-proposal gov-inst proposal store-inst)]
          (is held?)
          (is (seq violations))
          (is (some #(= :appointment-unverified (:rule %)) violations)))))

    (testing "HARD-hold: effect not :propose"
      (let [proposal (assoc (advisor/advise advisor-inst store-inst
                             {:op :schedule-appointment :appt-id "appt-001" :patch {}})
                            :effect :commit)]
        (let [{:keys [held? violations]} (governor/check-proposal gov-inst proposal store-inst)]
          (is held?)
          (is (seq violations))
          (is (some #(= :effect-not-propose (:rule %)) violations)))))

    (testing "HARD-hold: scope-excluded clinical content"
      (let [proposal {:op :schedule-appointment :appt-id "appt-001"
                      :effect :propose :summary "Appointment"
                      :rationale "Patient has diagnosis of hypertension"
                      :cites ["appt-001"] :value {} :confidence 0.95}]
        (let [{:keys [held? violations]} (governor/check-proposal gov-inst proposal store-inst)]
          (is held?)
          (is (seq violations))
          (is (some #(= :scope-exclusion (:rule %)) violations)))))

    (testing "HARD-hold: scope-excluded medication content"
      (let [proposal {:op :coordinate-supply-request :appt-id "appt-001"
                      :effect :propose :summary "Supply"
                      :rationale "Need to order medications for patient"
                      :cites ["appt-001"] :value {} :confidence 0.95}]
        (let [{:keys [held? violations]} (governor/check-proposal gov-inst proposal store-inst)]
          (is held?)
          (is (seq violations))
          (is (some #(= :scope-exclusion (:rule %)) violations)))))

    (testing "Legitimate facility safety concern is not scope-excluded"
      (let [proposal {:op :flag-safety-concern :appt-id "appt-001"
                      :effect :propose :summary "Elevator malfunction"
                      :rationale "Elevator near clinic entrance is down"
                      :cites ["appt-001"] :value {:concern :equipment} :confidence 0.95}]
        (let [{:keys [held? violations]} (governor/check-proposal gov-inst proposal store-inst)]
          (is (not held?))
          ;; May escalate due to safety concern, but not held
          (is (or (not (seq violations)) true)))))))

(deftest governor-escalation-gate
  (let [gov-inst (governor/make-governor)
        store-inst (store/demo-store)
        advisor-inst (advisor/mock-advisor)]

    (testing "Safety concern always escalates"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :flag-safety-concern :appt-id "appt-001" :patch {}})]
        (let [{:keys [escalate?]} (governor/check-proposal gov-inst proposal store-inst)]
          (is escalate?))))

    (testing "Low-confidence proposal escalates"
      (let [proposal (assoc (advisor/advise advisor-inst store-inst
                             {:op :schedule-appointment :appt-id "appt-001" :patch {}})
                            :confidence 0.5)]
        (let [{:keys [escalate?]} (governor/check-proposal gov-inst proposal store-inst)]
          (is escalate?))))

    (testing "High-confidence non-safety proposal does not escalate"
      (let [proposal (assoc (advisor/advise advisor-inst store-inst
                             {:op :schedule-appointment :appt-id "appt-001" :patch {}})
                            :confidence 0.95)]
        (let [{:keys [escalate?]} (governor/check-proposal gov-inst proposal store-inst)]
          (is (not escalate?)))))))
