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
  (with-applications [s applications] "replace/seed the application directory (map id->application)")
  (ledger-state [s]
    "the FULL cross-application aggregate as one kv-store-shaped map
    ({:ledger :commitment-history :tranche-release-history
    :released-tranches :commitment-sequences :tranche-sequences}) --
    added (ADR kotobase-persistence-migration) as the bulk-read
    counterpart `commitledger.edge.kotobase-store`'s request-scoped
    hydrate needs: hydrating this aggregate one protocol-method-call-
    per-field (this Store already exposes every individual piece via
    `ledger`/`commitment-history`/`tranche-release-history`/
    `released-tranches-of`/`next-commitment-sequence`/`next-tranche-
    sequence`) is exactly what `commitledger.edge.kv-store/save-store!`
    already does when TALKING to this protocol from the outside; this
    method exists so a Store backed by an ASYNC db-api (kotobase.net,
    where each of those individual reads is a separate remote round-
    trip) can do the SAME aggregation itself, chained, and hand back one
    promise-like of the whole map instead of making the caller
    orchestrate N separate awaited calls.")
  (with-ledger-state [s ledger-state]
    "bulk-replace the FULL cross-application aggregate from a kv-store-
    shaped map (see `ledger-state` above) -- the bulk-write counterpart,
    added for the same reason: `commitledger.edge.kotobase-store`'s
    request-scoped persist needs to set every field of the aggregate at
    once after a StateGraph run, and a Store backed by an async db-api
    should be able to do that as ONE batched remote transact instead of
    the caller replaying `commit-record!`/`append-ledger!` calls (which
    assume a SYNCHRONOUS db-api -- see `DatomicStore`'s own docstring)
    against it one at a time."))

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
  (with-applications [s applications] (when (seq applications) (swap! a assoc :applications applications)) s)
  (ledger-state [_]
    {:ledger (:ledger @a) :commitment-history (:commitment-history @a)
     :tranche-release-history (:tranche-release-history @a)
     :released-tranches (:released-tranches @a {})
     :commitment-sequences (:commitment-sequences @a {})
     :tranche-sequences (:tranche-sequences @a {})})
  (with-ledger-state [s {:keys [ledger commitment-history tranche-release-history
                               released-tranches commitment-sequences tranche-sequences]}]
    (swap! a merge {:ledger (or ledger [])
                    :commitment-history (or commitment-history [])
                    :tranche-release-history (or tranche-release-history [])
                    :released-tranches (into {} (map (fn [[k v]] [k (set v)])) (or released-tranches {}))
                    :commitment-sequences (or commitment-sequences {})
                    :tranche-sequences (or tranche-sequences {})})
    s))

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

;; ----------------------------- DatomicStore (langchain.db, or an injected db-api) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (personal-pledge, lender, tranche-schedule, ledger
  facts, drafts) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention every sibling
  actor's store uses.

  `:ledger/seq`/`:commitment/seq`/`:tranche/seq` values are ALWAYS
  written as STRINGS (`(str (count ...))`, never a raw integer) even
  though they are plain sequence counters -- kotobase-server's
  `do-transact` collides EVERY `:db.unique/identity` attribute whose
  value is a NUMBER (not a string) into entity id \"\" (empty string),
  silently merging every ledger/commitment/tranche entry that ever hits
  this into ONE entity on a live kotobase.net graph (confirmed against
  production, ADR-2607184000's known-issues section; independently
  re-confirmed against this actor's own would-be kotobase.net graph
  before this migration -- string-valued identity attrs, e.g.
  `:application/id`, are unaffected). This is a correctness fix that
  applies to EVERY backend this schema is used with, not just the
  kotobase-backed one, since the in-process default (`datomic-store`)
  shares this exact schema/tx-builder code; the string encoding is
  harmless there too (`parse-seq-num` below decodes it back to a number
  wherever numeric sort/compare is needed)."
  {:application/id                {:db/unique :db.unique/identity}
   :ledger/seq                    {:db/unique :db.unique/identity}
   :commitment/seq                {:db/unique :db.unique/identity}
   :tranche/seq                   {:db/unique :db.unique/identity}
   :commit-sequence/jurisdiction  {:db/unique :db.unique/identity}
   :tranche-sequence/jurisdiction {:db/unique :db.unique/identity}
   :released-tranche/key          {:db/unique :db.unique/identity}})

(defn- parse-seq-num
  "A `:ledger/seq`/`:commitment/seq`/`:tranche/seq` wire value (ALWAYS a
  string on this schema -- see schema docstring above) decoded back to a
  number, purely for local `sort-by`/comparison purposes. Without this,
  `(sort-by first [[\"10\" ..] [\"2\" ..]])` would sort lexicographically
  (\"10\" before \"2\"), silently misordering the ledger/commitment/
  tranche history once either count reaches double digits."
  [s]
  #?(:clj (Long/parseLong (str s))
     :cljs (js/parseInt (str s) 10)))

