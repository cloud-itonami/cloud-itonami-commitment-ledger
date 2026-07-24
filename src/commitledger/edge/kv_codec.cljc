(ns commitledger.edge.kv-codec
  "Explicit `commitledger.store` application map <-> JSON-safe (plain
  string-keyed, string/number/bool-valued) map codec for the edge KV
  persistence layer -- mirrors `commitledger.store`'s OWN `DatomicStore`
  `application->tx`/`pull->application` explicit-field approach to the
  exact same class of problem: a JSON/JS-safe wire encoding cannot carry
  a Clojure keyword or namespaced-keyword map key natively, and a
  GENERIC auto-detecting round-trip would wrongly re-keywordize a
  free-text field like `:purpose` (the exact failure mode
  `cloud_itonami.edge.ma_endpoints/flatten-namespaced-key`'s own
  docstring documents for the sibling repo's `clj->js` default -- keyword
  keys there silently lose their namespace on encode). camelCase JSON
  keys match every OTHER edge/* POST handler's own body-field convention
  in `gftdcojp/cloud-itonami` (`ma_endpoints.cljc`'s `revenueBand` etc).

  Pure data transform -- no `js/` interop anywhere in this ns, so it is
  fully portable and directly unit-testable under `clojure -M:dev:test`
  (JVM). The actual `js/JSON.stringify`/`.parse` calls live in
  `commitledger.edge.kv-store`'s CLJS-only `CloudflareKVStore`, which
  uses `application->json`/`json->application` as the encode/decode step
  either side of that text boundary.

  NOT a replacement for `commitledger.store`'s own protocol or its
  `application->tx`/`pull->application` -- purely the JSON-shape boundary
  the edge Cloudflare KV layer needs, entirely separate from the
  MemStore/DatomicStore SSoT the Governor/StateGraph tests exercise."
  )

(defn- qualified-name
  "keyword -> string, PRESERVING namespace (`:commitment/record` ->
  \"commitment/record\") -- plain `clojure.core/name` drops it, the same
  footgun `cloud_itonami.edge.ma_endpoints/flatten-namespaced-key`'s own
  docstring documents for `clj->js`'s default. The inverse is just
  `clojure.core/keyword` (1-arg): `(keyword \"commitment/record\")` =>
  `:commitment/record` -- `keyword` already splits on `/` correctly, so
  no custom decode helper is needed, only this encode-side one."
  [k]
  (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)))

(defn lender->json [lender]
  (when lender
    (cond-> {}
      (:lender/type lender) (assoc "type" (name (:lender/type lender)))
      (:lender/id lender) (assoc "id" (:lender/id lender))
      (some? (:lender/license-verified lender)) (assoc "licenseVerified" (boolean (:lender/license-verified lender))))))

(defn json->lender [m]
  (when m
    (cond-> {}
      (get m "type") (assoc :lender/type (keyword (get m "type")))
      (get m "id") (assoc :lender/id (get m "id"))
      (contains? m "licenseVerified") (assoc :lender/license-verified (get m "licenseVerified")))))

(defn pledge->json [pledge]
  (when pledge
    (cond-> {}
      (:milestone-report-cadence pledge) (assoc "milestoneReportCadence" (:milestone-report-cadence pledge))
      (:mentor-checkin-commitment pledge) (assoc "mentorCheckinCommitment" (:mentor-checkin-commitment pledge))
      (:progress-report-obligation pledge) (assoc "progressReportObligation" (:progress-report-obligation pledge)))))

(defn json->pledge [m]
  (when m
    (cond-> {}
      (get m "milestoneReportCadence") (assoc :milestone-report-cadence (get m "milestoneReportCadence"))
      (get m "mentorCheckinCommitment") (assoc :mentor-checkin-commitment (get m "mentorCheckinCommitment"))
      (get m "progressReportObligation") (assoc :progress-report-obligation (get m "progressReportObligation")))))

(defn milestone-evidence->json [ev]
  (when ev
    (cond-> {}
      (contains? ev :milestone-met?) (assoc "milestoneMet" (boolean (:milestone-met? ev)))
      (:evidence ev) (assoc "evidence" (:evidence ev)))))

(defn json->milestone-evidence [m]
  (when m
    (cond-> {}
      (contains? m "milestoneMet") (assoc :milestone-met? (get m "milestoneMet"))
      (get m "evidence") (assoc :evidence (get m "evidence")))))

(defn application->json
  "commitledger.store application map -> a plain, string-keyed,
  camelCase, JSON-safe map. Field-by-field (like `application->tx`), not
  a generic walk -- see ns docstring for why."
  [{:keys [id borrower-org-repo borrower-did requested-principal purpose existing-debt
           annual-income declared-repayment-capacity proposed-term-months personal-pledge
           lender proposed-rate jurisdiction status tranche-schedule commitment-number
           borrower-registration-verified?]}]
  (cond-> {}
    id (assoc "id" id)
    borrower-org-repo (assoc "borrowerOrgRepo" borrower-org-repo)
    (some? borrower-did) (assoc "borrowerDid" borrower-did)
    requested-principal (assoc "requestedPrincipal" requested-principal)
    purpose (assoc "purpose" purpose)
    (some? existing-debt) (assoc "existingDebt" existing-debt)
    (some? annual-income) (assoc "annualIncome" annual-income)
    (some? declared-repayment-capacity) (assoc "declaredRepaymentCapacity" declared-repayment-capacity)
    proposed-term-months (assoc "proposedTermMonths" proposed-term-months)
    personal-pledge (assoc "personalPledge" (pledge->json personal-pledge))
    lender (assoc "lender" (lender->json lender))
    (some? proposed-rate) (assoc "proposedRate" proposed-rate)
    jurisdiction (assoc "jurisdiction" jurisdiction)
    status (assoc "status" (name status))
    tranche-schedule (assoc "trancheSchedule" (vec tranche-schedule))
    commitment-number (assoc "commitmentNumber" commitment-number)
    (some? borrower-registration-verified?) (assoc "borrowerRegistrationVerified" (boolean borrower-registration-verified?))))

(defn json->application
  "Inverse of `application->json`."
  [m]
  (when m
    (cond-> {}
      (get m "id") (assoc :id (get m "id"))
      (get m "borrowerOrgRepo") (assoc :borrower-org-repo (get m "borrowerOrgRepo"))
      (contains? m "borrowerDid") (assoc :borrower-did (get m "borrowerDid"))
      (get m "requestedPrincipal") (assoc :requested-principal (get m "requestedPrincipal"))
      (get m "purpose") (assoc :purpose (get m "purpose"))
      (contains? m "existingDebt") (assoc :existing-debt (get m "existingDebt"))
      (contains? m "annualIncome") (assoc :annual-income (get m "annualIncome"))
      (contains? m "declaredRepaymentCapacity") (assoc :declared-repayment-capacity (get m "declaredRepaymentCapacity"))
      (get m "proposedTermMonths") (assoc :proposed-term-months (get m "proposedTermMonths"))
      (get m "personalPledge") (assoc :personal-pledge (json->pledge (get m "personalPledge")))
      (get m "lender") (assoc :lender (json->lender (get m "lender")))
      (contains? m "proposedRate") (assoc :proposed-rate (get m "proposedRate"))
      (get m "jurisdiction") (assoc :jurisdiction (get m "jurisdiction"))
      (get m "status") (assoc :status (keyword (get m "status")))
      (get m "trancheSchedule") (assoc :tranche-schedule (vec (get m "trancheSchedule")))
      (get m "commitmentNumber") (assoc :commitment-number (get m "commitmentNumber"))
      (contains? m "borrowerRegistrationVerified") (assoc :borrower-registration-verified? (get m "borrowerRegistrationVerified")))))

(defn ledger-state->json
  "The cross-application aggregate state (`:ledger`/`:commitment-
  history`/`:tranche-release-history`/`:released-tranches`/
  `:commitment-sequences`/`:tranche-sequences`) `commitledger.store`'s
  internal MemStore atom carries ALONGSIDE `:applications` -- needed so
  cross-application checks (check 6, `individual-lender-loan-count-
  exceeds-threshold-violations`) and the double-release guard (check 12)
  stay correct across separate, stateless HTTP requests. Facts/records in
  `:ledger`/`:commitment-history`/`:tranche-release-history` are already
  plain string-keyed maps (`commitledger.registry/register-commitment`
  etc. build them with string keys throughout, matching a Datomic/JSON-
  safe wire shape already) EXCEPT the ledger's own `:op`/`:disposition`/
  `:basis` keyword values, which need the same explicit treatment as
  `application->json` gives `:status`."
  [{:keys [ledger commitment-history tranche-release-history released-tranches
           commitment-sequences tranche-sequences]}]
  {"ledger" (mapv (fn [fact]
                     (into {} (map (fn [[k v]]
                                     [(name k) (cond (keyword? v) (qualified-name v)
                                                      (and (sequential? v) (every? keyword? v)) (mapv qualified-name v)
                                                      :else v)]))
                           fact))
                   (or ledger []))
   "commitmentHistory" (vec (or commitment-history []))
   "trancheReleaseHistory" (vec (or tranche-release-history []))
   "releasedTranches" (into {} (map (fn [[id idxs]] [id (vec idxs)])) (or released-tranches {}))
   "commitmentSequences" (or commitment-sequences {})
   "trancheSequences" (or tranche-sequences {})})

(def ^:private ledger-keyword-value-keys
  "Ledger fact keys whose VALUE is a keyword (`:t`/`:op`/`:disposition`,
  possibly namespaced -- e.g. `:op :commitment/record`) on the in-memory
  Store, needing the same explicit string<->keyword treatment
  `application->json`/`json->application` give `:status` -- see
  `ledger-state->json`'s own docstring."
  #{"t" "op" "disposition"})

(defn json->ledger-state
  [m]
  (if-not m
    {}
    {:ledger (mapv (fn [fact]
                      (into {} (map (fn [[k v]]
                                      [(keyword k)
                                       (cond
                                         (and (ledger-keyword-value-keys k) (string? v)) (keyword v)
                                         (and (= k "basis") (sequential? v)) (mapv keyword v)
                                         :else v)]))
                            fact))
                    (or (get m "ledger") []))
     :commitment-history (vec (or (get m "commitmentHistory") []))
     :tranche-release-history (vec (or (get m "trancheReleaseHistory") []))
     :released-tranches (into {} (map (fn [[id idxs]] [id (set idxs)])) (or (get m "releasedTranches") {}))
     :commitment-sequences (or (get m "commitmentSequences") {})
     :tranche-sequences (or (get m "trancheSequences") {})}))
