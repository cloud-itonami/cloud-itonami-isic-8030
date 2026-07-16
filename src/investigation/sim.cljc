(ns investigation.sim
  "Demo runner: push eight representative operations through one
  OperationActor and watch the RoutingGovernor + approval workflow earn
  the CaseCoordinator-LLM the right to log, schedule, flag or coordinate.

    op1  evidence log entry (no conclusion;正当)              → commit
    op2  qualified investigator scheduling (正当)              → commit
    op3  investigator lacking required qualification            → clearance-tier-gate REJECT → hold
    op4  investigator exceeding weekly capacity                → capacity-gate REJECT → hold
    op5  evidence log entry smuggling a rendered conclusion     → structural-scope-gate REJECT → hold
    op6  report-delivery coordination smuggling report content  → structural-scope-gate REJECT → hold
    op7  scheduling on a case with unverified authorization     → authorization-gate REJECT → hold
    op8  legal/compliance concern flag (always human review, any phase) → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [investigation.store :as store]
            [investigation.operation :as op]
            [investigation.facts :as facts]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a case manager 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   PAUSED for human review (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "cm-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   " (if approve? "approved -> " "rejected -> ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   -> disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        ;; :phase 3 (supervised-auto) explicitly -- default-phase is 1
        ;; (assisted, no auto-commit) so this demo can showcase the full
        ;; governed contract end to end.
        case-manager {:actor-id "cm-1" :actor-role :case-manager :phase 3}]

    (line "-- R0 investigator qualification coverage (honest current state) --")
    (line (pr-str (facts/coverage)))

    (line "\n-- OperationActor (CaseCoordinator-LLM sealed; RoutingGovernor active) --")

    (line "\nop1  evidence log entry (no conclusion; legitimate)")
    (run-op! actor "op1"
             {:op :log-case-record :subject "case-100" :case-id "case-100"
              :record-id "rec-1" :description "photographed damaged vehicle per client claim (demo)"
              :custodian "inv-100"}
             case-manager true)

    (line "\nop2  qualified investigator scheduling (legitimate)")
    (run-op! actor "op2"
             {:op :schedule-investigation-operation :subject "case-100"
              :case-id "case-100" :schedule-id "sch-1" :investigator-id "inv-100"
              :window "2026-07-20 09:00-13:00" :hours 4}
             case-manager true)

    (line "\nop3  investigator lacking case's required qualification (SIU cert)")
    (run-op! actor "op3"
             {:op :schedule-investigation-operation :subject "case-100"
              :case-id "case-100" :schedule-id "sch-2" :investigator-id "inv-200"
              :window "2026-07-21 09:00-13:00" :hours 4}
             case-manager true)

    (line "\nop4  investigator exceeding weekly capacity (needs 4h, has 2h headroom)")
    (run-op! actor "op4"
             {:op :schedule-investigation-operation :subject "case-100"
              :case-id "case-100" :schedule-id "sch-3" :investigator-id "inv-200"
              :window "2026-07-22 09:00-13:00" :hours 4}
             case-manager true)

    (line "\nop5  CaseCoordinator-LLM smuggles a rendered conclusion into an evidence entry (schema-excluded)")
    (run-op! actor "op5"
             {:op :log-case-record :subject "case-100" :case-id "case-100"
              :record-id "rec-2" :description "final assessment (demo)" :custodian "inv-100"
              :leaky? true}
             case-manager true)

    (line "\nop6  report-delivery coordination smuggles the report's own content (schema-excluded)")
    (run-op! actor "op6"
             {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
              :delivery-id "del-1" :recipient-channel "client-portal"
              :scheduled-at "2026-07-25T10:00:00Z" :greedy? true}
             case-manager true)

    (line "\nop7  scheduling on a case whose client-authorization is not yet verified")
    (run-op! actor "op7"
             {:op :schedule-investigation-operation :subject "case-300"
              :case-id "case-300" :schedule-id "sch-4" :investigator-id "inv-300"
              :window "2026-07-23 09:00-13:00" :hours 3}
             case-manager true)

    (line "\nop8  legal/compliance concern flag (always escalates, any phase)")
    (run-op! actor "op8"
             {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
              :flag-id "flg-1"
              :concern-summary "client-authorization for case-300 has never been verified -- please confirm engagement is properly documented"}
             case-manager true)

    (line "\n-- audit ledger (append-only; who logged/scheduled/flagged/coordinated what, on what basis) --")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
