(ns clinicops.operation
  "ClinicOperation -- the langgraph-clj StateGraph for ISIC-862 clinic
  operations coordination. This implements the state machine:

  intake → advise → govern → decide → commit | hold | request-approval

  The flow is:
  1. intake: receive the request, validate basic shape
  2. advise: ask the advisor for a proposal
  3. govern: run the three HARD checks + escalation gates
  4. decide: route based on governor output
  5. commit: write to store + audit ledger
  6. hold: reject the proposal with a detailed explanation
  7. request-approval: escalate to human for sign-off"
  (:require [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.phase :as phase]))

(defn make-graph
  "Create a demo state graph for the clinic operations flow."
  []
  {:name "clinic-operations"
   :nodes {:intake {}
           :advise {}
           :govern {}
           :decide {}
           :commit {}
           :hold {}
           :request-approval {}}
   :edges {:intake :advise
           :advise :govern
           :govern :decide
           :decide [:commit :hold :request-approval]}})

(defn process-request
  "Orchestrate the full flow: intake → advise → govern → decide → commit | hold | escalate.
  Returns the final state with result and any audit entries."
  [advisor-inst governor-inst store-inst current-phase request]
  (let [;; Step 1: intake
        intake-result {:request request :status :intake-received}

        ;; Step 2: advise
        proposal (advisor/advise advisor-inst store-inst request)

        ;; Step 3: govern
        gov-check (governor/check-proposal governor-inst proposal store-inst)

        ;; Step 4: decide
        {:keys [held? escalate?]} gov-check
        op (:op proposal)
        can-auto-commit (phase/can-auto-commit? current-phase op)
        should-escalate (or escalate? (phase/should-escalate? current-phase op))]

    (cond
      ;; HARD hold - never proceed
      held?
      {:status :held
       :reason "Hard governance violation"
       :violations (:violations gov-check)
       :audit-event {:event-type :proposal-rejected
                     :reason "Hard governance violation"
                     :op op
                     :violations (:violations gov-check)}}

      ;; Safe to proceed
      :else
      (if should-escalate
        ;; Route to escalation
        {:status :escalated
         :reason (if (:reason gov-check) (:reason gov-check) "Requires human approval")
         :proposal proposal
         :audit-event {:event-type :proposal-escalated
                       :reason "Requires human approval"
                       :op op
                       :confidence (:confidence proposal)}}
        ;; Auto-commit if we can, else hold for approval
        (if can-auto-commit
          {:status :committed
           :proposal proposal
           :audit-event {:event-type :proposal-committed
                         :op op
                         :confidence (:confidence proposal)}}
          {:status :awaiting-approval
           :proposal proposal
           :audit-event {:event-type :proposal-awaiting-approval
                         :op op
                         :confidence (:confidence proposal)}})))))
