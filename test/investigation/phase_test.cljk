(ns investigation.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is
  unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [investigation.store :as store]
            [investigation.operation :as op]))

(def case-manager {:actor-id "cm-1" :actor-role :case-manager})

(def clean-log
  {:op :log-case-record :subject "case-100" :case-id "case-100"
   :record-id "rec-400" :description "field notes (demo)" :custodian "inv-100"})

(def clean-schedule
  {:op :schedule-investigation-operation :subject "case-100" :case-id "case-100"
   :schedule-id "sch-400" :investigator-id "inv-100" :window "w" :hours 2})

(def concern-flag
  {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
   :flag-id "flg-400" :concern-summary "x"})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-log case-manager)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/case-record s "rec-400")) "SSoT untouched in phase 0")))

(deftest phase1-forces-approval-on-clean-log
  (testing "a clean log-case-record that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-log case-manager)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase1-holds-schedule-not-yet-enabled
  (let [[s res] (run 1 clean-schedule case-manager)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))))

(deftest phase2-enables-schedule-under-approval
  (let [[_ res] (run 2 clean-schedule case-manager)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-log
  (let [[s res] (run 3 clean-log case-manager)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "field notes (demo)" (:description (store/case-record s "rec-400"))))))

(deftest phase3-auto-commits-clean-schedule
  (let [[s res] (run 3 clean-schedule case-manager)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :proposed (:status (store/schedule-entry s "sch-400"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (missing authorization) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :schedule-investigation-operation :subject "case-300"
                          :case-id "case-300" :schedule-id "sch-401"
                          :investigator-id "inv-300" :window "w" :hours 2}
                       case-manager)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest missing-phase-context-does-not-grant-max-autonomy
  (testing "omitting :phase entirely must fall back to the conservative default (1), not phase 3"
    (let [s (store/seed-db)
          actor (op/build s)
          res (g/run* actor {:request clean-log :context case-manager} {:thread-id "no-phase"})]
      (is (= :interrupted (:status res)) "phase 1 forces approval, unlike phase 3's auto-commit"))))

(deftest flag-concern-never-auto-commits-at-any-phase
  (testing "a legal/compliance concern never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph concern-flag case-manager)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a compliance-concern flag"))))))
