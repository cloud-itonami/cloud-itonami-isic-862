(ns clinicops.advisor-test
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.advisor :as advisor]
            [clinicops.store :as store]))

(deftest advisor-operations
  (let [advisor-inst (advisor/mock-advisor)
        store-inst (store/demo-store)]

    (testing "Appointment scheduling proposal"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :schedule-appointment :appt-id "appt-001" :patch {:time "14:30"}})]
        (is (= :schedule-appointment (:op proposal)))
        (is (= :propose (:effect proposal)))
        (is (> (:confidence proposal) 0.8))))

    (testing "Referral logistics proposal"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :coordinate-referral-logistics :appt-id "appt-001" :patch {:clinic "ref"}})]
        (is (= :coordinate-referral-logistics (:op proposal)))
        (is (= :propose (:effect proposal)))
        (is (> (:confidence proposal) 0.8))))

    (testing "Supply request proposal"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :coordinate-supply-request :appt-id "appt-001" :patch {:items ["forms"]}})]
        (is (= :coordinate-supply-request (:op proposal)))
        (is (= :propose (:effect proposal)))
        (is (> (:confidence proposal) 0.8))))

    (testing "Staff shift proposal"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :schedule-staff-shift-proposal :appt-id "appt-001" :patch {:shift "pm"}})]
        (is (= :schedule-staff-shift-proposal (:op proposal)))
        (is (= :propose (:effect proposal)))
        (is (> (:confidence proposal) 0.8))))

    (testing "Safety concern proposal"
      (let [proposal (advisor/advise advisor-inst store-inst
                      {:op :flag-safety-concern :appt-id "appt-001" :patch {:concern "malfunction"}})]
        (is (= :flag-safety-concern (:op proposal)))
        (is (= :propose (:effect proposal)))
        (is (> (:confidence proposal) 0.8))))

    (testing "All proposals have appt-id"
      (doseq [op [:schedule-appointment :coordinate-referral-logistics :coordinate-supply-request
                  :schedule-staff-shift-proposal :flag-safety-concern]]
        (let [proposal (advisor/advise advisor-inst store-inst {:op op :appt-id "appt-001" :patch {}})]
          (is (= "appt-001" (:appt-id proposal))))))))
