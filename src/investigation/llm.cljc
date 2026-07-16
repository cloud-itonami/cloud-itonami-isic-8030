(ns investigation.llm
  "CaseCoordinator-LLM — the *contained intelligence node*.

  It only drafts case-scheduling and evidence-logging COORDINATION
  proposals: an evidence chain-of-custody log entry, a field-work
  scheduling pairing, a surfaced legal/compliance concern, or a report-
  delivery logistics coordination. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record. Every output is censored downstream
  by `investigation.policy` (the RoutingGovernor) before anything touches
  the SSoT.

  This actor NEVER drafts, and this namespace has no code path that could
  ever produce, a proposal that renders an investigative conclusion
  (guilt/liability/fault determination) or that authorizes a surveillance
  method — those aren't ops this actor's closed allowlist recognizes at
  all (see `investigation.phase`'s `write-ops`), and even a malformed/
  malicious request naming such an op falls through `infer`'s default
  case to a low-confidence `:noop` with `:effect :noop` (never
  `:propose`), which `investigation.policy`'s effect-invariant-gate hard-
  rejects unconditionally.

  Every proposal's `:effect` is always literally `:propose` — this actor
  never claims to directly mutate a final record; a governor-clean
  disposition only ever commits a PROPOSAL-shaped SSoT entry (`:status
  :proposed`/`:logged`), never a finalized determination.

  Like `cloud-itonami-isic-8299`'s TaskRouter-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft
     :rationale  str            ; why — SCANNED by scope-exclusion-gate
     :cites      [kw ..]        ; fields the LLM used
     :effect     :propose       ; always -- see investigation.policy
     :value      map            ; the record patch
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defn- propose-log-case-record
  "Evidence chain-of-custody log-entry proposal. `:leaky?` injects the
  failure mode we must defend against: a rendered investigative
  conclusion (guilt/liability/fault) sneaking into the record —
  `investigation.policy`'s scope-exclusion-gate must reject this
  outright, structurally (there is no `:conclusion` field in the schema
  at all) AND textually (the rationale below never uses a finalization-
  ACTION phrase)."
  [{:keys [case-id record-id description custodian leaky?]}]
  (let [patch (cond-> {:id record-id :case-id case-id
                       :description description :custodian custodian}
                leaky? (assoc :conclusion "subject determined liable (demo — schema-excluded field)"))]
    {:summary    (str "evidence log entry for " case-id)
     :rationale  "Structures the chain-of-custody entry only (who logged what, when, custodian of record) -- no investigative finding is rendered here."
     :cites      (vec (keys (dissoc patch :conclusion)))
     :effect     :propose
     :value      patch
     :confidence 0.92}))

(defn- propose-schedule
  "Investigator↔case field-work scheduling proposal. The LLM only
  proposes a pairing; it does not itself verify qualification/capacity —
  that is the RoutingGovernor's job (clearance-tier-gate/capacity-gate),
  which is why this stays deliberately high-confidence even for a pairing
  the governor will reject: proving those gates cannot rely on confidence
  as a proxy for correctness."
  [{:keys [case-id schedule-id investigator-id window hours]}]
  {:summary    (str schedule-id " scheduling proposal: " investigator-id " -> " case-id)
   :rationale  "Based on the investigator's declared availability window; qualification-tier and weekly-capacity verification is the governor's job, not this proposal's."
   :cites      [:case-id :investigator-id :window :hours]
   :effect     :propose
   :value      {:id schedule-id :case-id case-id :investigator-id investigator-id
               :window window :hours hours}
   :confidence 0.92})

(defn- propose-flag-concern
  "Legal/compliance concern proposal. ALWAYS escalates downstream
  (`investigation.policy`/`investigation.phase` both force this to human
  review, independent of confidence) — this generator deliberately does
  not try to sound confident about its own finding, since the point of
  flagging is exactly that a human needs to look."
  [{:keys [case-id flag-id concern-summary]}]
  {:summary    (str "legal/compliance concern flagged on " case-id)
   :rationale  (str "Surfacing for mandatory human legal/compliance review, not resolving it here: " concern-summary)
   :cites      [:case-id]
   :effect     :propose
   :value      {:id flag-id :case-id case-id :concern-summary concern-summary}
   :confidence 0.7})

(defn- propose-report-delivery
  "Report-delivery LOGISTICS coordination proposal (recipient channel,
  scheduled time) for an already-approved report. `:greedy?` injects the
  failure mode we must defend against: the report's own content
  sneaking into what should be pure delivery-logistics coordination --
  `investigation.policy`'s scope-exclusion-gate must reject the excess
  field."
  [{:keys [case-id delivery-id recipient-channel scheduled-at greedy?]}]
  (let [patch (cond-> {:id delivery-id :case-id case-id
                       :recipient-channel recipient-channel :scheduled-at scheduled-at}
                greedy? (assoc :report-content "full findings narrative (demo — schema-excluded field)"))]
    {:summary    (str "report delivery coordination for " case-id)
     :rationale  "Coordinates only the delivery logistics of an already-approved report (recipient channel, scheduled time) -- carries no report content."
     :cites      (vec (keys (dissoc patch :report-content)))
     :effect     :propose
     :value      patch
     :confidence 0.9}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [{:keys [op] :as request}]
  (case op
    :log-case-record                 (propose-log-case-record request)
    :schedule-investigation-operation (propose-schedule request)
    :flag-legal-compliance-concern    (propose-flag-concern request)
    :coordinate-report-delivery       (propose-report-delivery request)
    {:summary "unsupported operation" :rationale (str op) :cites [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a
;; real LLM in production. Either way its output is a PROPOSAL the
;; RoutingGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default
  everywhere. `store` is accepted for protocol-symmetry with other
  cloud-itonami actors but unused -- none of these four proposal kinds
  needs to read the SSoT to draft a proposal (the governor does all the
  store-dependent checking)."
  []
  (reify Advisor (-advise [_ _store request] (infer request))))

(def ^:private system-prompt
  (str "You are a private-investigation case-coordination advisor. Based only on the "
       "given facts, return exactly one proposal as an EDN map, no prose before or after.\n"
       "Keys: :summary (human-facing draft) :rationale (why -- never a finalization/"
       "execution action phrase) :cites (vector of fact keys used) :effect (always "
       ":propose, literally -- you never claim to finalize or authorize anything) "
       ":value (the record patch) :confidence (0..1).\n"
       "IMPORTANT: you coordinate case-scheduling and evidence-logging only. You must "
       "NEVER render an investigative conclusion (guilt, liability, fault) and you must "
       "NEVER authorize any surveillance method -- neither is a field in this schema, "
       "and neither is a capability you have."))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the RoutingGovernor
  escalates/holds — an LLM hiccup can never auto-commit."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "could not parse LLM response" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference).
  Pass `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/
  kotoba), or `model/mock-model` for offline tests. `gen-opts` is
  forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _store req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "op: " (:op req) "\nsubject: " (:subject req)
                                              "\nfacts: " (pr-str (dissoc req :op :subject)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (compliance review, audits). Persisted to the :audit
  channel."
  [request proposal]
  {:t          :casecoordinatorllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
