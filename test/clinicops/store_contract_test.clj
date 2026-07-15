(ns clinicops.store-contract-test
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.store :as store]))

(deftest store-basics
  (testing "MemStore creation"
    (let [st (store/make-store)]
      (is (not (nil? st)))
      (is (empty? (store/audit-log st)))))

  (testing "Appointment retrieval"
    (let [appt {:appt-id "a1" :patient-name "Test" :registered? true :verified? true}
          st (store/make-store :appointments {"a1" appt})]
      (is (= (store/appointment st "a1") appt))
      (is (nil? (store/appointment st "nonexistent")))))

  (testing "Provider retrieval"
    (let [provider {:provider-id "p1" :name "Dr. Test" :registered? true :verified? true}
          st (store/make-store :providers {"p1" provider})]
      (is (= (store/provider st "p1") provider))
      (is (nil? (store/provider st "nonexistent")))))

  (testing "Audit ledger append"
    (let [st (store/make-store)]
      (is (empty? (store/audit-log st)))
      (let [st2 (store/append-audit st {:event-type :test})]
        (is (= 1 (count (store/audit-log st2)))))))

  (testing "Demo store has sample data"
    (let [st (store/demo-store)]
      (is (not (nil? (store/appointment st "appt-001"))))
      (is (not (nil? (store/provider st "doc-001")))))))
