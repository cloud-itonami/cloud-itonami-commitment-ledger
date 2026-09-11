(ns commitledger.facts
  "Per-jurisdiction consumer/commercial-lending spec-basis catalog -- the
  G2-style citation table the CommitmentLedgerGovernor checks every
  `:commitment/record` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's lending-disclosure/
  interest-rate-restriction requirements, or did it invent one?').
  Structurally the same discipline `credit.facts` (`cloud-itonami-isic-
  6492`) and `factoring.facts` (`cloud-itonami-isic-6493`) already
  established in this fleet -- re-implemented locally here, not
  required from either sibling.

  Coverage is reported HONESTLY (see `coverage`): a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official consumer-
  lending/interest-rate-restriction regulator or statute (see
  `:provenance`); a STARTING catalog, not a from-scratch survey of all
  ~194 jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.

  `JPN`'s entry is this actor's own primary reference jurisdiction: 利息
  制限法 (Interest Rate Restriction Act, Act No. 100 of 1954) is the
  REAL statute `commitledger.registry/rate-ceiling-for-principal`'s
  three-tier ceiling (20% / 18% / 15%) is drawn from (Article 1). See
  `commitledger.registry`'s own docstring for the honest scoping note
  on why this actor applies those JPN tiers as its universal
  institutional-lender rate ceiling in V1, rather than a per-jurisdiction
  usury table (which would require a much larger seed catalog than this
  R0 builds)."
  )

(def catalog
  "iso3 -> requirement map. `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  `:commitment/record` proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency) / 法務省 (Ministry of Justice)"
          :legal-basis "利息制限法 (Interest Rate Restriction Act, Act No. 100 of 1954) Article 1 / 貸金業法 (Money Lending Business Act)"
          :national-spec "利息制限法 第一条 (元本額に応じた上限金利: 10万円未満 年20%、10万円以上100万円未満 年18%、100万円以上 年15%)"
          :provenance "https://www.fsa.go.jp/"}
   "USA" {:name "United States"
          :owner-authority "Consumer Financial Protection Bureau (CFPB)"
          :legal-basis "Truth in Lending Act (TILA) / Regulation Z (12 CFR Part 1026)"
          :national-spec "Regulation Z §1026.18 (disclosure requirements) -- state usury ceilings vary and are NOT modeled by this R0"
          :provenance "https://www.consumerfinance.gov/"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "Consumer Credit Act 1974 (as amended) / FCA CONC Sourcebook"
          :national-spec "FCA Handbook CONC 4-6 (disclosure and creditworthiness assessment)"
          :provenance "https://www.fca.org.uk/"}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Bürgerliches Gesetzbuch (BGB) §491 ff. (Verbraucherdarlehensvertrag)"
          :national-spec "BaFin-Rundschreiben zur Kreditwürdigkeitsprüfung (Verbraucherkreditrichtlinie-Umsetzung)"
          :provenance "https://www.bafin.de/"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to record a
  commitment on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-commitment-ledger R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `commitledger.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))
