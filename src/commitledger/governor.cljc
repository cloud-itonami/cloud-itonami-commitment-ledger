(ns commitledger.governor
  "CommitmentLedgerGovernor -- the independent compliance layer that
  earns the Commitment-LLM the right to commit. The LLM has no notion
  of which jurisdiction's spec-basis is official, whether an
  institutional lender's license is actually verified, whether a
  proposed rate exceeds 利息制限法's tiered ceiling, whether an
  individual lender is quietly acting 「業として」(as a business) across
  many small loans, whether an application's own capacity ratio exceeds
  a responsible-lending ceiling, whether a 'loan' proposal is secretly
  an equity pitch, whether a personal pledge is actually complete, or
  whether a borrower is actually self-registered -- so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the lending-matchmaking analog of `credit.governor` (`cloud-itonami-
  isic-6492`) and `factoring.governor` (`cloud-itonami-isic-6493`).

  ELEVEN HARD checks gate `:commitment/record` (the first actuation --
  matching + committing a loan-based commitment: the original ten plus
  V2's `borrower-registration-not-verified-violations`, check 13, added
  additively -- see `docs/adr/0002-http-edge-live-registry-
  verification.md`), plus TWO more HARD checks gate `:commitment/
  tranche-release` (the second, independent actuation -- releasing the
  next tranche against an already-committed commitment, mirroring
  `cloud-itonami-isic-6493`'s two-actuation `:advance/fund`/`:reserve/
  settle` shape and its own `receivable-status-precondition-violations`/
  `double-advance-violations`/`double-settle-violations` pattern). ALL
  HARD violations: a human approver CANNOT override them. The
  confidence/actuation gate is SOFT: it asks a human to look -- but see
  `commitledger.phase`: for `:stake :actuation/commitment-record` and
  `:stake :actuation/tranche-release` NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a human
  call. This actor does NOT move money itself -- see README `Actuation`:
  disbursement is delegated to `cloud-itonami-isic-6492`/`cloud-itonami-
  isic-6419`/external rails.

  ## The eleven `:commitment/record` HARD checks

    1. `spec-basis-missing-violations`                       -- jurisdiction spec-basis citation absent.
    2. `institutional-lender-license-not-verified-violations` -- `:lender/type :institutional` and `:lender/license-verified` not true.
    3. `rate-ceiling-exceeded-violations`                      -- `:lender/type :institutional` and rate exceeds 利息制限法's tiered ceiling for the principal (`commitledger.registry/rate-ceiling-for-principal`).
    4. `individual-lender-not-interest-free-violations`         -- `:lender/type :individual` and rate > 0.
    5. `individual-lender-principal-exceeds-cap-violations`      -- `:lender/type :individual` and principal > `commitledger.registry/individual-lender-principal-cap`.
    6. `individual-lender-loan-count-exceeds-threshold-violations` -- an individual lender's did:key already backs `commitledger.registry/individual-lender-loan-count-threshold` or more PRIOR COMMITTED records (see that check's own docstring: a conservative proxy, not a legal bright line).
    7. `capacity-ratio-exceeded-violations`                       -- `(existing-debt + requested-principal) / (annual-income | declared-repayment-capacity)` exceeds `commitledger.registry/capacity-ratio-ceiling`.
    8. `equity-language-detected-violations`                      -- the `:purpose` free-text field matches `commitledger.registry/equity-blocklist`.
    9. `personal-pledge-incomplete-violations`                    -- ANY required `:personal-pledge` field missing/blank. **This actor's own DISTINCTIVE check**, per this fleet's convention that every actor's ADR names its own novel contribution (see `docs/adr/0001-architecture.md`).
   10. `borrower-not-self-registered-violations`                  -- the borrower `:borrower-org-repo`/`:borrower-did` reference fields are missing/malformed (reference-field well-formedness -- ALWAYS required, independent of check 13 below).
   13. `borrower-registration-not-verified-violations`             -- `:borrower-registration-verified?` (a permanent ground-truth field set at INTAKE time by `commitledger.edge.commitment-endpoints`'s live `GET /api/open-business` lookup against `gftdcojp/cloud-itonami`'s registry, `commitledger.edge.registrylookup`) is not `true` (V2 -- see `docs/adr/0002-http-edge-live-registry-verification.md`. Numbered 13, not 11, because it is APPENDED after the two `:commitment/tranche-release` checks below, which kept their original numbers 11/12 when this was added).

  ## The two `:commitment/tranche-release` HARD checks

   11. `tranche-release-precondition-violations` -- the application must
       already be `:committed`, the `:tranche-index` must be in range
       of the application's own `:tranche-schedule`, and
       `:milestone-evidence` must be a non-blank, complete map
       (`:milestone-met?` true + non-blank `:evidence`) -- never taken
       on the advisor's self-report alone.
   12. `tranche-already-released-violations` -- a DEDICATED boolean/
       set-membership guard (`commitledger.store/tranche-already-
       released?`), never inferred from the application's own
       `:status` -- see `commitledger.store`'s own docstring for why."
  (:require [clojure.string :as str]
            [commitledger.facts :as facts]
            [commitledger.registry :as registry]
            [commitledger.store :as store]))

(def confidence-floor
  "Below this, a clean proposal still escalates to a human."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean. This
  actor performs TWO real-world actuation events -- recording a matched
  loan-based commitment, and authorizing a tranche release -- mirroring
  `cloud-itonami-isic-6493`'s two-actuation shape rather than every
  single-actuation sibling's one member. Neither ever moves money
  itself (see ns docstring)."
  #{:actuation/commitment-record :actuation/tranche-release})

(def ^:private record-checked-ops #{:commitment/record})

;; ----------------------------- legal / spec-basis -----------------------------

(defn- spec-basis-missing-violations
  "A `:commitment/record` proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's lending-disclosure
  requirements. Independently re-verifies TWO things, never trusting
  the advisor's self-report alone: (a) the proposal itself actually
  cites something (`:cites`/`:value :spec-basis`), AND (b) the
  application's own `:jurisdiction` genuinely resolves to a real entry
  in `commitledger.facts/catalog` -- an advisor cannot fabricate a
  jurisdiction absent from the catalog by simply citing SOMETHING.
  Check 1."
  [{:keys [op subject]} proposal st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          sb (facts/spec-basis (:jurisdiction a))
          value (:value proposal)]
      (when (or (nil? sb)
                (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :spec-basis-missing
          :detail (str subject " の法域(" (:jurisdiction a) ")について公式spec-basisの引用が無い/未登録のため、コミットメント記録として扱えない")}]))))

;; ----------------------------- institutional-lender checks -----------------------------

(defn- institutional-lender-license-not-verified-violations
  "For `:commitment/record`, `:lender/type :institutional` requires
  `:lender/license-verified true` on the application's own `:lender`
  map -- never taken on the advisor's self-report. Check 2."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          lender (:lender a)]
      (when (and (= :institutional (:lender/type lender))
                 (not (true? (:lender/license-verified lender))))
        [{:rule :institutional-lender-license-not-verified
          :detail (str subject " の機関貸付者(institutional lender)のライセンスが検証済み(license-verified)でない")}]))))

(defn- rate-ceiling-exceeded-violations
  "For `:commitment/record`, `:lender/type :institutional` requires the
  application's own `:proposed-rate` not exceed `commitledger.registry/
  rate-ceiling-for-principal`'s 利息制限法-tiered ceiling for its own
  `:requested-principal`. Check 3."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          lender (:lender a)]
      (when (= :institutional (:lender/type lender))
        (let [ceiling (registry/rate-ceiling-for-principal (:requested-principal a))]
          (when (> (:proposed-rate a) ceiling)
            [{:rule :rate-ceiling-exceeded
              :detail (str subject " の提案金利(" (:proposed-rate a) ")が元本("
                           (:requested-principal a) ")に対する利息制限法上限(" ceiling ")を超過している")}]))))))

;; ----------------------------- individual-lender safeguards -----------------------------

(defn- individual-lender-not-interest-free-violations
  "For `:commitment/record`, `:lender/type :individual` requires the
  application's own `:proposed-rate` to be exactly 0 (interest-free) --
  an individual lender safeguard, not a licensed institutional lending
  relationship. Check 4."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          lender (:lender a)]
      (when (and (= :individual (:lender/type lender))
                 (pos? (:proposed-rate a)))
        [{:rule :individual-lender-not-interest-free
          :detail (str subject " の個人貸付者(individual lender)は無利息(0%)である必要がある。提案金利=" (:proposed-rate a))}]))))

(defn- individual-lender-principal-exceeds-cap-violations
  "For `:commitment/record`, `:lender/type :individual` requires the
  application's own `:requested-principal` not exceed `commitledger.
  registry/individual-lender-principal-cap`. Check 5."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          lender (:lender a)]
      (when (and (= :individual (:lender/type lender))
                 (> (:requested-principal a) registry/individual-lender-principal-cap))
        [{:rule :individual-lender-principal-exceeds-cap
          :detail (str subject " の個人貸付者向け元本(" (:requested-principal a)
                       ")が上限(" registry/individual-lender-principal-cap ")を超過している")}]))))

(defn- individual-lender-loan-count-exceeds-threshold-violations
  "For `:commitment/record`, `:lender/type :individual`: independently
  count PRIOR COMMITTED commitment-record drafts whose `lender_id`
  matches this application's own `:lender/id`
  (`commitledger.store/individual-lender-commitment-count`) -- if that
  count is already >= `commitledger.registry/individual-lender-loan-
  count-threshold`, refuse. A CONSERVATIVE PROXY for 「業として」
  (acting 'as a business') repeated-lending risk under 貸金業法's
  licensing requirement -- NOT a legal bright line; documented here and
  in `commitledger.registry`'s own docstring, not silently assumed as
  precise law. Check 6."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          lender (:lender a)]
      (when (= :individual (:lender/type lender))
        (let [prior (store/individual-lender-commitment-count st (:lender/id lender))]
          (when (>= prior registry/individual-lender-loan-count-threshold)
            [{:rule :individual-lender-loan-count-exceeds-threshold
              :detail (str (:lender/id lender) " による既存コミット済み件数(" prior
                           ")が閾値(" registry/individual-lender-loan-count-threshold
                           ")以上のため、貸金業法上「業として」該当リスクの保守的プロキシとして拒否 (法的な明確な線引きではない)")}]))))))

;; ----------------------------- capacity -----------------------------

(defn- capacity-ratio-exceeded-violations
  "For `:commitment/record`, independently recompute the application's
  capacity ratio via `commitledger.registry/compute-capacity-ratio` and
  refuse if it exceeds `commitledger.registry/capacity-ratio-ceiling`.
  Needs no proposal inspection and no stored-verdict lookup -- its
  inputs are permanent ground-truth fields already on the application.
  Check 7."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)
          ratio (registry/compute-capacity-ratio a)]
      (when (> ratio registry/capacity-ratio-ceiling)
        [{:rule :capacity-ratio-exceeded
          :detail (str subject " の返済能力比率(" ratio ")が上限(" registry/capacity-ratio-ceiling ")を超過している")}]))))

;; ----------------------------- V1 lending-only scope guard -----------------------------

(defn- equity-language-detected-violations
  "For `:commitment/record`, the application's own `:purpose` free-text
  field must NOT match `commitledger.registry/equity-blocklist` -- this
  actor's V1 scope is LOAN-based support only (owner instruction), never
  equity/investment. Mirrors `cloud_itonami.funding/guard-non-equity!`'s
  concept, re-implemented locally (see `commitledger.registry`'s own
  docstring: reused, not shared code). Check 8."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)]
      (when (registry/equity-language? (:purpose a))
        [{:rule :equity-language-detected
          :detail (str subject " の目的(purpose)フィールドに出資/持分関連の語句が検出された。このアクターはV1で融資(loan)のみを扱う")}]))))

;; ----------------------------- distinctive check: personal pledge -----------------------------

(defn- personal-pledge-incomplete-violations
  "For `:commitment/record`, EVERY `commitledger.registry/personal-
  pledge-required-fields` key must be present and non-blank on the
  application's own `:personal-pledge` map
  (`commitledger.registry/personal-pledge-complete?`). **This actor's
  own DISTINCTIVE/novel check** -- see ns docstring and `docs/adr/
  0001-architecture.md`'s Decision section (every sibling actor's ADR
  names its own distinctive contribution; this one is it). Check 9."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)]
      (when-not (registry/personal-pledge-complete? (:personal-pledge a))
        [{:rule :personal-pledge-incomplete
          :detail (str subject " の personal-pledge (非金銭的な人的コミットメント) が不完全。必須フィールド: "
                       (pr-str registry/personal-pledge-required-fields))}]))))

;; ----------------------------- borrower self-registration -----------------------------

(defn- borrower-not-self-registered-violations
  "For `:commitment/record`, the application's own `:borrower-org-repo`/
  `:borrower-did` reference fields must be present and well-formed
  (`commitledger.registry/borrower-ref-well-formed?`). V1 scope: this
  does NOT call out to `gftdcojp/cloud-itonami`'s live self-registration
  registry (ADR-0013) over HTTP -- only reference-field well-formedness
  is verified (see README `What this actor does NOT do`). Check 10."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)]
      (when-not (registry/borrower-ref-well-formed? (:borrower-org-repo a) (:borrower-did a))
        [{:rule :borrower-not-self-registered
          :detail (str subject " の借り手参照(org/repo + did:key)が欠落/不整形。自己登録(ADR-0013)済みの参照として扱えない")}]))))

;; ----------------------------- V2: live borrower-registration verification -----------------------------

(defn- borrower-registration-not-verified-violations
  "For `:commitment/record`, `:borrower-registration-verified?` must be
  `true` on the application's own stored map. This field is set at
  INTAKE time by `commitledger.edge.commitment-endpoints`'s live `GET
  /api/open-business` lookup against `gftdcojp/cloud-itonami`'s public
  registry (`commitledger.edge.registrylookup`, ADR-0009 there) --
  exactly like every other check in this file, the Governor here only
  READS a permanent ground-truth field already on the stored
  application; it never calls out to the registry itself, and `check`
  (below) stays pure/synchronous. This is INDEPENDENT of check 10
  (`borrower-not-self-registered-violations`, reference-field
  well-formedness): a well-formed-but-never-verified reference (a typo'd
  org/repo, or one whose owner never actually claimed ADR-0013
  self-registration) still fails HERE even when well-formedness alone
  would have let it through. A point-in-time check at intake -- not
  re-verified before `:commitment/record` itself runs, should the
  borrower's registration status change in between (see `docs/adr/0002-
  http-edge-live-registry-verification.md`'s Consequences). Check 13."
  [{:keys [op subject]} st]
  (when (contains? record-checked-ops op)
    (let [a (store/application st subject)]
      (when-not (true? (:borrower-registration-verified? a))
        [{:rule :borrower-registration-not-verified
          :detail (str subject " の借り手 (" (:borrower-org-repo a)
                       ") は intake 時点で gftdcojp/cloud-itonami の公開レジストリ (GET /api/open-business) に "
                       "selfRegistered=true として存在確認できなかった (borrower-registration-verified?="
                       (pr-str (:borrower-registration-verified? a)) ")")}]))))

;; ----------------------------- tranche-release checks (actuation 2) -----------------------------

(defn- tranche-release-precondition-violations
  "For `:commitment/tranche-release`, independently verify: (a) the
  application is already `:committed`; (b) `:tranche-index` (from the
  request) is in range of the application's own `:tranche-schedule`;
  (c) `:milestone-evidence` (from the request) is a complete map --
  `:milestone-met? true` AND a non-blank `:evidence` string. Never taken
  on the advisor's self-report alone. Check 11."
  [{:keys [op subject tranche-index milestone-evidence]} st]
  (when (= op :commitment/tranche-release)
    (let [a (store/application st subject)
          committed? (store/application-already-committed? st subject)
          schedule (or (:tranche-schedule a) [])
          in-range? (and (integer? tranche-index) (<= 0 tranche-index) (< tranche-index (count schedule)))
          evidence-ok? (and (map? milestone-evidence)
                             (true? (:milestone-met? milestone-evidence))
                             (string? (:evidence milestone-evidence))
                             (not (str/blank? (:evidence milestone-evidence))))]
      (cond
        (not committed?)
        [{:rule :commitment-not-recorded
          :detail (str subject " はまだ commitment/record されていないため、tranche-release は提案できない")}]

        (not in-range?)
        [{:rule :tranche-index-out-of-range
          :detail (str subject " の tranche-index(" tranche-index ") が tranche-schedule の範囲外")}]

        (not evidence-ok?)
        [{:rule :milestone-evidence-missing
          :detail (str subject " の tranche " tranche-index " に、達成済み(milestone-met?)かつ非空の evidence を持つ milestone-evidence が無い")}]

        :else nil))))

(defn- tranche-already-released-violations
  "For `:commitment/tranche-release`, refuses to release the SAME
  tranche twice, off `commitledger.store/tranche-already-released?` -- a
  DEDICATED boolean guard, never inferred from `:status` (see
  `commitledger.store`'s own docstring: the fleet's own 'never a
  :status value' lesson, `credit.governor`'s ADR-0001 Decision 5,
  generalized by `factoring.governor`'s `receivable-status-
  precondition-violations`). Check 12."
  [{:keys [op subject tranche-index]} st]
  (when (= op :commitment/tranche-release)
    (when (and (integer? tranche-index) (store/tranche-already-released? st subject tranche-index))
      [{:rule :tranche-already-released
        :detail (str subject " の tranche " tranche-index " は既にリリース済み")}])))

;; ----------------------------- check aggregation -----------------------------

(defn check
  "Censors a Commitment-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool}."
  [request _context proposal st]
  (let [violation-lists [(spec-basis-missing-violations request proposal st)
                          (institutional-lender-license-not-verified-violations request st)
                          (rate-ceiling-exceeded-violations request st)
                          (individual-lender-not-interest-free-violations request st)
                          (individual-lender-principal-exceeds-cap-violations request st)
                          (individual-lender-loan-count-exceeds-threshold-violations request st)
                          (capacity-ratio-exceeded-violations request st)
                          (equity-language-detected-violations request st)
                          (personal-pledge-incomplete-violations request st)
                          (borrower-not-self-registered-violations request st)
                          (tranche-release-precondition-violations request st)
                          (tranche-already-released-violations request st)
                          (borrower-registration-not-verified-violations request st)]
        hard (into [] (apply concat violation-lists))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))]
    {:ok?          (and (empty? hard) (>= conf confidence-floor) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        (boolean (seq hard))
     :escalate?    (and (empty? hard) (or (< conf confidence-floor) stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
