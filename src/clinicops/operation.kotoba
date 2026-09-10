(ns clinicops.operation
  "ClinicOperation -- the REAL langgraph-clj StateGraph for ISIC-862
  clinic operations coordination. `make-graph` used to return an inert
  `{:nodes {} :edges {}}` data map that no code ever traversed --
  `process-request` was a hand-written `cond` that never touched
  langgraph at all, despite this namespace's docstring claiming
  StateGraph orchestration. `build` below compiles a REAL
  `langgraph.graph/state-graph` (mirrors `cerealops.operation`,
  cloud-itonami-isic-0111):

  intake -> advise -> govern -> decide -> commit | request-approval -> commit | hold

  1. intake: receive the request
  2. advise: ask the advisor for a proposal (`clinicops.advisor`)
  3. govern: run the three HARD checks + escalation gate (`clinicops.governor`)
  4. decide: route based on governor output + rollout phase (`clinicops.phase`)
  5. request-approval: reached only on escalate; `interrupt-before` pauses
     the graph here for a real human-in-the-loop resume (checkpoint-based)
  6. commit / hold: terminal nodes -- append the decision fact to the
     Store's append-only audit ledger (`clinicops.store/append-audit`),
     previously only ever called from `store_contract_test.clj` (dead
     code outside a test)

  `process-request` is kept as a backward-compatible convenience wrapper
  (same 5-arg call shape and `{:status ...}` return shape every existing
  caller/test in this repo already depends on) that compiles and runs
  the graph to completion (or first interrupt) in a single call. New
  integrations that need genuine human-in-the-loop resume should use
  `build` + `langgraph.graph/run*` directly (see
  `docs/operator-guide.md`)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.phase :as phase]
            [clinicops.store :as store]))

(defn build
  "Compiles a ClinicOperation graph. `advisor-inst`/`governor-inst` are a
  `clinicops.advisor/Advisor` and a governor instance (as returned by
  `clinicops.governor/make-governor`); `store-inst` is a
  `clinicops.store/Store`. opts:
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [advisor-inst governor-inst store-inst & [{:keys [checkpointer]
                                              :or {checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :phase       {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          {:proposal (advisor/advise advisor-inst store-inst request)}))

      (g/add-node :govern
        (fn [{:keys [proposal]}]
          {:verdict (governor/check-proposal governor-inst proposal store-inst)}))

      (g/add-node :decide
        (fn [{:keys [phase proposal verdict]}]
          (let [{:keys [held? escalate?]} verdict
                op (:op proposal)
                can-auto-commit (phase/can-auto-commit? phase op)
                should-escalate (or escalate? (phase/should-escalate? phase op))]
            (cond
              held?
              {:disposition :hold
               :audit [{:event-type :proposal-rejected
                        :reason "Hard governance violation"
                        :op op :violations (:violations verdict)}]}

              should-escalate
              {:disposition :escalate
               :audit [{:event-type :proposal-escalated
                        :reason "Requires human approval"
                        :op op :confidence (:confidence proposal)}]}

              can-auto-commit
              {:disposition :commit}

              ;; Not escalating and not auto-commit-eligible at this
              ;; phase: still requires human sign-off before committing.
              ;; (Unreachable under the current phase-config, whose
              ;; :auto/:escalate sets fully partition every op -- kept
              ;; for phase-config extensibility, matching the pre-graph
              ;; :awaiting-approval branch.)
              :else
              {:disposition :escalate
               :audit [{:event-type :proposal-awaiting-approval
                        :reason "Requires human approval"
                        :op op :confidence (:confidence proposal)}]}))))

      (g/add-node :request-approval
        (fn [{:keys [approval proposal]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :audit [{:event-type :approval-granted :op (:op proposal) :by (:by approval)}]}
            {:disposition :hold
             :audit [{:event-type :approval-rejected :op (:op proposal) :by (:by approval)}]})))

      (g/add-node :commit
        (fn [{:keys [proposal]}]
          (let [ev {:event-type :proposal-committed :op (:op proposal) :confidence (:confidence proposal)}]
            (store/append-audit store-inst ev)
            {:audit [ev]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:proposal-rejected :approval-rejected} (:event-type %)) audit))]
            (store/append-audit store-inst hf))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))

(defn process-request
  "Backward-compatible convenience wrapper: compiles a fresh graph
  (`build`) and runs it to completion or the first interrupt in ONE
  call, translating the result back to this repo's pre-StateGraph
  return shape: {:status :held|:escalated|:committed :reason ..
  :violations .. :proposal .. :audit-event ..}. This preserves every
  existing caller's contract (`clinicops.sim`,
  `governor_contract_test.clj`) while genuinely running the compiled
  langgraph-clj StateGraph underneath -- not a simulation of one.

  Because this wrapper never resumes an :escalate disposition (it
  returns as soon as the graph pauses at :request-approval), it cannot
  observe an :approval-rejected hold -- callers that need the full
  human-in-the-loop approve/reject cycle should call `build` +
  `langgraph.graph/run*` directly (see `docs/operator-guide.md` and
  `test/clinicops/operation_graph_test.clj`)."
  [advisor-inst governor-inst store-inst current-phase request]
  (let [cg (build advisor-inst governor-inst store-inst)
        tid (str (gensym "clinicops-req-"))
        {:keys [state]} (g/run* cg {:request request :phase current-phase} {:thread-id tid})
        {:keys [disposition proposal verdict audit]} state
        audit-event (last audit)]
    (case disposition
      :hold
      {:status :held
       :reason "Hard governance violation"
       :violations (:violations verdict)
       :audit-event audit-event}

      :escalate
      {:status :escalated
       :reason (or (:reason verdict) "Requires human approval")
       :proposal proposal
       :audit-event audit-event}

      :commit
      {:status :committed
       :proposal proposal
       :audit-event audit-event})))
