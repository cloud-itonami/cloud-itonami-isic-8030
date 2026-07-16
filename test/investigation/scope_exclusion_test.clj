(ns investigation.scope-exclusion-test
  "Dedicated regression test for a known self-tripping bug class
  independently rediscovered across multiple sibling actors in this
  fleet: a governor's scope-exclusion term list phrased as a BARE NOUN
  (e.g. 'conclusion', 'surveillance') accidentally matches inside the
  mock advisor's own DEFAULT rationale/disclaimer text for a legitimate,
  allowed proposal, causing the actor to self-block on its own happy
  path.

  `investigation.policy/scope-exclusion-phrases` is deliberately phrased
  as the finalization/execution ACTION ('finalize the investigation
  conclusion', 'authorize covert surveillance'), never a bare noun. This
  test proves that discipline holds by running EVERY default (non-leaky/
  non-greedy) mock-advisor proposal for every op in the closed allowlist
  through `policy/scope-exclusion-violations` directly and asserting
  zero hits -- including proposals whose legitimate rationale text
  naturally mentions 'surveillance' as a routine field-work TYPE
  descriptor, which is exactly the situation a bare-noun exclusion list
  would have wrongly self-tripped on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [investigation.llm :as llm]
            [investigation.policy :as policy]))

(def representative-requests
  "One request per op in the closed allowlist, using realistic field
  values -- including a schedule request whose :window explicitly names
  'surveillance' as the field-work type, deliberately probing the exact
  bare-noun false-positive shape this test guards against."
  [{:op :log-case-record :subject "case-100" :case-id "case-100"
    :record-id "rec-1" :description "logged photographic evidence of vehicle damage"
    :custodian "inv-100"}
   {:op :schedule-investigation-operation :subject "case-100" :case-id "case-100"
    :schedule-id "sch-1" :investigator-id "inv-100"
    :window "surveillance operation, 2026-07-20 09:00-13:00 (routine field-work observation)"
    :hours 4}
   {:op :flag-legal-compliance-concern :subject "case-300" :case-id "case-300"
    :flag-id "flg-1"
    :concern-summary "case authorization pending independent verification before the surveillance operation's scheduled conclusion date"}
   {:op :coordinate-report-delivery :subject "case-100" :case-id "case-100"
    :delivery-id "del-1" :recipient-channel "client-portal" :scheduled-at "2026-07-25T10:00:00Z"}])

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (doseq [req representative-requests]
    (testing (str (:op req) " default proposal")
      (let [proposal (llm/infer req)]
        (is (= [] (or (policy/scope-exclusion-violations proposal) []))
            (str "unexpected scope-exclusion hit on a legitimate proposal: "
                 (pr-str (select-keys proposal [:summary :rationale]))))))))

(deftest bare-noun-terms-alone-would-have-false-positived-but-action-phrases-do-not
  (testing "sanity-check the phrase list itself: none of its entries is a bare noun that a routine field-work mention of 'surveillance' or a routine project-management mention of 'conclusion' would trip"
    (doseq [phrase policy/scope-exclusion-phrases]
      (is (>= (count (str/split phrase #"\s+")) 3)
          (str "scope-exclusion phrase is suspiciously short / noun-like, not action-phrased: " (pr-str phrase))))))

(deftest scope-exclusion-still-catches-the-real-violation
  (testing "the gate is not a no-op -- an actual finalization/authorization action phrase in proposal text IS caught"
    (is (seq (policy/scope-exclusion-violations
              {:summary "case update" :rationale "recommend we finalize the investigation conclusion today"})))
    (is (seq (policy/scope-exclusion-violations
              {:summary "field-work plan" :rationale "we should authorize covert surveillance of the subject"})))))