(defn- chain
  "`v` is either a plain value (a SYNCHRONOUS db-api, e.g. the in-process
  `langchain.db/api` default this ns's own `datomic-store` still uses
  unchanged) or, under `:cljs` when `db-api` is an INJECTED async
  backend (e.g. `langchain.kotoba-db/kotoba-api-async` pointed at a live
  kotobase.net graph -- see `commitledger.edge.kotobase-store`), a real
  `js/Promise`. Returns `(f v)` directly for a plain value, or a Promise
  of `(f resolved-v)` for a Promise -- lets `application`/`all-
  applications`/`with-applications`/`ledger-state`/`with-ledger-state`
  below work unchanged against EITHER kind of `db-api` (this fleet's
  edge-layer code already documents this identical duality as
  \"promise-like\" on its own KVStore protocols, e.g.
  `commitledger.edge.kv-store`; this is the same convention one layer
  down). `:clj` `db-api` is always synchronous today, so `v` is never a
  Promise there."
  [v f]
  #?(:cljs (if (instance? js/Promise v) (.then v f) (f v))
     :clj  (f v)))

(defn- collect-chain
  "`[v1 v2 ...]` (each `chain`-able -- a plain value or, on `:cljs`, a
  Promise) -> a `chain`-able of a vector of resolved values, in order.
  The `chain`-based analog of `commitledger.edge.kv-store/collect`
  (itself a portable `js/Promise.all` substitute over `pcompat`) -- used
  by `all-applications`/`ledger-state` to gather N per-id/per-jurisdiction
  remote reads into one result without this ns depending on any edge-
  layer `pcompat`."
  [vs]
  (letfn [(go [vs acc]
            (if (empty? vs)
              acc
              (chain (first vs) (fn [v] (go (rest vs) (conj acc v))))))]
    (go vs [])))

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

(defn- decode-application-blob
  "Decode legacy EDN-string blobs while accepting values already decoded by
  langchain.kotoba-db's normalized pull boundary."
  [v]
  (if (string? v) (ls/dec* v) v))

