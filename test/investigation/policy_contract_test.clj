(ns investigation.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-8299`'s policy_contract_test. The single invariant
  under test:

    CaseCoordinator-LLM never logs/schedules/coordinates a record the
    RoutingGovernor would reject, NEVER renders an investigative
    conclusion or authorizes a surveillance method (permanent hard
    block), and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [investigation.store :as store]
            [investigation.policy :as policy]
            [investigation.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def case-manager   {:actor-id "cm-1" :actor-role :case-manager :phase 3})
(def investigator-r {:actor-id "iv-1" :actor-role :investigator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

;; ───────────────────── end-to-end (actor + governor + phase) ─────────────────────

(deftest authorized-log-case-record-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-case-record :subject "case-100" :case-id "case-100"
                   :record-id "rec-9" :description "X" :custodian "inv-100"}
                  case-manager)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "X" (:description (store/case-record db "rec-9"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "an :investigator role has no scheduling permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-investigation-operation :subject "case-100"
                     :case-id "case-100" :schedule-id "sch-9" :investigator-id "inv-100"
                     :window "w" :hours 2}
                    investigator-r)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/schedule-entry db "sch-9")) "SSoT unchanged")
      (is (some #{:rbac} (-> (store/ledger db) first :basis))))))

(deftest op-outside-closed-allowlist-is-held
  (testing "a request naming an op outside the four-op closed allowlist is rejected, never dispatched"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :finalize-investigation-conclusion :subject "case-100" :case-id "case-100"}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:op-allowlist-gate} (-> (store/ledger db) first :basis))))))

(deftest unmet-clearance-is-held
  (testing "an investigator lacking a case's required qualification → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :schedule-investigation-operation :subject "case-100"
                     :case-id "case-100" :schedule-id "sch-10" :investigator-id "inv-200"
                     :window "w" :hours 2}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:clearance-tier-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/schedule-entry db "sch-10"))))))

(deftest capacity-overcommit-is-held
  (testing "a scheduling that would push an investigator past weekly capacity → HOLD (inv-100 IS qualified for case-100, isolating this from clearance-tier-gate)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-investigation-operation :subject "case-100"
                     :case-id "case-100" :schedule-id "sch-11" :investigator-id "inv-100"
                     :window "w" :hours 15}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= [:capacity-gate] (-> (store/ledger db) first :basis)))
      (is (= 30 (:committed-hours (store/investigator db "inv-100"))) "unchanged"))))

(deftest leaky-log-case-record-with-conclusion-is-held
  (testing "a proposal smuggling a schema-excluded rendered conclusion → HOLD, permanent, non-overridable"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :log-case-record :subject "case-100" :case-id "case-100"
                     :record-id "rec-10" :description "X" :custodian "inv-100" :leaky? true}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:structural-scope-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/case-record db "rec-10"))))))

(deftest greedy-report-delivery-with-content-is-held
  (testing "a report-delivery coordination smuggling the report's own content → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
                     :delivery-id "del-9" :recipient-channel "c" :scheduled-at "t" :greedy? true}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:structural-scope-gate} (-> (store/ledger db) first :basis))))))

(deftest scheduling-on-unverified-case-is-held
  (testing "case-300's authorization has never been verified → scheduling on it is HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-investigation-operation :subject "case-300"
                     :case-id "case-300" :schedule-id "sch-12" :investigator-id "inv-300"
                     :window "w" :hours 2}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:authorization-gate} (-> (store/ledger db) first :basis))))))

(deftest logging-on-unverified-case-is-also-held
  (testing "authorization-gate applies to :log-case-record too, not only scheduling"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-case-record :subject "case-300" :case-id "case-300"
                     :record-id "rec-11" :description "x" :custodian "inv-300"}
                    case-manager)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:authorization-gate} (-> (store/ledger db) first :basis))))))

(deftest flag-concern-on-unverified-case-still-escalates-not-hold
  (testing "flag-legal-compliance-concern is EXEMPT from the authorization-gate -- a concern about a case's own missing authorization must remain raisable, not structurally suppressed by the very problem it's flagging"
    (let [[_db actor] (fresh)
          res (exec-op actor "t8c"
                    {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
                     :flag-id "flg-9" :concern-summary "authorization never verified"}
                    case-manager)]
      (is (= :interrupted (:status res)) "escalates to a human, is not hard-held")
      (is (= :compliance-concern (-> res :state :audit last :reason))))))

(deftest flag-concern-always-escalates-then-human-decides
  (testing "an otherwise-clean concern flag interrupts for human review, at any confidence, any phase"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t9"
                   {:op :flag-legal-compliance-concern :subject "case-100" :case-id "case-100"
                    :flag-id "flg-10" :concern-summary "possible conflict of interest"}
                   case-manager)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :compliance-concern (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "cm-1"}}
                         {:thread-id "t9" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :logged (:status (store/compliance-flag db "flg-10"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t10"
                  {:op :flag-legal-compliance-concern :subject "case-100" :case-id "case-100"
                   :flag-id "flg-11" :concern-summary "x"}
                  case-manager)
          r2 (g/run* actor {:approval {:status :rejected :by "cm-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/compliance-flag db "flg-11"))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-case-record :subject "case-100" :case-id "case-100"
                          :record-id "rec-a" :description "X" :custodian "inv-100"}
               case-manager)
      (exec-op actor "b" {:op :schedule-investigation-operation :subject "case-100"
                          :case-id "case-100" :schedule-id "sch-a" :investigator-id "inv-200"
                          :window "w" :hours 2}
               case-manager)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ───────────────────── direct policy/check unit tests ─────────────────────
;; These exercise checks a legitimately-behaving mock advisor can never
;; trigger by construction (it never emits :effect other than :propose,
;; and it never names an op outside the allowlist for a well-formed
;; request) -- hand-crafted proposals prove the governor's own defense-
;; in-depth still holds even against a compromised/misbehaving advisor
;; (e.g. a real LLM in production).

(deftest effect-invariant-gate-rejects-any-non-propose-effect
  (testing "a proposal that isn't literally :effect :propose is a hard, unconditional rejection"
    (let [st (store/seed-db)
          request {:op :log-case-record :subject "case-100"}
          context case-manager
          malicious-proposal {:summary "s" :rationale "r" :cites [] :confidence 0.95
                              :effect :execute-directly
                              :value {:id "rec-x" :case-id "case-100"}}
          verdict (policy/check request context malicious-proposal st)]
      (is (:hard? verdict))
      (is (some #{:effect-invariant-gate} (map :rule (:violations verdict)))))))

(deftest op-allowlist-gate-rejects-a-spoofed-op-even-with-a-permitted-role
  (let [st (store/seed-db)
        request {:op :authorize-surveillance-method :subject "case-100"}
        context case-manager
        proposal {:summary "s" :rationale "r" :cites [] :confidence 0.95 :effect :propose :value {}}
        verdict (policy/check request context proposal st)]
    (is (:hard? verdict))
    (is (some #{:op-allowlist-gate} (map :rule (:violations verdict))))))

(deftest confidence-floor-escalates-a-structurally-clean-proposal
  (testing "low confidence alone (no hard violation) → escalate, not commit, not hold"
    (let [st (store/seed-db)
          request {:op :log-case-record :subject "case-100"}
          context case-manager
          low-confidence-proposal {:summary "s" :rationale "r" :cites [:case-id] :confidence 0.2
                                   :effect :propose :value {:id "rec-low" :case-id "case-100"
                                                            :description "x" :custodian "inv-100"}}
          verdict (policy/check request context low-confidence-proposal st)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict))
      (is (not (:ok? verdict))))))
