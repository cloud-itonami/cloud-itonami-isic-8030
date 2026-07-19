(ns investigation.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6+): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`investigation.operation` -> `investigation.policy` (the
  RoutingGovernor, titled that in its own docstring) -> `investigation.store`)
  through a scenario built from real seeded case/investigator ids read out
  of `investigation.store/demo-data` (verified by running
  `clojure -M:dev:run` before writing this file).

  Note on `investigation.sim`: it was NOT reused verbatim, unlike
  isic-6820's `realty.sim`. Running it first surfaced that its op3/op4
  ('investigator lacking required qualification' / 'investigator exceeding
  weekly capacity') both schedule the SAME investigator (inv-200) for the
  SAME 4-hour window on case-100, so BOTH proposals actually trip BOTH
  `:clearance-tier-gate` AND `:capacity-gate` simultaneously (confirmed
  ledger basis for both: `[:clearance-tier-gate :capacity-gate]`) --
  neither op isolates the single reason its own comment claims to
  demonstrate. Not the isic-851-class bug (ids are real and every hold is
  a genuine, correct HARD hold -- nothing here is unsafe), just an
  overlapping demo-design imprecision. This file instead builds its own
  requests, chosen so every HARD-hold row below isolates exactly one
  distinct governor rule (see `run-demo!`).

  Renders deterministically -- no invented numbers, no timestamps in the
  page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [investigation.store :as store]
            [investigation.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :case-manager :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario covering every disposition
  this actor can reach, using only real ids from `store/demo-data`
  (case-100/case-200/case-300, inv-100/inv-200/inv-300):

    rec-1     :log-case-record on case-100 (authorized)                    -> auto-commit
    sched-1   :schedule-investigation-operation, case-100 <- inv-100
              (has the required :insurance-siu-cert, 4h against inv-100's
              10h headroom)                                                -> auto-commit
    deliver-1 :coordinate-report-delivery on case-100 (authorized, clean)  -> auto-commit
    flag-1    :flag-legal-compliance-concern on case-300 (ALWAYS escalates,
              any phase/confidence -- `investigation.phase`'s :auto set
              never includes this op, `investigation.policy`'s :flag?
              check forces escalate independent of confidence)            -> escalate, approved -> commit

    sched-2   case-100 <- inv-200, 1h (inv-200 lacks :insurance-siu-cert;
              1h keeps inv-200's projected 19h <= its 20h capacity, so
              capacity-gate stays clean)              -> HARD hold, `:clearance-tier-gate` ALONE
    sched-3   case-100 <- inv-100, 10h (inv-100 already has 34h committed
              after sched-1; 34+10=44 > its 40h capacity; inv-100 DOES
              hold the required cert, so clearance-tier-gate stays clean)
                                                        -> HARD hold, `:capacity-gate` ALONE
    sched-4   case-300 <- inv-300, 1h (case-300's required-qualifications
              is the empty set, so clearance is trivially satisfied, and
              inv-300 has 40h of headroom, so capacity stays clean; only
              case-300's own authorization-verified? false fires)
                                                        -> HARD hold, `:authorization-gate` ALONE
    leak-1    :log-case-record on case-100 with `:leaky? true` -- the
              CaseCoordinator-LLM mock injects a `:conclusion` field
              (structural-scope-violations catches it; the case is
              authorized and every other check is clean)
                                                        -> HARD hold, `:structural-scope-gate` ALONE

  Every HARD hold above never reaches a human -- exactly one governor rule
  fires per row, unlike `investigation.sim`'s op3/op4 (see namespace
  docstring). Returns the resulting store -- every field `render` reads
  below is real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "rec-1"
           {:op :log-case-record :subject "case-100" :case-id "case-100"
            :record-id "rec-1" :description "photographed damaged vehicle per client claim (demo)"
            :custodian "inv-100"})

    (exec! actor "sched-1"
           {:op :schedule-investigation-operation :subject "case-100"
            :case-id "case-100" :schedule-id "sched-1" :investigator-id "inv-100"
            :window "2026-07-20 09:00-13:00" :hours 4})

    (exec! actor "deliver-1"
           {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
            :delivery-id "deliver-1" :recipient-channel "client-portal"
            :scheduled-at "2026-07-25T10:00:00Z"})

    (exec! actor "flag-1"
           {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
            :flag-id "flag-1"
            :concern-summary "client-authorization for case-300 has never been verified -- please confirm engagement documentation before further work"})
    (approve! actor "flag-1")

    (exec! actor "sched-2"
           {:op :schedule-investigation-operation :subject "case-100"
            :case-id "case-100" :schedule-id "sched-2" :investigator-id "inv-200"
            :window "2026-07-21 09:00-10:00" :hours 1})

    (exec! actor "sched-3"
           {:op :schedule-investigation-operation :subject "case-100"
            :case-id "case-100" :schedule-id "sched-3" :investigator-id "inv-100"
            :window "2026-07-22 09:00-19:00" :hours 10})

    (exec! actor "sched-4"
           {:op :schedule-investigation-operation :subject "case-300"
            :case-id "case-300" :schedule-id "sched-4" :investigator-id "inv-300"
            :window "2026-07-23 09:00-10:00" :hours 1})

    (exec! actor "leak-1"
           {:op :log-case-record :subject "case-100" :case-id "case-100"
            :record-id "leak-1" :description "final assessment (demo)" :custodian "inv-100"
            :leaky? true})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell
  "This repo's real persisted ledger fact `:t` values (confirmed by
  running `clojure -M:dev:run` and reading `investigation.operation`'s
  `commit-fact` / `investigation.policy`'s `hold-fact`) are `:committed`
  and `:policy-hold` -- NOT the template's default `:governor-hold` /
  `:approval-granted` (those never appear in the persisted
  `investigation.store/ledger`: `:approval-granted` is only ever an
  ephemeral per-run `:audit` channel entry from
  `investigation.operation`'s `:request-approval` node, never passed to
  `store/append-ledger!`; the final `:commit` node always writes plain
  `:committed` regardless of whether the path went through human
  approval)."
  [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :policy-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- case-row [ledger {:keys [id client-id matter-type status authorization-verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc client-id) (esc matter-type) (esc (name (or status :n-a)))
          (if authorization-verified?
            "<span class=\"ok\">verified</span>"
            "<span class=\"warn\">unverified</span>")
          (status-cell ledger id)))

(defn- investigator-row [{:keys [id name qualifications weekly-capacity-hours committed-hours]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s / %s h</td></tr>"
          (esc id) (esc name)
          (esc (str/join ", " (sort (map clojure.core/name qualifications))))
          (esc committed-hours) (esc weekly-capacity-hours)))

(defn- ledger-row [{:keys [t op subject basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (`investigation.policy/allowed-ops`, `investigation.phase/phases`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-case-record</code></td><td><span class=\"ok\">auto-commit when clean, case authorized</span></td></tr>"
   "        <tr><td><code>:schedule-investigation-operation</code></td><td><span class=\"ok\">auto-commit when clean</span> &middot; clearance-tier + weekly-capacity independently checked</td></tr>"
   "        <tr><td><code>:coordinate-report-delivery</code></td><td><span class=\"ok\">auto-commit when clean</span> &middot; delivery LOGISTICS only, never report content</td></tr>"
   "        <tr><td><code>:flag-legal-compliance-concern</code></td><td><span class=\"warn\">ALWAYS human approval, any phase/confidence</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db` that
  has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        cases (->> (store/all-case-files db)
                   (filter #(#{"case-100" "case-200" "case-300"} (:id %)))
                   (sort-by :id))
        investigators (->> (store/all-investigators db)
                            (filter #(#{"inv-100" "inv-200" "inv-300"} (:id %)))
                            (sort-by :id))
        case-rows (str/join "\n" (map (partial case-row ledger) cases))
        investigator-rows (str/join "\n" (map investigator-row investigators))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-8030 &middot; investigation activities</title><style>\n"
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
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Investigation activities (ISIC 8030) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · RoutingGovernor-gated · never renders a conclusion, never authorizes surveillance</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Case files</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>investigation.store</code> via <code>investigation.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Case</th><th>Client</th><th>Matter type</th><th>Status</th><th>Authorization</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     case-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Investigators</h2>\n"
     "    <p class=\"muted\">Committed hours reflect this run's auto-committed scheduling only.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Investigator</th><th>Name</th><th>Qualifications</th><th>Committed / capacity</th></tr></thead>\n"
     "      <tbody>\n"
     investigator-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (RoutingGovernor, <code>investigation.policy</code>)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. This actor never renders an investigative conclusion and never authorizes a surveillance method — neither is an op in the closed allowlist at all.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-case-files db)) "cases,"
             (count (store/all-investigators db)) "investigators )")))
