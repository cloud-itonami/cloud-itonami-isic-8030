(ns investigation.policy
  "RoutingGovernor — the independent compliance layer that earns the
  CaseCoordinator-LLM the right to log, schedule, flag or coordinate.
  The LLM has no notion of case-authorization status, investigator
  qualification/capacity limits, or this domain's structural scope
  boundary (never a rendered investigative conclusion, never a
  surveillance-method authorization), so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD — this actor's analog
  of `cloud-itonami-isic-8299`'s RoutingGovernor and robotaxi's Minimal
  Risk Condition.

  This actor coordinates case-scheduling/evidence-logging only. It NEVER
  renders an investigative conclusion and NEVER authorizes a surveillance
  method — those are not ops in the closed allowlist at all, and the
  scope-exclusion-gate below is a PERMANENT, non-overridable hard block
  against any proposal that attempts either, defense-in-depth against a
  future allowlist change or an advisor bug, not merely 'currently
  unimplemented.'

  Ten checks, in priority order. The first eight are HARD violations: a
  human approver CANNOT override them. The last two are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                     — does actor-role have permission for op?
    2. op-allowlist-gate        — is op one of the four closed-allowlist
                                   ops at all? (defense-in-depth alongside
                                   rbac; catches a misconfigured role
                                   grant, not just an unauthorized role)
    3. effect-invariant-gate    — is the proposal's `:effect` literally
                                   `:propose`? This actor never claims to
                                   directly finalize/mutate a determination
                                   — ANY other effect value is a hard,
                                   permanent rejection regardless of which
                                   op it's attached to.
    4. authorization-gate       — does the case have an independently
                                   verified/registered client-authorization
                                   record on file? (exempts
                                   :flag-legal-compliance-concern — a
                                   compliance concern must be raisable
                                   about a case EVEN WHEN its own
                                   authorization is missing/unverified;
                                   gating the flag itself behind the very
                                   check it might be flagging would
                                   suppress exactly the report this actor
                                   most needs to surface, and it already
                                   always escalates to a human via check 9
                                   below, so it needs no additional
                                   authorization precondition to be safe.)
    5. clearance-tier-gate      — (only :schedule-investigation-operation)
                                   does the proposed investigator hold
                                   every qualification the case requires?
                                   An investigator or case citing a
                                   qualification class outside
                                   `investigation.facts/allowed-
                                   qualification-classes` is rejected
                                   outright.
    6. capacity-gate            — (only :schedule-investigation-operation)
                                   would this scheduling push the
                                   investigator's committed hours past
                                   their weekly capacity?
    7. structural-scope-gate    — does the proposal's :value carry a
                                   schema-excluded field (a rendered
                                   conclusion/verdict/determination, a
                                   surveillance-method authorization, or
                                   raw report content)?
    8. scope-exclusion-gate     — does the proposal's :summary/:rationale
                                   TEXT contain a finalization-of-
                                   investigation-conclusion or
                                   surveillance-method-authorization
                                   ACTION phrase? Phrased as the
                                   finalization/execution ACTION (e.g.
                                   'finalize the investigation
                                   conclusion'), deliberately never a bare
                                   noun ('conclusion'/'surveillance') --
                                   this actor's own default advisor
                                   legitimately mentions 'surveillance
                                   operation' as a routine field-work
                                   TYPE when scheduling, and a bare-noun
                                   exclusion list would self-trip on that
                                   normal text (a known bug class in this
                                   actor family; see
                                   scope_exclusion_test.clj).
    9. confidence floor         — LLM confidence below threshold →
                                   escalate.
   10. flag-legal-compliance    — :flag-legal-compliance-concern NEVER
                                   auto-resolves, at any confidence, any
                                   phase — it always reaches a human."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [investigation.facts :as facts]
            [investigation.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def allowed-ops
  "The closed op-allowlist. Every proposal not naming one of these is a
  hard rejection — this actor never grows a fifth op without a deliberate
  code change here, never at proposal time."
  #{:log-case-record :schedule-investigation-operation
    :flag-legal-compliance-concern :coordinate-report-delivery})

(def permissions
  "actor-role → set of operations it may perform."
  {:case-manager   allowed-ops
   :investigator   #{:log-case-record :flag-legal-compliance-concern}
   :client-liaison #{:coordinate-report-delivery :flag-legal-compliance-concern}})

(def private-value-keys
  "Fields that must NEVER appear in a proposal's :value — a rendered
  investigative conclusion/finding/verdict, a surveillance-method
  authorization, or raw report content. There is no corresponding field
  in `investigation.store`'s schema at all for any of these — this check
  exists as defense in depth against an LLM (or a future schema change)
  smuggling one in, not as the primary control."
  #{:conclusion :verdict :guilt-determination :liability-determination
    :fault-determination :surveillance-method :surveillance-authorization
    :covert-surveillance-method :report-content :raw-report-content})

(def scope-exclusion-phrases
  "Phrases that, if present (case-insensitive substring) in a proposal's
  :summary or :rationale TEXT, indicate an attempt to directly finalize
  an investigation conclusion or authorize a surveillance method.
  Deliberately phrased as the finalization/execution ACTION, never a bare
  noun — a bare noun like 'conclusion' or 'surveillance' would match
  inside this actor's own default mock-advisor rationale for a
  LEGITIMATE, allowed proposal (e.g. a schedule proposal's rationale
  routinely mentions 'surveillance operation' as a field-work TYPE, and a
  case's natural lifecycle language routinely mentions reaching a
  'conclusion' in an unrelated, harmless sense like 'pending conclusion
  of the records-research phase'). This is a known self-tripping bug
  class independently rediscovered across sibling actors in this family
  — see scope_exclusion_test.clj, which asserts none of these phrases
  ever appears in ANY default mock-advisor proposal for a legitimate
  request."
  #{"finalize the investigation conclusion"
    "finalizes the investigation conclusion"
    "finalizing the investigation conclusion"
    "finalize the case conclusion"
    "render a finding of guilt"
    "render a determination of liability"
    "render a determination of fault"
    "determine the subject's guilt"
    "determine the subject's liability"
    "issue a finding of fault"
    "authorize covert surveillance"
    "authorize a surveillance method"
    "authorize the surveillance method"
    "authorize wiretap surveillance"
    "authorize physical surveillance deployment"
    "authorize the deployment of a surveillance method"})

(def confidence-floor 0.6)

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " is not permitted to perform " op)}]))

