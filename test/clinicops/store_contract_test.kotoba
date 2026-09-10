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

;; MemStore == DatomicStore parity, mirroring
;; `cerealops.store-contract-test` (cloud-itonami-isic-0111). `append-audit`
;; on both backends returns the SAME store value it was called with (an
;; atom-mutation on MemStore, a conn-mutation on DatomicStore) -- so
;; `st2`/`d2` below are the identical object as `st`/`d`, and asserting
;; through the *original* binding after append is itself the regression
;; test for the dead-ledger bug this repo used to have (append-audit's
;; effect used to be a plain-vector `update` that produced a NEW, never
;; re-threaded record -- silently invisible to any other holder of the
;; store, including this actor's own compiled graph nodes).
(deftest mem-and-datomic-parity
  (let [mem (store/demo-store)
        dat (store/demo-datomic-store)]
    (testing "seeded appointment/provider data is identical across backends"
      (is (= (store/appointment mem "appt-001") (store/appointment dat "appt-001")))
      (is (= (store/provider mem "doc-001") (store/provider dat "doc-001")))
      (is (nil? (store/appointment mem "no-such-appt")))
      (is (nil? (store/appointment dat "no-such-appt"))))
    (testing "append-audit mutates the SAME store value on both backends"
      (store/append-audit mem {:event-type :committed :op :schedule-appointment})
      (store/append-audit dat {:event-type :committed :op :schedule-appointment})
      (store/append-audit mem {:event-type :proposal-escalated :op :flag-safety-concern})
      (store/append-audit dat {:event-type :proposal-escalated :op :flag-safety-concern})
      (is (= 2 (count (store/audit-log mem))))
      (is (= 2 (count (store/audit-log dat))))
      (is (= (map :event-type (store/audit-log mem))
             (map :event-type (store/audit-log dat)))))))