(defn- pull->application [m]
  ;; Kotobase stores the lookup-ref value as the entity id rather than as an
  ;; attribute. langchain.kotoba-db therefore backfills :application/id for
  ;; lookup-ref pulls, including an empty pull for a missing entity. Require at
  ;; least one real stored attribute so that the latter remains a nil lookup.
  (when (and (:application/id m)
             (some (fn [attr] (some? (get m attr)))
                   (remove #{:application/id} application-pull)))
    {:id (:application/id m) :borrower-org-repo (:application/borrower-org-repo m)
     :borrower-did (:application/borrower-did m)
     :requested-principal (:application/requested-principal m) :purpose (:application/purpose m)
     :existing-debt (:application/existing-debt m) :annual-income (:application/annual-income m)
     :declared-repayment-capacity (:application/declared-repayment-capacity m)
     :proposed-term-months (:application/proposed-term-months m)
     :personal-pledge (decode-application-blob (:application/personal-pledge m))
     :lender (decode-application-blob (:application/lender m))
     :proposed-rate (:application/proposed-rate m)
     :jurisdiction (:application/jurisdiction m) :status (:application/status m)
     :tranche-schedule (decode-application-blob (:application/tranche-schedule m))
     :commitment-number (:application/commitment-number m)}))

(defrecord DatomicStore [conn db-api]
  Store
  ;; `application`/`all-applications`/`with-applications`/`ledger-state`/
  ;; `with-ledger-state` are `chain`-aware (work against EITHER a sync or
  ;; an async `db-api` -- see `chain`'s own docstring): these five are
  ;; the ONLY methods `commitledger.edge.kotobase-store`'s request-scoped
  ;; hydrate/persist boundary calls against a REMOTE (async) db-api.
  ;; Every other method below assumes a SYNCHRONOUS `db-api` (true of the
  ;; in-process default `datomic-store` always uses, `langchain.db/api`)
  ;; -- they are only ever invoked, in this actor, against the in-process
  ;; snapshot store the StateGraph runs against per request, never
  ;; against the remote store directly (see that ns's docstring for why:
  ;; `langgraph.graph/run*` executes fully synchronously, so nothing it
  ;; calls mid-graph can be a network round-trip in a Cloudflare Pages
  ;; Function, which has no synchronous I/O primitive at all).
  (application [_ id]
    (chain ((:pull db-api) ((:db db-api) conn) application-pull [:application/id id])
           pull->application))
  (all-applications [_]
    (chain ((:q db-api) '[:find [?id ...] :where [?e :application/id ?id]] ((:db db-api) conn))
           (fn [ids]
             (chain (collect-chain
                     (mapv (fn [id] ((:pull db-api) ((:db db-api) conn) application-pull [:application/id id])) ids))
                    (fn [pulls] (vec (sort-by :id (map pull->application pulls))))))))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by (comp parse-seq-num first))
         (mapv (comp ls/dec* second))))
  (commitment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :commitment/seq ?s] [?e :commitment/record ?r]] (d/db conn))
         (sort-by (comp parse-seq-num first))
         (mapv (comp ls/dec* second))))
  (tranche-release-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :tranche/seq ?s] [?e :tranche/record ?r]] (d/db conn))
         (sort-by (comp parse-seq-num first))
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
                      ;; :commitment/seq is a :db.unique/identity attr --
                      ;; MUST be a string, never a raw int (see schema
                      ;; docstring: a numeric identity value collides to
                      ;; entity id "" on kotobase-server).
                      {:commitment/seq (str (count (commitment-history s))) :commitment/record (ls/enc (get result "record"))}])
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
                      ;; :tranche/seq -- same string-identity requirement, see above.
                      {:tranche/seq (str (count (tranche-release-history s))) :tranche/record (ls/enc (get result "record"))}
                      {:released-tranche/key rel-key
                       :released-tranche/application-id application-id
                       :released-tranche/tranche-index tranche-index}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    ;; :ledger/seq -- same string-identity requirement, see schema docstring.
    (d/transact! conn [{:ledger/seq (str (count (ledger s))) :ledger/fact (ls/enc fact)}])
    fact)
  (with-applications [s applications]
    (if (seq applications)
      (chain ((:transact! db-api) conn (mapv application->tx (vals applications))) (fn [_] s))
      s))
  (ledger-state [_]
    ;; Unlike `ledger`/`commitment-history`/`tranche-release-history`
    ;; above (which hardcode `d/q`/`(d/db conn)`, i.e. only ever work
    ;; against the in-process default), this method goes through
    ;; `db-api` directly so it ALSO works when `db-api` is the async
    ;; remote one (`commitledger.edge.kotobase-store`'s hydrate is the
    ;; only caller that needs the async path). All 6 sub-queries are
    ;; independent of each other (no jurisdiction/application-id needs
    ;; to be known up front -- unlike `next-commitment-sequence`/
    ;; `released-tranches-of`, which take a specific jurisdiction/
    ;; application-id, these ask for EVERY entity of each kind directly),
    ;; so they're gathered via `collect-chain` rather than nested.
    (let [db ((:db db-api) conn)]
      (chain (collect-chain
              [((:q db-api) '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] db)
               ((:q db-api) '[:find ?s ?r :where [?e :commitment/seq ?s] [?e :commitment/record ?r]] db)
               ((:q db-api) '[:find ?s ?r :where [?e :tranche/seq ?s] [?e :tranche/record ?r]] db)
               ((:q db-api) '[:find ?aid ?idx :where [?e :released-tranche/application-id ?aid] [?e :released-tranche/tranche-index ?idx]] db)
               ((:q db-api) '[:find ?j ?n :where [?e :commit-sequence/jurisdiction ?j] [?e :commit-sequence/next ?n]] db)
               ((:q db-api) '[:find ?j ?n :where [?e :tranche-sequence/jurisdiction ?j] [?e :tranche-sequence/next ?n]] db)])
             (fn [[ledger-rows commitment-rows tranche-rows rel-rows commit-seq-rows tranche-seq-rows]]
               {:ledger (->> ledger-rows (sort-by (comp parse-seq-num first)) (mapv (comp ls/dec* second)))
                :commitment-history (->> commitment-rows (sort-by (comp parse-seq-num first)) (mapv (comp ls/dec* second)))
                :tranche-release-history (->> tranche-rows (sort-by (comp parse-seq-num first)) (mapv (comp ls/dec* second)))
                :released-tranches (reduce (fn [m [aid idx]] (update m aid (fnil conj #{}) idx)) {} rel-rows)
                :commitment-sequences (into {} commit-seq-rows)
                :tranche-sequences (into {} tranche-seq-rows)}))))
  (with-ledger-state [s {:keys [ledger commitment-history tranche-release-history
                                released-tranches commitment-sequences tranche-sequences]}]
    (let [tx (cond-> []
               true (into (map-indexed (fn [i f] {:ledger/seq (str i) :ledger/fact (ls/enc f)}) (or ledger [])))
               true (into (map-indexed (fn [i r] {:commitment/seq (str i) :commitment/record (ls/enc r)}) (or commitment-history [])))
               true (into (map-indexed (fn [i r] {:tranche/seq (str i) :tranche/record (ls/enc r)}) (or tranche-release-history [])))
               true (into (mapcat (fn [[aid idxs]]
                                     (map (fn [idx] {:released-tranche/key (str aid ":" idx)
                                                    :released-tranche/application-id aid
                                                    :released-tranche/tranche-index idx})
                                          idxs))
                                  (or released-tranches {})))
               true (into (map (fn [[j n]] {:commit-sequence/jurisdiction j :commit-sequence/next n}) (or commitment-sequences {})))
               true (into (map (fn [[j n]] {:tranche-sequence/jurisdiction j :tranche-sequence/next n}) (or tranche-sequences {}))))]
      (if (seq tx)
        (chain ((:transact! db-api) conn tx) (fn [_] s))
        s))))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications]}]
   (let [s (->DatomicStore (d/create-conn schema) d/api)]
     (with-applications s applications))))

(defn store-with-api
  "`DatomicStore` backed by an INJECTED `db-api` map (`{:q :transact! :db
  :pull :entid}` -- `langchain.db/api`'s own shape, OR an async variant
  of it, e.g. `langchain.kotoba-db/kotoba-api-async` -- see `chain`'s
  docstring for how this record tolerates either) + a matching `conn`,
  instead of hardcoding `langchain.db`/`langchain.db/create-conn`. This
  is what lets this actor's application/ledger/commitment/tranche data
  live somewhere OTHER than an in-process atom -- e.g.
  `langchain.kotoba-db/kotoba-api-async` + a `kotoba-conn*` pointed at a
  live kotobase-server graph (see `commitledger.edge.kotobase-store`) --
  mirrors `crm.store/store-with-api` (cloud-itonami-isic-5820,
  ADR-2607184000) precisely. Does NOT seed demo data."
  [db-api conn]
  (->DatomicStore conn db-api))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo application set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
