(ns investigation.store
  "SSoT for the private-investigation case-coordination actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`. Uses
                       `langchain-store.core`'s shared codec/schema/entity
                       field-spec machinery (ADR-2607141600) instead of
                       hand-rolling `enc`/`dec*`.

  Both implement the same protocol and pass the same contract
  (test/investigation/store_contract_test.clj) — the actor, the
  RoutingGovernor and the audit ledger never know which SSoT they run on.

  Entity shapes: a case-file (matter metadata — client-id, matter-type,
  status, required-qualifications, AND its own authorization-verification
  state — never a rendered investigative conclusion/finding/verdict), an
  investigator (licensed contractor — qualifications, weekly capacity,
  committed hours), a case-record (one evidence chain-of-custody log
  entry per case — never raw client PII beyond what's needed to identify
  the case), a schedule-entry (investigator↔case field-work assignment),
  a compliance-flag (a surfaced legal/compliance concern — always
  human-reviewed, see `investigation.phase`), and a report-delivery
  (delivery-LOGISTICS coordination for an already-approved report — never
  the report's own content). There is NO field anywhere in this schema
  for a rendered investigative conclusion, a guilt/liability
  determination, or a surveillance-method authorization — the scope
  boundary is structural, not a runtime filter someone could forget to
  call (defense-in-depth alongside `investigation.policy`'s scope-
  exclusion-gate, which also scans proposal text for the same exclusion).

  The ledger stays append-only on every backend — 'who logged/scheduled/
  flagged/coordinated what, on what authorization/qualification basis' is
  always a query over an immutable log."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (case-file [s id])
  (all-case-files [s])
  (investigator [s id])
  (all-investigators [s])
  (case-record [s id])
  (case-records-of [s case-id])
  (schedule-entry [s id])
  (schedule-entries-of-investigator [s investigator-id])
  (compliance-flag [s id])
  (report-delivery [s id])
  (ledger [s])
  (commit-record! [s op value] "apply a committed op's record to the SSoT (dispatch on `op`, since every proposal's `:effect` is always literally `:propose` — see investigation.policy's effect-invariant-gate)")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-case-files [s xs]        "replace/seed case-files (map id→case-file)")
  (with-investigators [s xs]     "replace/seed investigators (map id→investigator)")
  (with-case-records [s xs]      "replace/seed case-records (map id→case-record)")
  (with-schedule-entries [s xs]  "replace/seed schedule-entries (map id→schedule-entry)")
  (with-compliance-flags [s xs]  "replace/seed compliance-flags (map id→compliance-flag)")
  (with-report-deliveries [s xs] "replace/seed report-deliveries (map id→report-delivery)"))