(defn- op-allowlist-violations [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-allowlist-gate :detail (str op " is not in the closed op allowlist")}]))

(defn- effect-invariant-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-invariant-gate
      :detail (str "proposal :effect must always be :propose, got " (pr-str (:effect proposal)))}]))

(defn- authorization-violations
  "Every op except :flag-legal-compliance-concern requires a verified
  case-authorization record on file before it may proceed at all."
  [{:keys [op]} proposal st]
  (when (not= op :flag-legal-compliance-concern)
    (let [case-id (get-in proposal [:value :case-id])
          cf (when case-id (store/case-file st case-id))]
      (cond
        (nil? cf)
        [{:rule :authorization-gate :detail (str "no case on file: " case-id)}]

        (not (:authorization-verified? cf))
        [{:rule :authorization-gate
          :detail (str "case " case-id " has no independently verified client-authorization record on file")}]))))

(defn- clearance-tier-violations
  "Only :schedule-investigation-operation proposes an investigator↔case
  pairing."
  [{:keys [op]} proposal st]
  (when (= op :schedule-investigation-operation)
    (let [{:keys [case-id investigator-id]} (:value proposal)
          cf  (store/case-file st case-id)
          inv (store/investigator st investigator-id)
          required (:required-qualifications cf #{})
          unknown  (remove facts/class-allowed? required)
          missing  (set/difference required (:qualifications inv #{}))]
      (cond
        (seq unknown)
        [{:rule :clearance-tier-gate
          :detail (str "case requires unknown qualification class(es): " (vec unknown))}]

        (seq missing)
        [{:rule :clearance-tier-gate
          :detail (str "investigator lacks required qualification(s): " (vec missing))}]))))

(defn- capacity-violations
  [{:keys [op]} proposal st]
  (when (= op :schedule-investigation-operation)
    (let [{:keys [investigator-id hours]} (:value proposal)
          inv (store/investigator st investigator-id)
          projected (+ (:committed-hours inv 0) (or hours 0))]
      (when (> projected (:weekly-capacity-hours inv 0))
        [{:rule :capacity-gate
          :detail (str "investigator's weekly capacity would be exceeded: "
                       projected " > " (:weekly-capacity-hours inv 0))}]))))

(defn- structural-scope-violations [proposal]
  (let [ks  (set (keys (:value proposal)))
        bad (set/intersection ks private-value-keys)]
    (when (seq bad)
      [{:rule :structural-scope-gate
        :detail (str "proposal :value carries schema-excluded field(s): " (vec bad))}])))

(defn scope-exclusion-violations
  "PUBLIC (not `defn-`) specifically so it can be unit-tested directly
  against every default mock-advisor proposal, independent of the rest of
  the governed pipeline/store setup — see
  test/investigation/scope_exclusion_test.clj, which asserts this never
  fires against a legitimate proposal's own default text (the self-
  tripping bug class documented on `scope-exclusion-phrases` above)."
  [proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))
        hit  (some #(when (str/includes? text %) %) scope-exclusion-phrases)]
    (when hit
      [{:rule :scope-exclusion-gate
        :detail (str "proposal text contains a finalization/surveillance-authorization action phrase: " (pr-str hit))}])))

(defn check
  "Censors a CaseCoordinator-LLM proposal against the policy tables.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :hard? bool :flag? bool}.

  - :hard?     — at least one HARD violation. Forces HOLD; a human cannot
                 override.
  - :escalate? — soft: low confidence OR a compliance-concern flag. A
                 human decides.
  - :ok?       — clean AND not escalating: safe to auto-commit."
  [request context proposal st]
  (let [hard (into []
                   (concat (rbac-violations request context)
                           (op-allowlist-violations request)
                           (effect-invariant-violations proposal)
                           (authorization-violations request proposal st)
                           (clearance-tier-violations request proposal st)
                           (capacity-violations request proposal st)
                           (structural-scope-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf  (:confidence proposal 0.0)
        low?  (< conf confidence-floor)
        flag? (= :flag-legal-compliance-concern (:op request))
        hard? (boolean (seq hard))]
    {:ok?        (and (not hard?) (not low?) (not flag?))
     :violations hard
     :confidence conf
     :hard?      hard?
     :escalate?  (and (not hard?) (or low? flag?))
     :flag?      flag?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
