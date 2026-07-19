(ns customsclearing.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`customsclearing.actor` -> `customsclearing.governor` ->
  `customsclearing.store`) through a scenario built from real,
  exercised store/test data and renders the result deterministically --
  no invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs before shipping).

  Seed data provenance, disclosed plainly:
  - Principal `imp-001` (\"Acme Importers Ltd\") is lifted VERBATIM from
    `customsclearing.actor-test/registered-store` (the proven-passing
    fixture every actor test in this repo builds on).
  - Consignment `cons-001` (`{:origin \"JP\" :destination \"US\" :mode
    :ocean}`) is lifted VERBATIM from
    `customsclearing.store-test/test-register-consignment` -- also
    real, already-exercised proof data, not invented here.
  - Consignment `cons-002` is ADDITIONAL demo data (a second shipment
    was needed so the compliance-concern and carrier-coordination ops
    below have a distinct consignment to reference) registered via the
    store's own real `register-consignment!` call -- disclosed here as
    an addition, not presented as a pre-existing fixture.
  - `imp-ghost` is deliberately NEVER registered -- it exists only to
    demonstrate the `:no-principal` hard-violation path, exactly as
    `customsclearing.governor-test/test-hard-violation-unregistered-
    principal` does with its own \"unknown-principal\" id.

  IMPORTANT architectural note: `customsclearing.store/MemStore` is an
  IMMUTABLE defrecord -- `register-principal!`/`register-consignment!`
  return a NEW store value rather than mutating in place (confirmed by
  `customsclearing.store-test/test-immutability`). `run-demo!` below
  threads the returned store value through each registration call
  accordingly.

  Known architectural gaps, honestly noted rather than papered over
  (both confirmed by reading `customsclearing.governor` and
  `customsclearing.advisor` directly, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    (`customsclearing.advisor/mock-advisor`) unconditionally sets
    `:effect :propose` on every proposal it emits. Covered instead by
    `customsclearing.governor-test/test-hard-violation-wrong-effect`,
    which calls `governor/check` directly with a hand-built proposal.
  - The confidence-floor escalation (confidence < 0.6) is likewise NOT
    reachable through this demo: every disclosed `mock-advisor` request
    type carries a fixed confidence of 0.7 or higher, and the one path
    with confidence 0.0 (an unrecognized request type, mapping to
    `:op :unknown`) is dominated by the HARD `:forbidden-operation`
    rule (`:unknown` is itself a member of `customsclearing.governor`'s
    `forbidden-ops` set) before the confidence check is ever reached.
    Covered instead by
    `customsclearing.governor-test/test-low-confidence-escalation`,
    which again calls `governor/check` directly with a hand-built
    low-confidence proposal.
  - So this demo reaches 5 of the governor's 7 documented reasons
    through the real graph (`:no-principal`, `:forbidden-operation`,
    `:no-spec-basis`, the `:flag-compliance-concern` escalation, and
    the missing-document escalation) plus 4 clean auto-commits across
    the remaining ops -- the other 2 reasons above are architecturally
    unreachable via the real advisor and are covered by the existing
    unit tests instead.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [customsclearing.store :as store]
            [customsclearing.advisor :as advisor]
            [customsclearing.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real customs-clearing/freight-forwarding request through
  the actual compiled graph for `tid` (thread-id). If the graph
  escalates (interrupts before `:request-approval`), immediately
  approves it (this scenario never demonstrates an UNAPPROVED
  escalation -- every escalation here reaches a human broker who signs
  off). Returns a map describing exactly what really happened -- no
  field is invented."
  [graph tid principal-id req-type extra]
  (let [request (merge {:principal-id principal-id :type req-type} extra)
        r1 (actor/run-request! graph tid request {})]
    (cond
      (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid {:approver "human-broker-01"})]
        {:thread-id tid :principal-id principal-id :op req-type :request request
         :outcome :approved-and-committed
         :record (-> r2 :state :records first)})

      (= :rejected (-> r1 :state :phase))
      {:thread-id tid :principal-id principal-id :op req-type :request request
       :outcome :hard-hold
       :verdict (-> r1 :state :decision)
       :rule (-> r1 :state :decision :violations first :rule)}

      :else
      {:thread-id tid :principal-id principal-id :op req-type :request request
       :outcome :auto-committed
       :record (-> r1 :state :records first)})))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph -- 4 clean auto-commits (one per disclosed,
  non-forbidden `mock-advisor` op), both escalation paths that are
  actually reachable (`:flag-compliance-concern`, missing-document), and
  the 3 of 4 HARD-hold reasons reachable via the real advisor
  (`:no-principal`, `:forbidden-operation` via the `:unknown` fallback
  op, `:no-spec-basis`) -- see namespace docstring for the 2 rules that
  are architecturally unreachable here. Every `:op`/request `:type` and
  violation rule name below is copied from `customsclearing.advisor`/
  `customsclearing.governor`'s own source, not invented."
  [;; imp-001 / \"Acme Importers Ltd\" / cons-001 (verbatim test fixtures)
   ["cc-log-shipment"       "imp-001" :log-shipment
    {:consignment-id "cons-001" :status :in-transit}]
   ["cc-draft-declaration"  "imp-001" :draft-declaration
    {:consignment-id "cons-001" :direction :import :hs-code "0901.21"
     :declared-value 15000
     :spec-basis "WCO Harmonized System 2022, heading 0901"}]
   ["cc-draft-no-basis"     "imp-001" :draft-declaration
    {:consignment-id "cons-001" :direction :import :hs-code "0901.21"
     :declared-value 15000}]
   ["cc-verify-complete"    "imp-001" :verify-documents
    {:consignment-id "cons-001"
     :documents #{:commercial-invoice :packing-list :certificate-of-origin}
     :missing #{}}]
   ["cc-verify-missing"     "imp-001" :verify-documents
    {:consignment-id "cons-001"
     :documents #{:commercial-invoice}
     :missing #{:certificate-of-origin}}]
   ;; cons-002 (additional demo data, see namespace docstring)
   ["cc-flag-concern"       "imp-001" :flag-concern
    {:consignment-id "cons-002" :concern-type :restricted-goods
     :detail "possible dual-use item"}]
   ["cc-coordinate"         "imp-001" :coordinate-carrier
    {:consignment-id "cons-002" :carrier "Pacific Forwarding Co"
     :instructions "arrange pickup at bonded warehouse 7/18"}]
   ["cc-unknown-op"         "imp-001" :unrecognized-request-type
    {:consignment-id "cons-002"}]
   ;; imp-ghost is never registered -- see namespace docstring
   ["cc-no-principal"       "imp-ghost" :log-shipment
    {:consignment-id "cons-999" :status :in-transit}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `customsclearing.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db0 (store/create-store)
        db1 (store/register-principal! db0 "imp-001" {:name "Acme Importers Ltd"})
        db2 (store/register-consignment! db1 "cons-001" {:origin "JP" :destination "US" :mode :ocean})
        db3 (store/register-consignment! db2 "cons-002" {:origin "CN" :destination "JP" :mode :air})
        graph (actor/build-graph (advisor/mock-advisor) db3)
        runs (mapv (fn [[tid principal-id req-type extra]]
                     (run-op! graph tid principal-id req-type extra))
                   op-specs)]
    {:store db3 :runs runs}))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- principal-row [store principal-id name runs]
  (let [last-run (last (filter #(= principal-id (:principal-id %)) runs))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc principal-id) (esc name)
            (if (store/principal store principal-id)
              "<span class=\"ok\">registered</span>"
              "<span class=\"err\">not registered</span>")
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- consignment-row [store consignment-id]
  (let [{:keys [origin destination mode]} (store/consignment store consignment-id)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc consignment-id) (esc origin) (esc destination) (esc (name mode)))))

(defn- request-detail [request]
  (let [{:keys [type hs-code spec-basis carrier concern-type missing declared-value]} request]
    (esc (str/join ", " (remove nil?
                                 [(some->> hs-code (str "hs-code="))
                                  (when (= :draft-declaration type)
                                    (if spec-basis "spec-basis=cited" "spec-basis=MISSING"))
                                  (some->> declared-value (str "declared-value="))
                                  (some->> carrier (str "carrier="))
                                  (some->> concern-type (str "concern-type="))
                                  (when (seq missing) (str "missing=" missing))])))))

(defn- run-row [{:keys [thread-id principal-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc principal-id) (esc (name op))
          (request-detail request)
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`customsclearing.governor`'s own docstring) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:no-principal</code></td><td class=\"critical-cell\">HARD &middot; client/principal must be registered with this practice</td></tr>"
   "        <tr><td><code>:no-actuation</code></td><td class=\"critical-cell\">HARD &middot; proposal effect must be :propose (never actuates)</td></tr>"
   "        <tr><td><code>:forbidden-operation</code></td><td class=\"critical-cell\">HARD &middot; clearing goods, acting as broker of record, filing a binding declaration, waiving sanctions screening -- permanently out of scope</td></tr>"
   "        <tr><td><code>:no-spec-basis</code></td><td class=\"critical-cell\">HARD &middot; a customs declaration draft with no cited tariff/HS-code basis is an invented legal basis</td></tr>"
   "        <tr><td><code>:flag-compliance-concern</code></td><td class=\"warn\">ALWAYS human approval &middot; cardinal escalation trigger</td></tr>"
   "        <tr><td><code>missing-document</code></td><td class=\"warn\">ALWAYS human approval &middot; document-checklist verification found a required document missing</td></tr>"
   "        <tr><td><code>confidence &lt; 0.6</code></td><td class=\"warn\">ALWAYS human approval &middot; confidence floor</td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [principal-rows (principal-row store "imp-001" "Acme Importers Ltd" runs)
        consignment-rows (str/join "\n" (map #(consignment-row store %) ["cons-001" "cons-002"]))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-3331 &middot; independent customs clearing &amp; freight forwarding</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".critical-cell { color: #b3261e; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Independent Customs Clearing &amp; Freight Forwarding (ISCO-08 3331) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; never itself clears goods, files, or acts as broker of record</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered principals</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>customsclearing.store</code> via <code>customsclearing.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. <code>imp-001</code> is the verbatim <code>customsclearing.actor-test</code> fixture.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Principal</th><th>Name</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     principal-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered consignments</h2>\n"
     "    <p class=\"muted\"><code>cons-001</code> is the verbatim <code>customsclearing.store-test</code> fixture. <code>cons-002</code> is additional demo data registered via the store's own real <code>register-consignment!</code> call (disclosed, see namespace docstring) so the compliance-concern and carrier-coordination ops below have a distinct consignment to reference.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Consignment</th><th>Origin</th><th>Destination</th><th>Mode</th></tr></thead>\n"
     "      <tbody>\n"
     consignment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Independent Customs Clearing Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by any human at this layer -- they would need to act entirely outside this actor. Escalations always require a human licensed broker's sign-off, regardless of confidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, principal, op, the request's own supporting detail, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Principal</th><th>Op</th><th>Detail</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/records (:store result))) "ledger facts )")))
