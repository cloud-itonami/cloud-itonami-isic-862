(ns clinicops.phase-test
  (:require [clojure.test :refer [deftest testing is]]
            [clinicops.phase :as phase]))

(deftest phase-config
  (testing "All phases have config"
    (doseq [p [0 1 2 3]]
      (is (not (nil? (phase/phase-for p))))))

  (testing "Phase 0 is read-only"
    (let [config (phase/phase-for 0)]
      (is (empty? (:auto config)))
      (is (seq (:escalate config)))))

  (testing "Phase 3 has auto-commit for 4 non-safety ops"
    (let [config (phase/phase-for 3)]
      (is (= 4 (count (:auto config))))
      (is (not (contains? (:auto config) :flag-safety-concern)))
      (is (contains? (:escalate config) :flag-safety-concern))))

  (testing "can-auto-commit? respects phase"
    (is (not (phase/can-auto-commit? 0 :schedule-appointment)))
    (is (not (phase/can-auto-commit? 1 :schedule-appointment)))
    (is (not (phase/can-auto-commit? 2 :schedule-appointment)))
    (is (phase/can-auto-commit? 3 :schedule-appointment)))

  (testing "Safety concern never auto-commits"
    (doseq [p [0 1 2 3]]
      (is (not (phase/can-auto-commit? p :flag-safety-concern)))))

  (testing "Safety concern always escalates"
    (doseq [p [0 1 2 3]]
      (is (phase/should-escalate? p :flag-safety-concern)))))
