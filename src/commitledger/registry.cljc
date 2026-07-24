(ns commitledger.registry
  "Pure-function commitment-record/tranche-release construction, and
  every re-derivable ground-truth calculation the
  CommitmentLedgerGovernor needs -- an append-only commitment
  book-of-record draft, the lending analog of `credit.registry`
  (`cloud-itonami-isic-6492`) and `factoring.registry` (`cloud-itonami-
  isic-6493`).

  ## `rate-ceiling-for-principal` -- 利息制限法 tiers, applied universally (honest scoping note)

  `rate-ceiling-for-principal` mirrors Japan's 利息制限法 (Interest Rate
  Restriction Act) Article 1 three-tier ceiling EXACTLY (principal <
  ¥100,000 -> 20%; ¥100,000 <= principal < ¥1,000,000 -> 18%; principal
  >= ¥1,000,000 -> 15%) -- a REAL statute, not an invented number. This
  R0 applies these JPN tiers as the actor's UNIVERSAL institutional-
  lender rate ceiling regardless of the application's own `:jurisdiction`
  field, an honest, deliberate V1 simplification (this actor's own
  primary reference jurisdiction is Japan) rather than a claim that
  every jurisdiction's usury law matches these exact numbers -- the same
  honest-simplification discipline `credit.registry/affordability-
  ceiling`'s docstring uses for its own single-jurisdiction reference
  ceiling applied fleet-wide. A future revision could key this off
  `:jurisdiction` the same way `commitledger.facts/catalog` is already
  jurisdiction-keyed; V1 does not, to keep the R0 scope honestly small.

  ## `capacity-ratio-ceiling` -- reused, not shared code

  `capacity-ratio-ceiling` (0.43) and `compute-capacity-ratio`'s formula
  are a DELIBERATE, LOCAL re-implementation of `credit.registry`'s own
  `affordability-ceiling`/`compute-debt-to-income-ratio` (`cloud-
  itonami-isic-6492`) -- same well-known regulatory reference point
  (the U.S. Ability-to-Repay/Qualified Mortgage rule's 43% back-end
  debt-to-income ratio, Regulation Z, 12 CFR §1026.43), re-derived here
  rather than required from the sibling repo, per this fleet's 'shared
  data contract, no shared code' convention across repo boundaries
  (matching the VC-fund trio's `funding.cljc` concept-reuse posture,
  `cloud-itonami-isic-6499`/`gftdcojp/cloud-itonami`).

  ## `equity-blocklist` -- reused, not shared code

  `equity-blocklist` mirrors `cloud_itonami.funding/equity-blocklist`
  (really `cloud_itonami.kernels.substance/equity-blocklist`,
  `orgs/gftdcojp/cloud-itonami`) TOKEN-FOR-TOKEN, again a local
  re-implementation, not an import -- this actor's V1 scope is
  LOAN-based support only (per the owner's explicit instruction), so any
  free-text field that smuggles in an equity/dividend/cap-table claim is
  a HARD violation (`commitledger.governor/equity-language-detected-
  violations`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed lender's act, not this actor's. This actor also never moves
  money itself (see README `Actuation`); a committed record here is
  always a DRAFT of a loan-based commitment/tranche-release
  authorization, delegated to `cloud-itonami-isic-6492`/`cloud-itonami-
  isic-6419`/external disbursement rails."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- rate ceiling (institutional lenders) -----------------------------

(def rate-ceiling-tier-1-max
  "利息制限法 第一条 first tier upper bound: principal strictly below this
  is capped at `rate-ceiling-tier-1-pct`."
  100000)

(def rate-ceiling-tier-2-max
  "利息制限法 第一条 second tier upper bound: principal strictly below
  this (and at/above `rate-ceiling-tier-1-max`) is capped at
  `rate-ceiling-tier-2-pct`."
  1000000)

(def rate-ceiling-tier-1-pct 0.20)
(def rate-ceiling-tier-2-pct 0.18)
(def rate-ceiling-tier-3-pct 0.15)

(defn rate-ceiling-for-principal
  "利息制限法 第一条's three-tier institutional-lender rate ceiling,
  keyed off principal alone (see ns docstring for the honest 'applied
  universally, not per-jurisdiction' V1 scoping note)."
  [principal]
  (cond
    (< principal rate-ceiling-tier-1-max) rate-ceiling-tier-1-pct
    (< principal rate-ceiling-tier-2-max) rate-ceiling-tier-2-pct
    :else                                 rate-ceiling-tier-3-pct))

;; ----------------------------- individual-lender safeguards -----------------------------

(def individual-lender-principal-cap
  "An individual (non-institutional) lender's principal may never exceed
  this -- grounded in the SAME 利息制限法 first-tier threshold
  (`rate-ceiling-tier-1-max`), reused here as a conservative individual-
  lender exposure cap, not a separate invented number."
  rate-ceiling-tier-1-max)

(def individual-lender-loan-count-threshold
  "If an individual lender's did:key already backs this many (or more)
  PRIOR COMMITTED records, an additional commitment is refused. A
  CONSERVATIVE PROXY for 「業として」(acting 'as a business') repeated-
  lending risk under 貸金業法's licensing requirement -- NOT a legal
  bright line, and not a claim that exactly 3 loans is where Japanese
  law draws that boundary. See `commitledger.governor/individual-
  lender-loan-count-exceeds-threshold-violations`'s own docstring."
  3)

;; ----------------------------- capacity ratio -----------------------------

(def capacity-ratio-ceiling
  "A REAL, well-known back-end debt-to-income/capacity ratio ceiling
  (0.43), mirroring the U.S. Ability-to-Repay/Qualified Mortgage rule's
  general qualifying threshold (Regulation Z, 12 CFR §1026.43) -- an
  honest starting reference point, re-derived locally (see ns docstring:
  reused, not shared code, from `credit.registry/affordability-
  ceiling`)."
  0.43)

(defn compute-capacity-ratio
  "Pure computation of an application's back-end capacity ratio:
  (existing-debt + requested-principal) / repayment-capacity, where
  repayment-capacity is `:annual-income` when present and positive, else
  `:declared-repayment-capacity` (the 'or declared repayment capacity'
  fallback the spec calls for)."
  [{:keys [existing-debt requested-principal annual-income declared-repayment-capacity]}]
  (let [capacity (if (and annual-income (pos? annual-income))
                   annual-income
                   declared-repayment-capacity)]
    (when-not (and capacity (pos? capacity))
      (throw (ex-info "compute-capacity-ratio: annual-income or declared-repayment-capacity must be > 0" {})))
    (when (neg? existing-debt)
      (throw (ex-info "compute-capacity-ratio: existing-debt must be >= 0" {})))
    (when (neg? requested-principal)
      (throw (ex-info "compute-capacity-ratio: requested-principal must be >= 0" {})))
    (/ (+ (double existing-debt) (double requested-principal)) (double capacity))))

;; ----------------------------- equity-language blocklist -----------------------------

(def equity-blocklist
  "Substrings (lowercased) that indicate an attempt to attach an
  ownership/profit-participation right to a LOAN-based commitment --
  this actor's V1 scope is lending-only (owner instruction), never
  equity/investment. Token-for-token mirror of `cloud_itonami.funding/
  equity-blocklist` (`cloud_itonami.kernels.substance/equity-
  blocklist`, `orgs/gftdcojp/cloud-itonami`) -- re-implemented locally,
  not required across the repo boundary. See ns docstring."
  #{"equity" "dividend" "shares" "share of profit" "profit share"
    "profit-share" "ownership stake" "cap table" "cap-table"
    "投資持分" "持分" "配当" "株式" "出資比率" "議決権" "利益分配"})

(defn equity-language?
  "Does `text` (a free-text memo/purpose field) contain any
  `equity-blocklist` substring? Case-insensitive; blank/nil text never
  matches."
  [text]
  (boolean
   (when (and text (not (str/blank? text)))
     (let [lower (str/lower-case text)]
       (some #(str/includes? lower %) equity-blocklist)))))

;; ----------------------------- personal pledge -----------------------------

(def personal-pledge-required-fields
  "The non-financial human-commitment fields this actor's OWN
  distinctive check (`commitledger.governor/personal-pledge-incomplete-
  violations`) requires be present and non-blank on every application's
  `:personal-pledge` map."
  #{:milestone-report-cadence :mentor-checkin-commitment :progress-report-obligation})

(defn- blank-value? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn personal-pledge-complete?
  "Every `personal-pledge-required-fields` key present on `pledge` with a
  non-blank value."
  [pledge]
  (boolean
   (and (map? pledge)
        (every? #(not (blank-value? (get pledge %))) personal-pledge-required-fields))))

;; ----------------------------- borrower self-registration ref -----------------------------

(defn borrower-ref-well-formed?
  "V1 scope: does NOT call out to `gftdcojp/cloud-itonami`'s live
  registry (no HTTP integration in this build, by design -- see README
  `What this actor does NOT do`). Only verifies the two reference fields
  a self-registered borrower (ADR-0013, `gftdcojp/cloud-itonami`) would
  carry are present and well-formed: a non-blank `org/repo` string
  (containing exactly one `/`) and a non-blank `did:key` string."
  [org-repo did]
  (boolean
   (and org-repo (string? org-repo) (not (str/blank? org-repo))
        (= 2 (count (str/split org-repo #"/" -1)))
        (every? #(not (str/blank? %)) (str/split org-repo #"/" -1))
        did (string? did) (not (str/blank? did))
        (str/starts-with? did "did:"))))

;; ----------------------------- commitment-record draft -----------------------------

(defn register-commitment
  "Validate + construct the COMMITMENT-RECORD registration DRAFT -- the
  matched loan commitment (terms + personal-pledge), NOT a real
  disbursement. Pure function -- does not touch any real loan-servicing/
  banking system; it builds the RECORD this actor keeps. This actor does
  NOT move money itself -- actual disbursement is delegated to
  `cloud-itonami-isic-6492`/`cloud-itonami-isic-6419`/external rails
  (see README `Actuation`)."
  [application-id lender-id principal jurisdiction sequence]
  (when-not (and application-id (not= application-id ""))
    (throw (ex-info "register-commitment: application_id required" {})))
  (when-not (and lender-id (not= lender-id ""))
    (throw (ex-info "register-commitment: lender_id required" {})))
  (when (neg? principal)
    (throw (ex-info "register-commitment: principal must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "register-commitment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "register-commitment: sequence must be >= 0" {})))
  (let [commitment-number (str (str/upper-case jurisdiction) "-COMMIT-" (zero-pad sequence 6))
        record {"record_id" commitment-number
                "kind" "commitment-record-draft"
                "application_id" application-id
                "lender_id" lender-id
                "principal" principal
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "commitment_number" commitment-number
     "certificate" (unsigned-certificate "CommitmentRecordCertificate" commitment-number commitment-number)}))

(defn register-tranche-release
  "Validate + construct the TRANCHE-RELEASE DRAFT -- authorizes releasing
  the next tranche of an already-committed commitment, NOT a real fund
  transfer (see ns docstring / README `Actuation`)."
  [application-id tranche-index amount jurisdiction sequence]
  (when-not (and application-id (not= application-id ""))
    (throw (ex-info "register-tranche-release: application_id required" {})))
  (when (neg? tranche-index)
    (throw (ex-info "register-tranche-release: tranche-index must be >= 0" {})))
  (when (neg? amount)
    (throw (ex-info "register-tranche-release: amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "register-tranche-release: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "register-tranche-release: sequence must be >= 0" {})))
  (let [release-number (str (str/upper-case jurisdiction) "-TRANCHE-" (zero-pad sequence 6))
        record {"record_id" release-number
                "kind" "tranche-release-draft"
                "application_id" application-id
                "tranche_index" tranche-index
                "amount" amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "release_number" release-number
     "certificate" (unsigned-certificate "TrancheReleaseCertificate" release-number release-number)}))

(defn append
  "Append a draft record, returning a NEW list (never mutate history in
  place)."
  [history result]
  (conj (vec history) (get result "record")))
