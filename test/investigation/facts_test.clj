(ns investigation.facts-test
  "The R0 investigator qualification catalog is the whole ground truth for
  the clearance-tier gate — these tests guard its own internal honesty
  (every class it advertises is actually backed by a catalog entry, no
  duplicate/aspirational entries) AND that it never grows a surveillance-
  method-authorization class by accident."
  (:require [clojure.test :refer [deftest is testing]]
            [investigation.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name domain issuing-body]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? domain))
      (is (string? issuing-body)))))

(deftest allowed-qualification-classes-matches-catalog
  (is (= (into #{} (map :id facts/catalog)) facts/allowed-qualification-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :state-pi-license))
  (is (facts/class-allowed? :insurance-siu-cert))
  (is (not (facts/class-allowed? :self-declared)))
  (is (not (facts/class-allowed? :trust-me)))
  (is (not (facts/class-allowed? nil))))

(deftest catalog-never-authorizes-a-surveillance-method
  (testing "no catalog entry's id or name could be mistaken for a surveillance-method authorization"
    (doseq [{:keys [id name]} facts/catalog]
      (is (not (re-find #"(?i)surveillance|wiretap|covert" (str id))))
      (is (not (re-find #"(?i)surveillance|wiretap|covert" name))))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:qualification-count c)))
    (is (<= (:qualification-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:domains c) :licensure))))
