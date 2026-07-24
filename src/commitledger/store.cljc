(ns commitledger.store
  "SSoT for the commitment-ledger actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*`/`cloud-itonami-*` actor in this fleet uses
  (closest templates: `credit.store`, `cloud-itonami-isic-6492`;
  `factoring.store`, `cloud-itonami-isic-6493`):

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  `DatomicStore` uses `langchain-store.core` (ADR-2607141600) for the
  shared EDN-blob codec (`ls/enc`/`ls/dec*`) instead of hand-rolling it.

  Both implement the same protocol and pass the same contract
  (test/commitledger/store_contract_test.cljc), which is the whole
  point: the actor, the CommitmentLedgerGovernor and the audit ledger
  never know which SSoT they run on.

  ONE dynamic entity (the commitment APPLICATION), like `credit.store`
  -- there is no separate 'commitment' or 'tranche' entity kind, only
  two DRAFT HISTORIES (`commitment-history`/`tranche-release-history`)
  and a per-application RELEASED-TRANCHE SET, the double-actuation-guard
  ground truth `commitledger.governor/tranche-already-released-
  violations` reads. Per this fleet's own hard-won lesson (`credit.
  governor`'s ADR-0001 Decision 5's `application-not-approved-
  violations` docstring, generalized further by `factoring.governor`'s
  `receivable-status-precondition-violations`): a double-actuation guard
  must be a DEDICATED boolean/set-membership check, NEVER inferred from
  a single `:status` keyword that legitimately advances past the
  checked value after the actuation succeeds. `tranche-already-
  released?` below is exactly that dedicated guard -- a per-(application,
  tranche-index) membership test, independent of the application's own
  `:status`.

  The ledger stays append-only on every backend: 'which application was
  matched to which lender, on what terms, with what personal pledge, and
  which tranche was released when' is always a query over an immutable
  log -- the audit trail a borrower AND a lender (institutional or
  individual) both need, and the evidence an operator needs if a
  commitment is later disputed."
  (:require [commitledger.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (application [s id])
  (all-applications [s])
  (ledger [s])
  (commitment-history [s] "the append-only commitment-record draft history (actuation 1)")
  (tranche-release-history [s] "the append-only tranche-release draft history (actuation 2), across all applications")
  (released-tranches-of [s application-id] "set of tranche-index already released for this application")
  (next-commitment-sequence [s jurisdiction] "next commitment-record sequence for a jurisdiction")
  (next-tranche-sequence [s jurisdiction] "next tranche-release sequence for a jurisdiction")
  (application-already-committed? [s application-id] "has this application already been committed (:commitment/record)?")
  (tranche-already-released? [s application-id tranche-index] "has this specific tranche already been released? A DEDICATED boolean guard -- never inferred from :status, see ns docstring")
  (individual-lender-commitment-count [s lender-id] "count of PRIOR COMMITTED commitment-record drafts whose lender_id = lender-id -- ground truth for commitledger.governor/individual-lender-loan-count-exceeds-threshold-violations")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-applications [s applications] "replace/seed the application directory (map id->application)"))

;; ----------------------------- demo data -----------------------------

(defn- pledge [cadence mentor progress]
  {:milestone-report-cadence cadence
   :mentor-checkin-commitment mentor
   :progress-report-obligation progress})

(def ^:private complete-pledge
  (pledge "monthly" "biweekly check-in with assigned mentor" "quarterly written progress report to lender"))

(def ^:private institutional-lender
  {:lender/type :institutional :lender/id "did:key:z6MkInstitutionalBank01" :lender/license-verified true})

(defn demo-data
  "A small, self-contained application set so the actor + tests run
  offline. `app-clean` walks the full clean lifecycle (record then both
  tranche releases); every other application is seeded to independently
  trigger exactly ONE HARD check, matching `credit.store`'s/`factoring.
  store`'s own `demo-data` convention of one dedicated failure-mode
  fixture per check.

  Every application below carries `:borrower-registration-verified?
  true` -- V2's ground-truth field for check 13
  (`commitledger.governor/borrower-registration-not-verified-
  violations`), written at INTAKE time by
  `commitledger.edge.commitment-endpoints`'s live `GET /api/open-
  business` lookup against `gftdcojp/cloud-itonami`'s registry (see
  `commitledger.edge.registrylookup`) -- EXCEPT `app-registration-
  unverified`, the dedicated one-fixture-per-check fixture for THIS
  check, which carries `false`. This is data only, never governor
  logic: every other check's own fixture is unaffected."
  []
  {:applications
   {"app-clean"
    {:id "app-clean" :borrower-org-repo "acme/ramen-cart" :borrower-did "did:key:z6MkAcmeRamenCart01"
     :requested-principal 300000 :purpose "working capital to restock ramen-cart ingredients and propane"
     :existing-debt 100000 :annual-income 3000000 :declared-repayment-capacity nil
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [150000 150000] :borrower-registration-verified? true}

    "app-no-spec"
    {:id "app-no-spec" :borrower-org-repo "acme/no-spec-biz" :borrower-did "did:key:z6MkNoSpec01"
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "ATL" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-unlicensed"
    {:id "app-unlicensed" :borrower-org-repo "acme/unlicensed-biz" :borrower-did "did:key:z6MkUnlicensed01"
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender (assoc institutional-lender :lender/license-verified false)
     :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-rate-exceeded"
    {:id "app-rate-exceeded" :borrower-org-repo "acme/rate-exceeded-biz" :borrower-did "did:key:z6MkRateExceeded01"
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.25 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-individual-interest"
    {:id "app-individual-interest" :borrower-org-repo "acme/individual-interest-biz" :borrower-did "did:key:z6MkIndividualInterest01"
     :requested-principal 80000 :purpose "working capital" :existing-debt 10000 :annual-income 2000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderA" :lender/license-verified false}
     :proposed-rate 0.05 :jurisdiction "JPN" :status :intake
     :tranche-schedule [80000] :borrower-registration-verified? true}

    "app-individual-cap"
    {:id "app-individual-cap" :borrower-org-repo "acme/individual-cap-biz" :borrower-did "did:key:z6MkIndividualCap01"
     :requested-principal 150000 :purpose "working capital" :existing-debt 10000 :annual-income 3000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderB" :lender/license-verified false}
     :proposed-rate 0.0 :jurisdiction "JPN" :status :intake
     :tranche-schedule [150000] :borrower-registration-verified? true}

    "app-individual-repeat-1"
    {:id "app-individual-repeat-1" :borrower-org-repo "acme/individual-repeat-biz-1" :borrower-did "did:key:z6MkIndividualRepeat01"
     :requested-principal 50000 :purpose "working capital" :existing-debt 10000 :annual-income 2000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderRepeat" :lender/license-verified false}
     :proposed-rate 0.0 :jurisdiction "JPN" :status :intake
     :tranche-schedule [50000] :borrower-registration-verified? true}

    "app-individual-repeat-2"
    {:id "app-individual-repeat-2" :borrower-org-repo "acme/individual-repeat-biz-2" :borrower-did "did:key:z6MkIndividualRepeat02"
     :requested-principal 50000 :purpose "working capital" :existing-debt 10000 :annual-income 2000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderRepeat" :lender/license-verified false}
     :proposed-rate 0.0 :jurisdiction "JPN" :status :intake
     :tranche-schedule [50000] :borrower-registration-verified? true}

    "app-individual-repeat-3"
    {:id "app-individual-repeat-3" :borrower-org-repo "acme/individual-repeat-biz-3" :borrower-did "did:key:z6MkIndividualRepeat03"
     :requested-principal 50000 :purpose "working capital" :existing-debt 10000 :annual-income 2000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderRepeat" :lender/license-verified false}
     :proposed-rate 0.0 :jurisdiction "JPN" :status :intake
     :tranche-schedule [50000] :borrower-registration-verified? true}

    "app-individual-repeat-4"
    {:id "app-individual-repeat-4" :borrower-org-repo "acme/individual-repeat-biz-4" :borrower-did "did:key:z6MkIndividualRepeat04"
     :requested-principal 50000 :purpose "working capital" :existing-debt 10000 :annual-income 2000000
     :proposed-term-months 6 :personal-pledge complete-pledge
     :lender {:lender/type :individual :lender/id "did:key:z6MkIndividualLenderRepeat" :lender/license-verified false}
     :proposed-rate 0.0 :jurisdiction "JPN" :status :intake
     :tranche-schedule [50000] :borrower-registration-verified? true}

    "app-capacity-exceeded"
    {:id "app-capacity-exceeded" :borrower-org-repo "acme/capacity-exceeded-biz" :borrower-did "did:key:z6MkCapacityExceeded01"
     :requested-principal 900000 :purpose "working capital" :existing-debt 800000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [900000] :borrower-registration-verified? true}

    "app-equity-language"
    {:id "app-equity-language" :borrower-org-repo "acme/equity-language-biz" :borrower-did "did:key:z6MkEquityLanguage01"
     :requested-principal 300000 :purpose "in exchange for an equity stake / cap table allocation"
     :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-pledge-incomplete"
    {:id "app-pledge-incomplete" :borrower-org-repo "acme/pledge-incomplete-biz" :borrower-did "did:key:z6MkPledgeIncomplete01"
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge (dissoc complete-pledge :mentor-checkin-commitment)
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-borrower-missing"
    {:id "app-borrower-missing" :borrower-org-repo "" :borrower-did nil
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? true}

    "app-registration-unverified"
    {:id "app-registration-unverified" :borrower-org-repo "acme/registration-unverified-biz" :borrower-did "did:key:z6MkRegistrationUnverified01"
     :requested-principal 300000 :purpose "working capital" :existing-debt 100000 :annual-income 3000000
     :proposed-term-months 12 :personal-pledge complete-pledge
     :lender institutional-lender :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
     :tranche-schedule [300000] :borrower-registration-verified? false}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- record-commitment!
  "Backend-agnostic `:commitment/mark-recorded` -- looks up the
  application via the protocol and drafts the commitment record (the
  application's OWN `:requested-principal`/`:lender`/`:jurisdiction` --
  the governor has already verified every HARD check, so this persists
  the terms that were actually proposed/approved, not substituted
  figures), and returns {:result .. :application-patch ..} for the
  caller to persist."
  [s application-id]
  (let [a (application s application-id)
        lender-id (get-in a [:lender :lender/id])
        seq-n (next-commitment-sequence s (:jurisdiction a))
        result (registry/register-commitment
                application-id lender-id (:requested-principal a) (:jurisdiction a) seq-n)]
    {:result result
     :application-patch {:status :committed
                          :commitment-number (get result "commitment_number")}}))

(defn- release-tranche!
  "Backend-agnostic `:commitment/mark-tranche-released` -- drafts the
  tranche-release record for `tranche-index` off the application's OWN
  `:tranche-schedule`, and returns {:result .. :tranche-index ..} for
  the caller to persist (mark released + append)."
  [s application-id tranche-index]
  (let [a (application s application-id)
        amount (nth (:tranche-schedule a) tranche-index)
        seq-n (next-tranche-sequence s (:jurisdiction a))
        result (registry/register-tranche-release
                application-id tranche-index amount (:jurisdiction a) seq-n)]
    {:result result :tranche-index tranche-index}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (application [_ id] (get-in @a [:applications id]))
  (all-applications [_] (sort-by :id (vals (:applications @a))))
  (ledger [_] (:ledger @a))
  (commitment-history [_] (:commitment-history @a))
  (tranche-release-history [_] (:tranche-release-history @a))
  (released-tranches-of [_ application-id] (get-in @a [:released-tranches application-id] #{}))
  (next-commitment-sequence [_ jurisdiction] (get-in @a [:commitment-sequences jurisdiction] 0))
  (next-tranche-sequence [_ jurisdiction] (get-in @a [:tranche-sequences jurisdiction] 0))
  (application-already-committed? [_ application-id] (= :committed (get-in @a [:applications application-id :status])))
  (tranche-already-released? [_ application-id tranche-index] (contains? (get-in @a [:released-tranches application-id] #{}) tranche-index))
  (individual-lender-commitment-count [_ lender-id]
    (count (filter #(= lender-id (get % "lender_id")) (:commitment-history @a))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :application/upsert
      (swap! a update-in [:applications (:id value)] merge value)

      :commitment/mark-recorded
      (let [application-id (first path)
            {:keys [result application-patch]} (record-commitment! s application-id)
            jurisdiction (:jurisdiction (application s application-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:commitment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:applications application-id] merge application-patch)
                       (update :commitment-history registry/append result))))
        result)

      :commitment/mark-tranche-released
      (let [application-id (first path)
            tranche-index (:tranche-index value)
            {:keys [result]} (release-tranche! s application-id tranche-index)
            jurisdiction (:jurisdiction (application s application-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:tranche-sequences jurisdiction] (fnil inc 0))
                       (update-in [:released-tranches application-id] (fnil conj #{}) tranche-index)
                       (update :tranche-release-history registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applications [s applications] (when (seq applications) (swap! a assoc :applications applications)) s))

(defn seed-db
  "A MemStore seeded with the demo application set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                            :ledger [] :commitment-history [] :tranche-release-history []
                            :released-tranches {} :commitment-sequences {} :tranche-sequences {}))))

(defn empty-store
  "A MemStore with NO seeded applications -- the same empty internal
  shape `seed-db` starts from before `demo-data` is merged in, optionally
  overlaid with a partial `initial-state` map (`:applications`/`:ledger`/
  `:commitment-history`/`:tranche-release-history`/`:released-tranches`/
  `:commitment-sequences`/`:tranche-sequences` -- any subset). Used by
  `commitledger.edge.commitment-endpoints` to reconstruct a fresh, real
  (non-demo) Store per HTTP request from whatever `commitledger.edge.kv-
  store` loaded out of Cloudflare KV (see that ns's own docstring: the
  edge KV layer is a separate, HTTP-request-scoped persistence boundary,
  not a replacement for this Store protocol -- this constructor is the
  seam between the two, exactly as `with-applications` already is for
  seeding)."
  ([] (empty-store {}))
  ([initial-state]
   (->MemStore (atom (merge {:applications {} :ledger [] :commitment-history [] :tranche-release-history []
                              :released-tranches {} :commitment-sequences {} :tranche-sequences {}}
                             initial-state)))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (personal-pledge, lender, tranche-schedule, ledger
  facts, drafts) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention every sibling
  actor's store uses."
  {:application/id                {:db/unique :db.unique/identity}
   :ledger/seq                    {:db/unique :db.unique/identity}
   :commitment/seq                {:db/unique :db.unique/identity}
   :tranche/seq                   {:db/unique :db.unique/identity}
   :commit-sequence/jurisdiction  {:db/unique :db.unique/identity}
   :tranche-sequence/jurisdiction {:db/unique :db.unique/identity}
   :released-tranche/key          {:db/unique :db.unique/identity}})

(defn- application->tx
  [{:keys [id borrower-org-repo borrower-did requested-principal purpose existing-debt
           annual-income declared-repayment-capacity proposed-term-months personal-pledge
           lender proposed-rate jurisdiction status tranche-schedule commitment-number]}]
  (cond-> {:application/id id}
    borrower-org-repo               (assoc :application/borrower-org-repo borrower-org-repo)
    (some? borrower-did)            (assoc :application/borrower-did borrower-did)
    requested-principal              (assoc :application/requested-principal requested-principal)
    purpose                           (assoc :application/purpose purpose)
    (some? existing-debt)             (assoc :application/existing-debt existing-debt)
    (some? annual-income)              (assoc :application/annual-income annual-income)
    (some? declared-repayment-capacity) (assoc :application/declared-repayment-capacity declared-repayment-capacity)
    proposed-term-months                (assoc :application/proposed-term-months proposed-term-months)
    personal-pledge                      (assoc :application/personal-pledge (ls/enc personal-pledge))
    lender                                (assoc :application/lender (ls/enc lender))
    (some? proposed-rate)                  (assoc :application/proposed-rate proposed-rate)
    jurisdiction                            (assoc :application/jurisdiction jurisdiction)
    status                                   (assoc :application/status status)
    tranche-schedule                          (assoc :application/tranche-schedule (ls/enc tranche-schedule))
    commitment-number                          (assoc :application/commitment-number commitment-number)))

(def ^:private application-pull
  [:application/id :application/borrower-org-repo :application/borrower-did
   :application/requested-principal :application/purpose :application/existing-debt
   :application/annual-income :application/declared-repayment-capacity :application/proposed-term-months
   :application/personal-pledge :application/lender :application/proposed-rate
   :application/jurisdiction :application/status :application/tranche-schedule :application/commitment-number])

(defn- pull->application [m]
  (when (:application/id m)
    {:id (:application/id m) :borrower-org-repo (:application/borrower-org-repo m)
     :borrower-did (:application/borrower-did m)
     :requested-principal (:application/requested-principal m) :purpose (:application/purpose m)
     :existing-debt (:application/existing-debt m) :annual-income (:application/annual-income m)
     :declared-repayment-capacity (:application/declared-repayment-capacity m)
     :proposed-term-months (:application/proposed-term-months m)
     :personal-pledge (ls/dec* (:application/personal-pledge m))
     :lender (ls/dec* (:application/lender m))
     :proposed-rate (:application/proposed-rate m)
     :jurisdiction (:application/jurisdiction m) :status (:application/status m)
     :tranche-schedule (ls/dec* (:application/tranche-schedule m))
     :commitment-number (:application/commitment-number m)}))

(defrecord DatomicStore [conn]
  Store
  (application [_ id]
    (pull->application (d/pull (d/db conn) application-pull [:application/id id])))
  (all-applications [_]
    (->> (d/q '[:find [?id ...] :where [?e :application/id ?id]] (d/db conn))
         (map #(pull->application (d/pull (d/db conn) application-pull [:application/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (commitment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :commitment/seq ?s] [?e :commitment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (tranche-release-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :tranche/seq ?s] [?e :tranche/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (released-tranches-of [_ application-id]
    (->> (d/q '[:find [?idx ...] :in $ ?aid :where
                [?e :released-tranche/application-id ?aid]
                [?e :released-tranche/tranche-index ?idx]]
              (d/db conn) application-id)
         set))
  (next-commitment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
               :where [?e :commit-sequence/jurisdiction ?j] [?e :commit-sequence/next ?n]]
             (d/db conn) jurisdiction)
        0))
  (next-tranche-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
               :where [?e :tranche-sequence/jurisdiction ?j] [?e :tranche-sequence/next ?n]]
             (d/db conn) jurisdiction)
        0))
  (application-already-committed? [s application-id]
    (= :committed (:status (application s application-id))))
  (tranche-already-released? [s application-id tranche-index]
    (contains? (released-tranches-of s application-id) tranche-index))
  (individual-lender-commitment-count [s lender-id]
    (count (filter #(= lender-id (get % "lender_id")) (commitment-history s))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :application/upsert
      (d/transact! conn [(application->tx value)])

      :commitment/mark-recorded
      (let [application-id (first path)
            {:keys [result application-patch]} (record-commitment! s application-id)
            jurisdiction (:jurisdiction (application s application-id))
            next-n (inc (next-commitment-sequence s jurisdiction))]
        (d/transact! conn
                     [(application->tx (assoc application-patch :id application-id))
                      {:commit-sequence/jurisdiction jurisdiction :commit-sequence/next next-n}
                      {:commitment/seq (count (commitment-history s)) :commitment/record (ls/enc (get result "record"))}])
        result)

      :commitment/mark-tranche-released
      (let [application-id (first path)
            tranche-index (:tranche-index value)
            {:keys [result]} (release-tranche! s application-id tranche-index)
            jurisdiction (:jurisdiction (application s application-id))
            next-n (inc (next-tranche-sequence s jurisdiction))
            rel-key (str application-id ":" tranche-index)]
        (d/transact! conn
                     [{:tranche-sequence/jurisdiction jurisdiction :tranche-sequence/next next-n}
                      {:tranche/seq (count (tranche-release-history s)) :tranche/record (ls/enc (get result "record"))}
                      {:released-tranche/key rel-key
                       :released-tranche/application-id application-id
                       :released-tranche/tranche-index tranche-index}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-applications [s applications]
    (when (seq applications) (d/transact! conn (mapv application->tx (vals applications)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-applications s applications))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo application set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
