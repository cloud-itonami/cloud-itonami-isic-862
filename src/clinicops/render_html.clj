(ns clinicops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for cloud-itonami-isic-862: this repo
  had NO demo page and no generator at all. This namespace drives the
  REAL actor stack -- `clinicops.operation/build` (a genuinely compiled
  `langgraph.graph` StateGraph) -> `clinicops.governor` ->
  `clinicops.store` -- and renders whatever that run actually produced.
  Nothing on the page is hand-typed telemetry: every appointment,
  provider, disposition, violation rule, violation detail and ledger
  fact is read back out of the store / graph state after the run.

  Deterministic: no timestamps and no `gensym` thread ids reach the
  page (the ledger's `:timestamp` is deliberately NOT rendered), so two
  runs against the same seed are byte-identical. Verify by rendering
  into two scratch dirs and diffing.

  Two build-time invariants, enforced in `-main` BEFORE the file is
  written (see `assert-invariants!`):

    1. The run MUST produce at least one HARD governor hold -- a
       `:proposal-rejected` ledger fact carrying a non-empty
       `:violations` vector. A console that shows only happy paths is
       not evidence that the governor works, so a run that fails to
       make the governor refuse anything throws instead of writing.
    2. Every appointment id a scenario names MUST either be a key of
       `clinicops.store/demo-appointments` or be explicitly declared
       `:absent? true` (the deliberately-unregistered id this repo's own
       `clinicops.sim` already uses). This makes an invented patient /
       clinician id a build failure rather than a review finding.

  MEASURED, not assumed -- three findings this renderer discloses on the
  page rather than papering over:

    a. `clinicops.sim`'s \"Effect not :propose\" scenario does not
       actually hold. It puts `:effect :commit` on the REQUEST, but
       `clinicops.advisor` hardcodes `:effect :propose` on every
       proposal it drafts, so the request field is dropped and the
       proposal commits. The governor rule is fine; the scenario cannot
       reach it.
    b. Same for `:op-not-allowed` and for the `confidence-floor` soft
       gate: `advisor/route-op` throws on an op outside the allowlist,
       and the mock advisor's confidences are 0.86-0.91, so neither
       gate is reachable from a request. Reaching rules 2 and 3 and the
       confidence floor requires the exact threat model the governor's
       own docstring names -- \"a compromised or confused advisor\" --
       so `DriftedAdvisor` below wraps the real `MockAdvisor` and
       tampers with the proposal it produced. It adds NO rules and
       relaxes none; it only supplies inputs. Rows produced this way
       are labelled as simulated advisor compromise on the page.
    c. The store DROPS the approver on the approved-commit path. The
       graph's `:request-approval` node emits an `:approval-granted`
       audit entry carrying `:by`, but that entry stays in the graph's
       `:audit` channel -- only the `:commit` node writes to the store,
       and it writes `:proposal-committed`, which has no `:by`. On the
       REJECTED path the approver DOES survive, because the `:hold` node
       forwards the `:approval-rejected` entry verbatim. The disclosure
       section is derived at render time by scanning the committed facts
       for an approver key, so the page self-corrects if the store is
       fixed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [clinicops.advisor :as advisor]
            [clinicops.governor :as governor]
            [clinicops.operation :as operation]
            [clinicops.phase :as phase]
            [clinicops.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- drifted advisor -----------------------------

(defrecord DriftedAdvisor [tamper]
  advisor/Advisor
  (-advise [_ st request]
    ;; Ask the REAL MockAdvisor for its real proposal first, then apply
    ;; one tamper to it. Models a compromised/confused advisor -- the
    ;; failure mode `clinicops.governor` exists to catch. The governor,
    ;; the graph and the store are untouched.
    (tamper (advisor/advise (advisor/mock-advisor) st request))))

;; ----------------------------- scenario driver -----------------------------

(def ^:private absent-appt-id
  "The deliberately-unregistered appointment id `clinicops.sim` already
  uses. Not a patient record -- it exists precisely to be absent from
  `store/demo-appointments`."
  "unregistered-appt")

(def ^:private scenarios
  "Every scenario this console runs, in order. `:advisor` is `:mock`
  (the repo's real advisor) or a tamper fn (simulated advisor drift).
  `:approval` resumes the graph after an `interrupt-before` pause."
  [{:id "S01" :phase 3 :appt-id "appt-001" :advisor :mock
    :request {:op :schedule-appointment :patch {:new-time "14:30"}}
    :label "Appointment reschedule (clean, phase 3)"}

   {:id "S02" :phase 3 :appt-id "appt-002" :advisor :mock
    :request {:op :coordinate-referral-logistics :patch {:target-clinic "clinic-xyz"}}
    :label "Referral paperwork handoff (clean, phase 3)"}

   {:id "S03" :phase 3 :appt-id "appt-001" :advisor :mock
    :request {:op :coordinate-supply-request :patch {:items ["forms" "pens"]}}
    :label "Office consumable request (clean, phase 3)"}

   {:id "S04" :phase 3 :appt-id "appt-002" :advisor :mock
    :request {:op :schedule-staff-shift-proposal :patch {:staff "reception-b" :shift "pm"}}
    :label "Reception staff shift proposal (clean, phase 3)"}

   {:id "S05" :phase 3 :appt-id "appt-001" :advisor :mock
    :request {:op :flag-safety-concern :patch {:concern "elevator malfunction"}}
    :approval {:status :approved :by "human-01"}
    :label "Facility safety flag -> human APPROVED"}

   {:id "S06" :phase 3 :appt-id "appt-002" :advisor :mock
    :request {:op :flag-safety-concern :patch {:concern "waiting room floor spill"}}
    :approval {:status :rejected :by "human-02"}
    :label "Facility safety flag -> human REJECTED"}

   {:id "S07" :phase 1 :appt-id "appt-001" :advisor :mock
    :request {:op :schedule-appointment :patch {:new-time "09:15"}}
    :label "Appointment reschedule at phase 1 (rollout gate, left pending)"}

   {:id "S08" :phase 3 :appt-id absent-appt-id :absent? true :advisor :mock
    :request {:op :schedule-appointment :patch {:new-time "15:00"}}
    :label "Appointment id not registered/verified in the store"}

   {:id "S09" :phase 3 :appt-id "appt-001" :advisor :mock
    :request {:op :schedule-appointment :patch {:note "patient has diagnosed hypertension"}}
    :label "Clinical content smuggled into an admin request"}

   {:id "S10" :phase 3 :appt-id "appt-001"
    :advisor #(assoc % :rationale "患者の血圧 (blood pressure) を確認して triage する")
    :drift "rationale rewritten to clinical assessment language"
    :request {:op :schedule-appointment :patch {:new-time "11:00"}}
    :label "Advisor drifts into clinical assessment in its own rationale"}

   {:id "S11" :phase 3 :appt-id "appt-001"
    :advisor #(assoc % :effect :commit)
    :drift ":effect flipped :propose -> :commit"
    :request {:op :coordinate-supply-request :patch {:items ["forms"]}}
    :label "Advisor claims direct actuation instead of a proposal"}

   {:id "S12" :phase 3 :appt-id "appt-002"
    :advisor #(assoc % :op :prescribe-medication)
    :drift ":op rewritten to an op outside the closed allowlist"
    :request {:op :schedule-appointment :patch {:new-time "16:00"}}
    :label "Advisor invents an op outside the allowlist"}

   {:id "S13" :phase 3 :appt-id "appt-001"
    :advisor #(assoc % :confidence 0.31)
    :drift ":confidence dropped to 0.31 (below the 0.6 floor)"
    :request {:op :coordinate-referral-logistics :patch {:target-clinic "clinic-abc"}}
    :approval {:status :approved :by "human-01"}
    :label "Low-confidence proposal -> soft gate -> human APPROVED"}])

(defn- advisor-for [spec]
  (if (= :mock (:advisor spec))
    (advisor/mock-advisor)
    (->DriftedAdvisor (:advisor spec))))

(defn run-scenarios!
  "Runs every scenario in `scenarios` against ONE shared seeded store,
  through the real compiled StateGraph, and records exactly which
  ledger facts each scenario appended.

  Correlation is by ledger-length snapshot around each scenario, NOT by
  joining on (op, appointment-id). That join would be wrong here: this
  actor's ledger facts carry `:op` but no appointment id at all, and the
  same op is exercised by several scenarios, so any such join would let
  one scenario inherit another's decision.

  Returns {:store .. :ledger .. :runs [..]}."
  []
  (let [st (store/demo-store)
        runs
        (reduce
         (fn [acc {:keys [id phase appt-id request approval] :as spec}]
           (let [before (count (store/audit-log st))
                 actor  (operation/build (advisor-for spec) (governor/make-governor) st)
                 tid    (str "console-" id)
                 r1     (g/run* actor
                                {:request (assoc request :appt-id appt-id) :phase phase}
                                {:thread-id tid})
                 r2     (when (and approval (= :interrupted (:status r1)))
                          (g/run* actor {:approval approval}
                                  {:thread-id tid :resume? true}))
                 final  (or r2 r1)
                 after  (vec (store/audit-log st))]
             (conj acc
                   (assoc spec
                          :paused?      (= :interrupted (:status r1))
                          :frontier     (:frontier r1)
                          :graph-status (:status final)
                          :disposition  (get-in final [:state :disposition])
                          :proposal     (get-in final [:state :proposal])
                          :verdict      (get-in final [:state :verdict])
                          :graph-audit  (get-in final [:state :audit])
                          :facts        (subvec after before)))))
         []
         scenarios)]
    {:store st :ledger (vec (store/audit-log st)) :runs runs}))

;; ----------------------------- classification -----------------------------

(defn hard-hold?
  "A HARD governor refusal: the governor itself returned violations.
  Deliberately NOT `(= :hold disposition)` -- an approval-rejected hold
  is also a `:hold`, carries NO violations, and is a rollout/approval
  gate decision by a human, not a governor refusal."
  [run]
  (boolean (and (= :hold (:disposition run))
                (seq (get-in run [:verdict :violations])))))

(defn approval-gate-hold?
  "A hold produced by a human rejecting at the `:request-approval`
  interrupt -- empty `:violations` by construction."
  [run]
  (boolean (and (= :hold (:disposition run))
                (empty? (get-in run [:verdict :violations])))))

(defn pending-escalation?
  "The graph paused at `interrupt-before :request-approval` and was
  never resumed -- no ledger fact at all."
  [run]
  (= :interrupted (:graph-status run)))

(defn- violation-rules [run]
  (mapv :rule (get-in run [:verdict :violations])))

(defn assert-invariants!
  "Throws (so nothing is written) unless the run is real evidence."
  [{:keys [runs ledger]}]
  (let [hard (filterv hard-hold? runs)
        seeded (set (keys store/demo-appointments))
        invented (remove (fn [{:keys [appt-id absent?]}]
                           (or absent? (contains? seeded appt-id)))
                         runs)
        ledger-hard (filterv #(and (= :proposal-rejected (:event-type %))
                                   (seq (:violations %)))
                             ledger)]
    (when (seq invented)
      (throw (ex-info "Scenario names an appointment id that is neither seeded nor declared absent"
                      {:offenders (mapv (juxt :id :appt-id) invented)
                       :seeded seeded})))
    (when (empty? hard)
      (throw (ex-info "Refusing to write operator-console.html: the run produced ZERO HARD governor holds, so the page would be evidence of nothing."
                      {:runs (count runs) :dispositions (frequencies (map :disposition runs))})))
    (when (empty? ledger-hard)
      (throw (ex-info "Refusing to write operator-console.html: the governor refused, but no :proposal-rejected fact with violations reached the append-only ledger."
                      {:hard-holds (count hard) :ledger (count ledger)})))
    {:hard-holds (count hard)
     :hard-rules (into (sorted-set) (map name (mapcat violation-rules hard)))}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw->s [k] (if (keyword? k) (name k) (str k)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join "" (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join "" (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

(defn- disposition-cell [run]
  (cond
    (hard-hold? run)
    (str "<span class=\"critical\">HARD hold &middot; "
         (esc (str/join ", " (map kw->s (violation-rules run)))) "</span>")

    (approval-gate-hold? run)
    "<span class=\"warn\">held &middot; human rejected</span>"

    (pending-escalation? run)
    "<span class=\"warn\">paused at :request-approval</span>"

    (:approval run)
    (str "<span class=\"ok\">approved &amp; committed</span>")

    (= :commit (:disposition run))
    "<span class=\"ok\">auto-committed</span>"

    :else (str "<span class=\"muted\">" (esc (kw->s (:disposition run))) "</span>")))

(defn- advisor-cell [run]
  (if (= :mock (:advisor run))
    "<span class=\"muted\">real advisor</span>"
    (str "<span class=\"warn\">drift &middot; " (esc (:drift run)) "</span>")))

;; ----------------------------- sections -----------------------------

(defn- registry-section [st]
  (let [appt-rows
        (for [id (sort (keys store/demo-appointments))
              :let [a (store/appointment st id)]]
          (row (code id)
               (esc (:patient-name a))
               (code (:provider-id a))
               (esc (:appointment-time a))
               (code (kw->s (:appointment-type a)))
               (if (and (:registered? a) (:verified? a))
                 "<span class=\"ok\">registered &amp; verified</span>"
                 "<span class=\"critical\">not verified</span>")))
        prov-rows
        (for [id (sort (keys store/demo-providers))
              :let [p (store/provider st id)]]
          (row (code id)
               (esc (:name p))
               (code (kw->s (:specialty p)))
               (esc (str/join ", " (:availability p)))
               (if (and (:registered? p) (:verified? p))
                 "<span class=\"ok\">registered &amp; verified</span>"
                 "<span class=\"critical\">not verified</span>")))]
    (str
     (section "Appointment register"
              (str "Read back through the <code>clinicops.store/Store</code> protocol after the run. "
                   "The governor re-derives verification from these records and never trusts a proposal's own "
                   (code ":appt-id") " claim.")
              (table ["Appointment" "Patient" "Provider" "Slot" "Type" "Store verification"] appt-rows))
     (section "Provider register"
              "Seeded provider records, read through the same protocol."
              (table ["Provider" "Name" "Specialty" "Availability (hours)" "Store verification"] prov-rows)))))

(defn- scenario-section [runs]
  (section
   "Scenario run"
   (str "Every scenario below was executed through the compiled "
        (code "langgraph") " StateGraph "
        "(<code>intake &rarr; advise &rarr; govern &rarr; decide &rarr; commit | request-approval &rarr; commit | hold</code>). "
        "Rows marked <em>drift</em> wrap the repo's real advisor and tamper with the proposal it produced, to reach "
        "governor rules that no well-formed request can reach. No governor rule is added or relaxed anywhere in this renderer.")
   (table ["#" "Scenario" "Phase" "Appointment" "Op (as proposed)" "Advisor" "Outcome" "Ledger facts"]
          (for [r runs]
            (row (code (:id r))
                 (esc (:label r))
                 (str (:phase r))
                 (str (code (:appt-id r))
                      (when (:absent? r) " <span class=\"critical\">not in store</span>"))
                 (code (kw->s (or (get-in r [:proposal :op]) (get-in r [:request :op]))))
                 (advisor-cell r)
                 (disposition-cell r)
                 (str (count (:facts r))))))))

(defn- hard-hold-section [runs]
  (let [hard (filter hard-hold? runs)]
    (section
     (str "HARD governor holds (" (count hard) ")")
     (str "Permanent refusals by <code>clinicops.governor</code>. These are un-overridable: they never reach a human, "
          "and no approval exists that can release them. Rule names and detail text are the governor's own output, "
          "verbatim.")
     (table ["#" "Rule" "Governor detail" "Op" "Appointment"]
            (for [r hard
                  v (get-in r [:verdict :violations])]
              (row (code (:id r))
                   (str "<span class=\"critical\">" (esc (kw->s (:rule v))) "</span>")
                   (esc (:detail v))
                   (code (kw->s (or (get-in r [:proposal :op]) (get-in r [:request :op]))))
                   (code (:appt-id r))))))))

(defn- gate-hold-section [runs]
  (let [gate (filter approval-gate-hold? runs)
        pending (filter pending-escalation? runs)]
    (section
     (str "Approval / rollout gate holds (" (+ (count gate) (count pending)) ")")
     (str "Kept in a SEPARATE table on purpose. These holds carry an <strong>empty</strong> "
          (code ":violations") " vector &mdash; the governor did not refuse anything. They are a human declining at the "
          (code "interrupt-before") " pause, or a rollout phase that does not yet permit auto-commit. Counting them "
          "as governor refusals would overstate what the compliance layer caught.")
     (table ["#" "Scenario" "Kind" "Decided by" "Violations" "Ledger facts"]
            (concat
             (for [r gate]
               (row (code (:id r))
                    (esc (:label r))
                    "<span class=\"warn\">human rejected at approval</span>"
                    (code (get-in r [:approval :by]))
                    "<span class=\"muted\">none (empty)</span>"
                    (str (count (:facts r)))))
             (for [r pending]
               (row (code (:id r))
                    (esc (:label r))
                    (str "<span class=\"warn\">phase-"
                         (:phase r)
                         " rollout gate &middot; paused at "
                         (code (str/join ", " (map kw->s (:frontier r))))
                         "</span>")
                    "<span class=\"muted\">awaiting a human</span>"
                    "<span class=\"muted\">none (empty)</span>"
                    (str (count (:facts r))))))))))

(defn approver-attribution
  "Derived AT RENDER TIME, not asserted. Scans the ledger facts each
  approved scenario actually produced for an approver key, so this
  section reports whatever the store does today and self-corrects if the
  store is later fixed to carry the approver onto the committed record."
  [runs]
  (let [approver-keys #{:by :approver :approved-by :actor :decided-by}
        approved (filter #(and (:approval %) (= :commit (:disposition %))) runs)
        rejected (filter approval-gate-hold? runs)
        fact-approver (fn [f] (some (fn [k] (when (contains? f k) [k (get f k)])) approver-keys))
        scan (fn [rs] (for [r rs]
                        {:run r
                         :graph-approver (some fact-approver (:graph-audit r))
                         :record-approver (some fact-approver (:facts r))}))]
    {:approved (scan approved)
     :rejected (scan rejected)}))

(defn- approver-section [runs]
  (let [{:keys [approved rejected]} (approver-attribution runs)
        all (concat approved rejected)
        dropped (filter #(and (:graph-approver %) (nil? (:record-approver %))) all)
        verdict
        (cond
          (empty? all)
          "<span class=\"muted\">no approval path was exercised in this run</span>"
          (empty? dropped)
          "<span class=\"ok\">the store retains the approver on every approval-derived record in this run</span>"
          :else
          (str "<span class=\"critical\">the store drops the approver on "
               (count dropped) " of " (count all)
               " approval-derived records</span> &mdash; the approver exists in the graph's "
               (code ":audit") " channel but never reaches the append-only ledger, because the "
               (code ":commit")
               " node writes a fresh <code>:proposal-committed</code> fact instead of forwarding the "
               (code ":approval-granted") " entry."))]
    (section
     "Approver attribution"
     (str "Measured, not assumed. " verdict)
     (table ["#" "Scenario" "Human decision" "Approver in graph audit" "Approver on ledger record"]
            (for [{:keys [run graph-approver record-approver]} all]
              (row (code (:id run))
                   (esc (:label run))
                   (code (str (kw->s (get-in run [:approval :status])) " by "
                              (get-in run [:approval :by])))
                   (if graph-approver
                     (str (code (str (kw->s (first graph-approver)) " = " (second graph-approver))))
                     "<span class=\"muted\">absent</span>")
                   (if record-approver
                     (str "<span class=\"ok\">" (code (str (kw->s (first record-approver)) " = "
                                                           (second record-approver))) "</span>")
                     "<span class=\"critical\">absent</span> <span class=\"muted\">(audit only &mdash; not retained on record)</span>")))))))

(defn- gate-matrix-section []
  (section
   "Op gate matrix"
   (str "Derived from <code>clinicops.governor/allowed-ops</code>, "
        (code "always-escalate-ops") ", " (code "confidence-floor") " and "
        (code "clinicops.phase/phase-config") " at build time &mdash; this table is read out of the code, "
        "not transcribed from it. The confidence floor is "
        (code (str governor/confidence-floor)) ".")
   (table (into ["Op" "Always escalates"]
                (for [p (sort (keys phase/phase-config))]
                  (str "Phase " p " (" (kw->s (:name (phase/phase-for p))) ")")))
          (for [op (sort-by name governor/allowed-ops)]
            (apply row
                   (code (kw->s op))
                   (if (contains? governor/always-escalate-ops op)
                     "<span class=\"warn\">ALWAYS &middot; never auto at any phase</span>"
                     "<span class=\"muted\">no</span>")
                   (for [p (sort (keys phase/phase-config))]
                     (cond
                       (phase/can-auto-commit? p op) "<span class=\"ok\">auto-commit when clean</span>"
                       (phase/should-escalate? p op) "<span class=\"warn\">human approval</span>"
                       :else "<span class=\"muted\">not accepted</span>")))))))

(defn- ledger-section [ledger]
  (section
   (str "Append-only audit ledger (" (count ledger) " facts)")
   (str "Every fact this run appended to <code>clinicops.store</code>, in append order. "
        "Timestamps are deliberately not rendered so the page is byte-stable across runs. "
        "Note that these facts record <code>:op</code> but no appointment id, which is why this console "
        "correlates facts to scenarios by append position rather than by joining on (op, appointment).")
   (table ["#" "Fact" "Op" "Approver" "Basis"]
          (map-indexed
           (fn [i {:keys [event-type op by violations reason confidence]}]
             (row (str (inc i))
                  (let [n (kw->s event-type)]
                    (str "<span class=\""
                         (case event-type
                           :proposal-committed "ok"
                           (:proposal-rejected :approval-rejected) "critical"
                           "muted")
                         "\">" (esc n) "</span>"))
                  (code (kw->s (or op :n-a)))
                  (if by (code by) "<span class=\"muted\">&mdash;</span>")
                  (cond
                    (seq violations)
                    (esc (str/join ", " (map (comp kw->s :rule) violations)))
                    confidence (str "confidence " (esc confidence))
                    reason (esc reason)
                    :else "")))
           ledger))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole document from a completed `run-scenarios!` result."
  [{:keys [store ledger runs]}]
  (let [hard (filter hard-hold? runs)
        rules (into (sorted-set) (map (comp kw->s :rule) (mapcat #(get-in % [:verdict :violations]) hard)))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-862 &middot; clinicops operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Outpatient clinic coordination (ISIC 862) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; ADMINISTRATIVE / FACILITY COORDINATION ONLY &middot; no clinical authority</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>Generated at build time by <code>clinicops.render-html</code> (<code>clojure -M:dev:render-html</code>) by "
     "actually running <code>clinicops.operation/build</code> &mdash; a compiled <code>langgraph</code> StateGraph &mdash; "
     "over the seeded <code>clinicops.store</code>. Every appointment, provider, disposition, violation rule, violation "
     "detail and ledger fact below was read back out of that run. Nothing is hand-written sample data.</p>\n"
     "    <p class=\"muted\">This actor coordinates clinic <em>logistics</em>: appointment slots, referral paperwork, "
     "non-clinical consumables, administrative shifts and facility safety flags. Diagnosis, treatment, medication, "
     "clinical procedures, patient assessment, triage, discharge and end-of-life decisions are permanently out of "
     "scope &mdash; not un-implemented, but HARD-blocked by the governor's maximally conservative scope scan.</p>\n"
     "    <p><strong>" (count runs) "</strong> scenarios &middot; <strong>" (count hard)
     "</strong> HARD governor holds covering <strong>" (count rules) "</strong> distinct rules ("
     (esc (str/join ", " rules)) ") &middot; <strong>" (count ledger) "</strong> ledger facts. "
     "The build refuses to write this file if the run produces zero HARD holds.</p>\n"
     "  </section>\n"
     (registry-section store)
     (scenario-section runs)
     (hard-hold-section runs)
     (gate-hold-section runs)
     (approver-section runs)
     (gate-matrix-section)
     (ledger-section ledger)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-862 &middot; clinicops &middot; AGPL-3.0-or-later. "
     "Regenerate with <code>clojure -M:dev:render-html</code>. Deterministic: no timestamps are rendered, so two "
     "runs against the same seed are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-scenarios!)
        {:keys [hard-holds hard-rules]} (assert-invariants! result)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count (:runs result)) " scenarios, "
                  hard-holds " HARD governor holds ["
                  (str/join " " hard-rules) "], "
                  (count (:ledger result)) " ledger facts, "
                  (count html) " chars)"))))
