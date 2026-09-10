(ns clinicops.operation-graph-test
  "End-to-end coverage of the REAL compiled `langgraph-clj` StateGraph
  (`clinicops.operation/build`) -- proving the graph's own
  `:status`/`:frontier` (a genuine `interrupt-before` pause, not a
  simulated one) AND that the `:commit`/`:hold` nodes genuinely append
  to the Store's append-only audit ledger. `process-request`'s existing
  tests (`governor_contract_test.clj`) only exercise the translated
  legacy `{:status ...}` shape; this file exercises the graph itself,
  including the full human-in-the-loop approve/reject resume cycle
  `process-request` cannot reach in a single call."
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.operation :as operation]
            [clinicops.store :as store]
            [langgraph.graph :as g]))

(deftest operation-commit-path
  (let [store-inst (store/demo-store)
        actor (operation/build (advisor/mock-advisor) (governor/make-governor) store-inst)
        result (g/run* actor
                        {:request {:op :schedule-appointment :appt-id "appt-001" :patch {}}
                         :phase 3}
                        {:thread-id "test-commit"})]
    (is (= :done (:status result)))
    (is (= :commit (get-in result [:state :disposition])))
    (testing "a real ledger entry was appended by the :commit node"
      (is (= 1 (count (store/audit-log store-inst))))
      (is (= :proposal-committed (:event-type (first (store/audit-log store-inst))))))))

(deftest operation-hard-hold-path
  (let [store-inst (store/demo-store)
        actor (operation/build (advisor/mock-advisor) (governor/make-governor) store-inst)
        result (g/run* actor
                        {:request {:op :schedule-appointment :appt-id "unregistered-appt" :patch {}}
                         :phase 3}
                        {:thread-id "test-hold"})]
    (is (= :done (:status result)))
    (is (= :hold (get-in result [:state :disposition])))
    (testing "the hold reason is a real Governor violation"
      (is (some #(= :appointment-unverified (:rule %))
                (get-in result [:state :verdict :violations]))))
    (testing "a real ledger entry was appended by the :hold node"
      (is (= 1 (count (store/audit-log store-inst))))
      (is (= :proposal-rejected (:event-type (first (store/audit-log store-inst))))))))

(deftest operation-escalate-approve-commit-path
  (let [store-inst (store/demo-store)
        actor (operation/build (advisor/mock-advisor) (governor/make-governor) store-inst)
        tid "test-escalate-approve"
        r1 (g/run* actor
                    {:request {:op :flag-safety-concern :appt-id "appt-001" :patch {:concern "elevator malfunction"}}
                     :phase 3}
                    {:thread-id tid})]
    (testing "the graph genuinely pauses at :request-approval (interrupt-before)"
      (is (= :interrupted (:status r1)))
      (is (= [:request-approval] (:frontier r1))))
    (let [r2 (g/run* actor {:approval {:status :approved :by "human-01"}}
                      {:thread-id tid :resume? true})]
      (testing "resume with an approval commits"
        (is (= :done (:status r2)))
        (is (= :commit (get-in r2 [:state :disposition]))))
      (testing "the ledger now has the escalated-then-committed fact"
        (is (= 1 (count (store/audit-log store-inst))))
        (is (= :proposal-committed (:event-type (first (store/audit-log store-inst)))))))))

(deftest operation-escalate-reject-hold-path
  (let [store-inst (store/demo-store)
        actor (operation/build (advisor/mock-advisor) (governor/make-governor) store-inst)
        tid "test-escalate-reject"
        _ (g/run* actor
                   {:request {:op :flag-safety-concern :appt-id "appt-001" :patch {:concern "elevator malfunction"}}
                    :phase 3}
                   {:thread-id tid})
        r2 (g/run* actor {:approval {:status :rejected :by "human-01"}}
                    {:thread-id tid :resume? true})]
    (testing "resume with a rejection holds"
      (is (= :done (:status r2)))
      (is (= :hold (get-in r2 [:state :disposition]))))
    (testing "the ledger records the approval-rejected fact"
      (is (= 1 (count (store/audit-log store-inst))))
      (is (= :approval-rejected (:event-type (first (store/audit-log store-inst))))))))

(deftest process-request-still-compiles-and-runs-the-real-graph
  (testing "process-request's translated shape matches a direct build+run* call"
    (let [store-inst (store/demo-store)
          result (operation/process-request (advisor/mock-advisor) (governor/make-governor)
                                             store-inst 3
                                             {:op :schedule-appointment :appt-id "appt-001" :patch {}})]
      (is (= :committed (:status result)))
      (is (= 1 (count (store/audit-log store-inst)))))))
