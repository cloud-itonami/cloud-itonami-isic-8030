(ns investigation.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-8299`'s rollout phases: start with zero
  autonomy, widen as trust grows. Where the RoutingGovernor answers 'is
  this allowed?', the phase answers 'how much autonomy does the actor
  have *yet*?'. It can only ever make the actor MORE conservative than
  the governor: it downgrades a governor-clean commit to approval or
  hold, never the reverse.

    Phase 0  no-autonomy         — no writes at all, any op.
    Phase 1  assisted-logging    — `:log-case-record` allowed, every
                                   write needs human approval.
    Phase 2  assisted-coordination — adds `:schedule-investigation-
                                   operation` / `:coordinate-report-
                                   delivery` / `:flag-legal-compliance-
                                   concern` (still approval-only).
    Phase 3  supervised-auto     — governor-clean, high-confidence
                                   `:log-case-record` /
                                   `:schedule-investigation-operation` /
                                   `:coordinate-report-delivery` may
                                   auto-commit.

  `:flag-legal-compliance-concern` is deliberately NEVER a member of any
  phase's `:auto` set, at any phase — a legal/compliance concern always
  reaches a human, independent of the RoutingGovernor's own always-
  escalate check on the same op (defense-in-depth: two independent
  systems both refuse to let this op auto-resolve).

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted
  disposition plus a reason when the phase changed it.")

(def write-ops #{:log-case-record :schedule-investigation-operation
                  :flag-legal-compliance-concern :coordinate-report-delivery})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:flag-legal-compliance-concern` is
  intentionally absent from every phase's `:auto` set."
  {0 {:label "no-autonomy"
      :writes #{}
      :auto #{}}
   1 {:label "assisted-logging"
      :writes #{:log-case-record}
      :auto #{}}
   2 {:label "assisted-coordination"
      :writes #{:log-case-record :schedule-investigation-operation
                :flag-legal-compliance-concern :coordinate-report-delivery}
      :auto #{}}
   3 {:label "supervised-auto"
      :writes #{:log-case-record :schedule-investigation-operation
                :flag-legal-compliance-concern :coordinate-report-delivery}
      :auto #{:log-case-record :schedule-investigation-operation :coordinate-report-delivery}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (investigation.operation: (:phase context phase/default-phase)), AND
  the fallback `gate` itself uses for an unrecognized phase NUMBER. This
  is directly reachable by any ordinary caller that simply omits :phase
  -- not just malformed/malicious input -- so it must be the MOST
  CONSERVATIVE phase whose write posture never auto-commits (the same
  accidental fail-open shape found and fixed in sibling actors in this
  family, e.g. `cloud-itonami-isic-8299`'s `bizsupport.phase` and the
  shared `talent.phase` template — this actor is written correctly from
  the start). `:flag-legal-compliance-concern` remains unaffected either
  way (never in any phase's `:auto` set — a compliance concern always
  reaches a human)."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:flag-legal-compliance-concern` is
    never auto-eligible at any phase, so it always lands here once
    phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a RoutingGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
