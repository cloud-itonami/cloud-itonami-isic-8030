(ns investigation.facts
  "R0 investigator qualification catalog — the ONLY licensing/certification
  classes the RoutingGovernor will accept as evidence of an investigator's
  clearance to be scheduled onto a case requiring that qualification
  (mirrors `cloud-itonami-isic-8299`'s `bizsupport.facts` / `cloud-itonami-
  isic-8291`'s `dossier.facts` discipline: honesty over coverage). Every
  entry is a real, citable, well-known private-investigation-adjacent
  licensing/certification category — never a fabricated one.

  Deliberately excluded, by design, on every axis of this catalog: any
  class that would function as an authorization to perform a specific
  COVERT SURVEILLANCE METHOD (e.g. wiretap, GPS tracking, hidden-camera
  placement). This actor never authorizes a surveillance method itself
  (see `investigation.policy`'s scope-exclusion-gate) — a qualification
  catalog is evidence an investigator is legally entitled to WORK the
  case, never itself a grant of a specific investigative technique. An
  investigator's authority to use any particular surveillance method is
  established outside this system entirely (state law + a licensed
  attorney/agency's own case-specific legal-basis determination), not by
  anything this catalog could ever certify.")

(def catalog
  "Each entry: {:id :name :domain :issuing-body}. `:id` is the value that
  must appear in an investigator's `:qualifications` set and a case's
  `:required-qualifications` set for the clearance-tier-gate to treat it
  as real."
  [{:id :state-pi-license
    :name "State-issued Private Investigator License"
    :domain :licensure
    :issuing-body "U.S. state licensing board (jurisdiction-specific)"}
   {:id :process-server-cert
    :name "Certified Process Server credential"
    :domain :legal-service
    :issuing-body "State/county court or professional process-server association"}
   {:id :background-check-fcra-cert
    :name "FCRA (Fair Credit Reporting Act) compliant background-screening certification"
    :domain :background-screening
    :issuing-body "Professional Background Screening Association (PBSA)"}
   {:id :records-research-cert
    :name "Licensed public-records research credential"
    :domain :records-research
    :issuing-body "State/county records-access licensing authority"}
   {:id :insurance-siu-cert
    :name "Insurance Special Investigations Unit (SIU) certification"
    :domain :insurance-fraud
    :issuing-body "International Association of Special Investigation Units (IASIU)"}])

(def allowed-qualification-classes
  "The closed set of `:id` values the clearance-tier-gate will accept
  anywhere — on an investigator's `:qualifications` or a case's `:required-
  qualifications`. A class not in `catalog` (e.g. :self-declared,
  :trust-me, or any purported surveillance-method authorization) must be
  rejected, not silently accepted because it looks like a keyword."
  (into #{} (map :id catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全カテゴリ対応' in prose, 5 real named categories in fact)."
  []
  {:qualification-count (count catalog)
   :domains (into (sorted-set) (map :domain catalog))
   :note "R0 scope: 5 real, named investigator licensing/certification categories, none of which authorizes any specific surveillance method. Extend only by appending a real, citable category -- never fabricate one, and never add a surveillance-method authorization class."})

(defn class-allowed? [qual-class]
  (contains? allowed-qualification-classes qual-class))
