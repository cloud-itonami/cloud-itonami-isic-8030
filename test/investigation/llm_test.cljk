(ns investigation.llm-test
  "CaseCoordinator-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [investigation.llm :as llm]))

(deftest log-case-record-proposal-carries-structured-fields
  (let [p (llm/infer {:op :log-case-record :subject "case-100" :case-id "case-100"
                      :record-id "rec-9" :description "X" :custodian "inv-100"})]
    (is (= :propose (:effect p)))
    (is (= "rec-9" (get-in p [:value :id])))
    (is (>= (:confidence p) 0.9))))

(deftest leaky-log-case-record-proposal-contains-the-excluded-field
  (testing "the LLM layer does not filter -- that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [p (llm/infer {:op :log-case-record :subject "case-100" :case-id "case-100"
                        :record-id "rec-9" :description "X" :custodian "inv-100" :leaky? true})]
      (is (contains? (:value p) :conclusion)))))

(deftest schedule-proposal-carries-case-and-investigator
  (let [p (llm/infer {:op :schedule-investigation-operation :subject "case-100"
                      :case-id "case-100" :schedule-id "sch-9" :investigator-id "inv-200"
                      :window "w" :hours 4})]
    (is (= :propose (:effect p)))
    (is (= "inv-200" (get-in p [:value :investigator-id])))
    (is (>= (:confidence p) 0.9) "high confidence even for a pairing the governor will reject -- proves clearance/capacity gates cannot rely on confidence")))

(deftest report-delivery-proposal-greedy-adds-report-content
  (let [clean (llm/infer {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
                          :delivery-id "del-9" :recipient-channel "c" :scheduled-at "t"})
        greedy (llm/infer {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
                           :delivery-id "del-9" :recipient-channel "c" :scheduled-at "t" :greedy? true})]
    (is (not (contains? (:value clean) :report-content)))
    (is (contains? (:value greedy) :report-content))))

(deftest flag-concern-proposal-never-marks-high-confidence
  (let [p (llm/infer {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
                      :flag-id "flg-9" :concern-summary "x"})]
    (is (= :propose (:effect p)))
    (is (< (:confidence p) 0.9) "a raised concern is a claim pending human review, never auto-confident")))

(deftest unsupported-op-yields-noop-never-propose
  (testing "a request naming an op outside the closed allowlist never gets an :effect :propose -- this is what lets policy/effect-invariant-gate hard-reject it structurally"
    (let [p (llm/infer {:op :finalize-investigation-conclusion :subject "case-100"})]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p))))))
