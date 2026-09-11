(ns investigation.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a
  rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [investigation.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Insurance fraud claim review (demo)" (:matter-type (store/case-file s "case-100"))))
      (is (= #{:insurance-siu-cert} (:required-qualifications (store/case-file s "case-100"))))
      (is (true? (:authorization-verified? (store/case-file s "case-100"))))
      (is (false? (:authorization-verified? (store/case-file s "case-300"))))
      (is (= "Investigator A (demo)" (:name (store/investigator s "inv-100"))))
      (is (= #{:state-pi-license :insurance-siu-cert} (:qualifications (store/investigator s "inv-100"))))
      (is (= 30 (:committed-hours (store/investigator s "inv-100"))))
      (is (= 3 (count (store/all-case-files s))))
      (is (= 3 (count (store/all-investigators s))))
      (is (nil? (store/case-record s "rec-nope"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "log-case-record commits an evidence entry"
        (store/commit-record! s :log-case-record
                               {:id "rec-1" :case-id "case-100" :description "X" :custodian "inv-100"})
        (is (= "X" (:description (store/case-record s "rec-1"))))
        (is (= :proposed (:status (store/case-record s "rec-1")))))
      (testing "schedule-investigation-operation commits and bumps investigator committed-hours"
        (store/commit-record! s :schedule-investigation-operation
                               {:id "sch-1" :case-id "case-100" :investigator-id "inv-100"
                                :window "w" :hours 5})
        (is (= :proposed (:status (store/schedule-entry s "sch-1"))))
        (is (= 35 (:committed-hours (store/investigator s "inv-100"))) "30 + 5h"))
      (testing "flag-legal-compliance-concern commits with :status :logged"
        (store/commit-record! s :flag-legal-compliance-concern
                               {:id "flg-1" :case-id "case-300" :concern-summary "x"})
        (is (= :logged (:status (store/compliance-flag s "flg-1")))))
      (testing "coordinate-report-delivery commits a delivery coordination entry"
        (store/commit-record! s :coordinate-report-delivery
                               {:id "del-1" :case-id "case-100" :recipient-channel "c" :scheduled-at "t"})
        (is (= :proposed (:status (store/report-delivery s "del-1")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest case-records-of-and-schedule-entries-of-investigator
  (doseq [[label s] (backends)]
    (testing label
      (store/commit-record! s :log-case-record
                             {:id "rec-a" :case-id "case-100" :description "a" :custodian "inv-100"})
      (store/commit-record! s :log-case-record
                             {:id "rec-b" :case-id "case-100" :description "b" :custodian "inv-100"})
      (store/commit-record! s :log-case-record
                             {:id "rec-c" :case-id "case-200" :description "c" :custodian "inv-200"})
      (is (= #{"rec-a" "rec-b"} (into #{} (map :id) (store/case-records-of s "case-100"))))
      (store/commit-record! s :schedule-investigation-operation
                             {:id "sch-a" :case-id "case-100" :investigator-id "inv-100" :window "w" :hours 1})
      (is (= #{"sch-a"} (into #{} (map :id) (store/schedule-entries-of-investigator s "inv-100")))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/case-file s "nope")))
    (is (= [] (store/all-case-files s)))
    (is (= [] (store/ledger s)))
    (store/with-case-files s {"x" {:id "x" :matter-type "X"}})
    (is (= "X" (:matter-type (store/case-file s "x"))))))