;; ───────────────────────── demo data (fictitious, non-real people) ──────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real client/investigator/subject is ever named in this
  repository."
  []
  {:case-files
   {"case-100" {:id "case-100" :client-id "cl-100" :matter-type "Insurance fraud claim review (demo)"
                :status :open :required-qualifications #{:insurance-siu-cert}
                :authorization-verified? true :legal-basis "signed client engagement letter on file (demo)"}
    "case-200" {:id "case-200" :client-id "cl-100" :matter-type "Pre-employment background check (demo)"
                :status :open :required-qualifications #{:background-check-fcra-cert}
                :authorization-verified? true :legal-basis "FCRA-compliant consent form on file (demo)"}
    "case-300" {:id "case-300" :client-id "cl-200" :matter-type "Skip-trace records research (demo)"
                :status :open :required-qualifications #{}
                :authorization-verified? false :legal-basis nil}}
   :investigators
   {"inv-100" {:id "inv-100" :name "Investigator A (demo)"
               :qualifications #{:state-pi-license :insurance-siu-cert}
               :weekly-capacity-hours 40 :committed-hours 30}
    "inv-200" {:id "inv-200" :name "Investigator B (demo)"
               :qualifications #{:state-pi-license}
               :weekly-capacity-hours 20 :committed-hours 18}
    "inv-300" {:id "inv-300" :name "Investigator C (demo)"
               :qualifications #{:state-pi-license :records-research-cert}
               :weekly-capacity-hours 40 :committed-hours 0}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (case-file [_ id] (get-in @a [:case-files id]))
  (all-case-files [_] (sort-by :id (vals (:case-files @a))))
  (investigator [_ id] (get-in @a [:investigators id]))
  (all-investigators [_] (sort-by :id (vals (:investigators @a))))
  (case-record [_ id] (get-in @a [:case-records id]))
  (case-records-of [_ case-id]
    (->> (vals (:case-records @a)) (filter #(= case-id (:case-id %))) (sort-by :id)))
  (schedule-entry [_ id] (get-in @a [:schedule-entries id]))
  (schedule-entries-of-investigator [_ investigator-id]
    (->> (vals (:schedule-entries @a)) (filter #(= investigator-id (:investigator-id %))) (sort-by :id)))
  (compliance-flag [_ id] (get-in @a [:compliance-flags id]))
  (report-delivery [_ id] (get-in @a [:report-deliveries id]))
  (ledger [_] (:ledger @a))
  (commit-record! [s op {:keys [id investigator-id] :as value}]
    (case op
      :log-case-record
      (swap! a assoc-in [:case-records id] (assoc value :status :proposed))

      :schedule-investigation-operation
      (do (swap! a assoc-in [:schedule-entries id] (assoc value :status :proposed))
          (swap! a update-in [:investigators investigator-id :committed-hours]
                 (fnil + 0) (:hours value 0)))

      :flag-legal-compliance-concern
      (swap! a assoc-in [:compliance-flags id] (assoc value :status :logged))

      :coordinate-report-delivery
      (swap! a assoc-in [:report-deliveries id] (assoc value :status :proposed))

      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-case-files [s xs]        (when (seq xs) (swap! a assoc :case-files xs)) s)
  (with-investigators [s xs]     (when (seq xs) (swap! a assoc :investigators xs)) s)
  (with-case-records [s xs]      (when (seq xs) (swap! a assoc :case-records xs)) s)
  (with-schedule-entries [s xs]  (when (seq xs) (swap! a assoc :schedule-entries xs)) s)
  (with-compliance-flags [s xs]  (when (seq xs) (swap! a assoc :compliance-flags xs)) s)
  (with-report-deliveries [s xs] (when (seq xs) (swap! a assoc :report-deliveries xs)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                            :case-records {} :schedule-entries {}
                            :compliance-flags {} :report-deliveries {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────
;; Entity field-specs drive map->tx/pull->map/pull-pattern via
;; langchain-store.core (ADR-2607141600) — no hand-rolled enc/dec*.

(def ^:private case-file-spec
  {:id                       {:attr :case/id}
   :client-id                {:attr :case/client-id}
   :matter-type              {:attr :case/matter-type}
   :status                   {:attr :case/status}
   :required-qualifications  {:attr :case/required-qualifications :blob? true :default #{}}
   :authorization-verified?  {:attr :case/authorization-verified?}
   :legal-basis              {:attr :case/legal-basis}})

(def ^:private investigator-spec
  {:id                     {:attr :investigator/id}
   :name                   {:attr :investigator/name}
   :qualifications         {:attr :investigator/qualifications :blob? true :default #{}}
   :weekly-capacity-hours  {:attr :investigator/weekly-capacity-hours}
   :committed-hours        {:attr :investigator/committed-hours :default 0}})

(def ^:private case-record-spec
  {:id          {:attr :case-record/id}
   :case-id     {:attr :case-record/case-id}
   :description {:attr :case-record/description}
   :custodian   {:attr :case-record/custodian}
   :status      {:attr :case-record/status}})

(def ^:private schedule-entry-spec
  {:id              {:attr :schedule/id}
   :case-id         {:attr :schedule/case-id}
   :investigator-id {:attr :schedule/investigator-id}
   :window          {:attr :schedule/window}
   :hours           {:attr :schedule/hours}
   :status          {:attr :schedule/status}})

(def ^:private compliance-flag-spec
  {:id              {:attr :compliance-flag/id}
   :case-id         {:attr :compliance-flag/case-id}
   :concern-summary {:attr :compliance-flag/concern-summary}
   :status          {:attr :compliance-flag/status}})

(def ^:private report-delivery-spec
  {:id                {:attr :report-delivery/id}
   :case-id           {:attr :report-delivery/case-id}
   :recipient-channel {:attr :report-delivery/recipient-channel}
   :scheduled-at      {:attr :report-delivery/scheduled-at}
   :status            {:attr :report-delivery/status}})

(def ^:private schema
  (ls/identity-schema [:case/id :investigator/id :case-record/id
                        :schedule/id :compliance-flag/id :report-delivery/id
                        :ledger/seq]))

(defn- pull1
  "Pull one entity by its identity lookup-ref `[id-attr id]`, shaped via
  `spec` (langchain-store field-spec)."
  [conn spec id-attr id]
  (ls/pull->map spec :id (d/pull (d/db conn) (ls/pull-pattern spec) [id-attr id])))

(defn- pull-all
  "All entity ids carrying `id-attr`, each pulled+shaped via `spec`."
  [conn spec id-attr]
  (->> (d/q {:find ['?id '...] :where [['?e id-attr '?id]]} (d/db conn))
       (map #(pull1 conn spec id-attr %))
       (sort-by :id)))

(defrecord DatomicStore [conn]
  Store
  (case-file [_ id] (pull1 conn case-file-spec :case/id id))
  (all-case-files [_] (pull-all conn case-file-spec :case/id))
  (investigator [_ id] (pull1 conn investigator-spec :investigator/id id))
  (all-investigators [_] (pull-all conn investigator-spec :investigator/id))
  (case-record [_ id] (pull1 conn case-record-spec :case-record/id id))
  (case-records-of [_ case-id]
    (->> (d/q '[:find [?id ...] :in $ ?cid
                :where [?e :case-record/case-id ?cid] [?e :case-record/id ?id]]
              (d/db conn) case-id)
         (map #(pull1 conn case-record-spec :case-record/id %))
         (sort-by :id)))
  (schedule-entry [_ id] (pull1 conn schedule-entry-spec :schedule/id id))
  (schedule-entries-of-investigator [_ investigator-id]
    (->> (d/q '[:find [?id ...] :in $ ?iid
                :where [?e :schedule/investigator-id ?iid] [?e :schedule/id ?id]]
              (d/db conn) investigator-id)
         (map #(pull1 conn schedule-entry-spec :schedule/id %))
         (sort-by :id)))
  (compliance-flag [_ id] (pull1 conn compliance-flag-spec :compliance-flag/id id))
  (report-delivery [_ id] (pull1 conn report-delivery-spec :report-delivery/id id))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (commit-record! [s op {:keys [investigator-id] :as value}]
    (case op
      :log-case-record
      (d/transact! conn [(ls/map->tx case-record-spec (assoc value :status :proposed))])

      :schedule-investigation-operation
      (do (d/transact! conn [(ls/map->tx schedule-entry-spec (assoc value :status :proposed))])
          (let [inv (investigator s investigator-id)]
            (d/transact! conn
              [(ls/map->tx investigator-spec
                            (update inv :committed-hours (fnil + 0) (:hours value 0)))])))

      :flag-legal-compliance-concern
      (d/transact! conn [(ls/map->tx compliance-flag-spec (assoc value :status :logged))])

      :coordinate-report-delivery
      (d/transact! conn [(ls/map->tx report-delivery-spec (assoc value :status :proposed))])

      nil)
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-case-files [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx case-file-spec %) (vals xs)))) s)
  (with-investigators [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx investigator-spec %) (vals xs)))) s)
  (with-case-records [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx case-record-spec %) (vals xs)))) s)
  (with-schedule-entries [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx schedule-entry-spec %) (vals xs)))) s)
  (with-compliance-flags [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx compliance-flag-spec %) (vals xs)))) s)
  (with-report-deliveries [s xs]
    (when (seq xs) (d/transact! conn (mapv #(ls/map->tx report-delivery-spec %) (vals xs)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [case-files investigators case-records schedule-entries
            compliance-flags report-deliveries]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-case-files case-files) (with-investigators investigators)
         (with-case-records case-records) (with-schedule-entries schedule-entries)
         (with-compliance-flags compliance-flags) (with-report-deliveries report-deliveries)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str (name disposition) " · op=" op " · actor=" actor " · subject=" subject
       " · basis=" (pr-str basis)))
