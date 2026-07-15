(ns clinicops.governor-contract-test
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.operation :as operation]
            [clinicops.phase :as phase]
            [clinicops.store :as store]))

(deftest integration-phase-3-scenarios
  (let [advisor-inst (advisor/mock-advisor)
        gov-inst (governor/make-governor)
        store-inst (store/demo-store)]

    (testing "Phase 3: normal appointment scheduling"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :schedule-appointment :appt-id "appt-001" :patch {}})]
        (is (= :committed (:status result)))))

    (testing "Phase 3: normal referral coordination"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :coordinate-referral-logistics :appt-id "appt-001" :patch {}})]
        (is (= :committed (:status result)))))

    (testing "Phase 3: normal supply request"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :coordinate-supply-request :appt-id "appt-001" :patch {}})]
        (is (= :committed (:status result)))))

    (testing "Phase 3: normal staff shift"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :schedule-staff-shift-proposal :appt-id "appt-001" :patch {}})]
        (is (= :committed (:status result)))))

    (testing "Phase 3: safety concern always escalates"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :flag-safety-concern :appt-id "appt-001" :patch {}})]
        (is (= :escalated (:status result)))))))

(deftest integration-phase-1-scenarios
  (let [advisor-inst (advisor/mock-advisor)
        gov-inst (governor/make-governor)
        store-inst (store/demo-store)]

    (testing "Phase 1: all non-safety ops escalate"
      (doseq [op [:schedule-appointment :coordinate-referral-logistics
                  :coordinate-supply-request :schedule-staff-shift-proposal]]
        (let [result (operation/process-request
                       advisor-inst gov-inst store-inst 1
                       {:op op :appt-id "appt-001" :patch {}})]
          (is (= :awaiting-approval (:status result))))))))

(deftest integration-hard-hold-scenarios
  (let [advisor-inst (advisor/mock-advisor)
        gov-inst (governor/make-governor)
        store-inst (store/demo-store)]

    (testing "HARD-hold: unregistered appointment"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :schedule-appointment :appt-id "unknown-appt" :patch {}})]
        (is (= :held (:status result)))
        (is (seq (:violations result)))))

    (testing "HARD-hold: scope-excluded clinical content"
      (let [result (operation/process-request
                     advisor-inst gov-inst store-inst 3
                     {:op :schedule-appointment :appt-id "appt-001"
                      :patch {:note "patient has diagnosis of hypertension"}})]
        ;; The advisor will create a proposal with clinical content
        ;; The governor should catch and hold it
        (is (= :held (:status result)))
        (is (seq (:violations result)))))))

(deftest legitimate-facility-safety-concern-is-not-scope-excluded
  "Verify that a legitimate facility safety concern (e.g. elevator down)
  does NOT trigger scope-exclusion, even though clinics are clinically central."
  (let [advisor-inst (advisor/mock-advisor)
        gov-inst (governor/make-governor)
        store-inst (store/demo-store)
        ;; Simulate a safety concern about a facility issue
        request {:op :flag-safety-concern :appt-id "appt-001"
                 :patch {:concern "elevator malfunction near clinic entrance"}}]
    (let [result (operation/process-request
                   advisor-inst gov-inst store-inst 3 request)]
      ;; It should escalate (safety concern always escalates), but NOT be held
      (is (not= :held (:status result)))
      (is (or (= :escalated (:status result)) (= :escalated (-> result :proposal)))))))
