(ns commitledger.edge.commitment-endpoints
  "The four HTTP handlers V2 exposes over the commitment-ledger actor
  (`docs/adr/0002-http-edge-live-registry-verification.md`), mirroring
  `cloud_itonami.edge.ma_endpoints`'/`cloud_itonami.edge.register`'s own
  handler-boilerplate shape (env/org/repo/body/context/did args, a FLAT
  `pc/then` chain rather than nested callbacks, `auth/json-response`
  error shapes) -- adapted so the CORE handler logic (`intake-core!` etc,
  below) is portable `.cljc`, testable on JVM with `MockLookup`/
  `mem-kv-store`/`mock-verifier`, and ONLY the top-level `on-request-*`
  Cloudflare Pages Function entry points at the bottom of this ns are
  `:cljs`-only (parsing `context`, producing a real `js/Response`).

  Every write handler is CACAO-gated (`commitledger.edge.auth`):
    - `on-request-post-intake`         -- the BORROWER's own resources
      must include `kotoba://itonami/{org}/{repo}` for the business
      THIS application is about (mirrors `cloud_itonami.edge.register`'s
      claim-scope check: only the actual business owner may submit an
      application for their own business).
    - `on-request-post-record`         -- the caller's verified CACAO
      `iss` must equal the STORED application's own `:lender/id`
      (`commitledger.edge.auth/require-lender`) -- only the actual named
      lender may trigger their own commitment.
    - `on-request-post-tranche-release` -- same lender-identity check
      (the lender who recorded the commitment is the one authorizing
      tranche release).
    - `on-request-get-application`     -- PUBLIC, no CACAO needed (read
      access is not sensitive here, mirroring `cloud_itonami.edge.open_
      business`'s own public-GET posture) -- redacts `:lender/id` and
      `:borrower-did` from the response (see `public-view` below).
    - `on-request-post-approve`         -- V3
      (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`). Same
      lender-identity gate as `-record`/`-tranche-release`
      (`commitledger.edge.auth/require-lender`) -- see that ADR's
      Decision 1 for why this option was chosen over a distinct
      operator/reviewer capability string.

  `:commitment/record`/`:commitment/tranche-release` run the EXISTING,
  UNMODIFIED `commitledger.operation/build` StateGraph -- this ns never
  reimplements Governor/Phase logic, it only wires HTTP <-> the actor.
  Per the actor's own structural invariant (`commitledger.phase`'s ns
  docstring: neither actuation is EVER auto-eligible, at any phase),
  running the graph against a governor-clean proposal for either
  actuation ALWAYS pauses at `:request-approval` (`g/run*` returns
  `:status :interrupted`) -- this V2 HTTP surface exposes that as
  disposition `\"request-approval\"` and stops there.

  V3 addendum -- the resume step V2 explicitly deferred is now built:
  `run-graph!` persists EVERY run's checkpoint to KV via
  `commitledger.edge.kv-checkpoint` (not just an in-process ephemeral
  checkpointer, which would lose the interrupted state the instant this
  stateless Function invocation ends), and `on-request-post-approve`
  loads that persisted checkpoint and resumes it (`g/run* ...
  {:resume? true}`) -- see `docs/adr/0003-isic6492-wiring-and-approval-
  resume.md` for the full design (including WHY this had to be built
  now: without it, `commitledger.operation`'s new isic-6492 wiring in
  the `:commit` node would be unreachable from this live HTTP service,
  only reachable from unit tests that construct an already-approved
  context directly). `on-request-post-record` ALSO constructs a
  `commitledger.edge.isic6492-client/LiveIsic6492Client` from
  `env.COMMITMENT_LEDGER_ACTOR_SEED`/`_DID`/`ISIC6492_BASE_URL` and
  threads it (plus a `context.waitUntil`-backed fire-and-forget wrapper)
  into `commitledger.operation/build`'s new opts -- `-tranche-release`
  does NOT (see `commitledger.operation`'s ns docstring: only a NEW
  `:commitment/record` triggers an isic-6492 intake)."
  (:require [clojure.string :as str]
            [commitledger.edge.auth :as auth]
            [commitledger.edge.isic6492-client :as isic6492]
            [commitledger.edge.kotobase-store :as kotobase]
            [commitledger.edge.kv-checkpoint :as kvcp]
            [commitledger.edge.kv-codec :as kv-codec]
            [commitledger.edge.kv-store :as kv]
            [commitledger.edge.pcompat :as pc]
            [commitledger.edge.registrylookup :as lookup]
            [commitledger.operation :as op]
            [commitledger.phase :as phase]
            [commitledger.store :as store]
            [langgraph.checkpoint :as cp]
            [langgraph.graph :as g]))

;; ----------------------------- shared -----------------------------

(defn- gen-id
  "A simple, sufficiently-unique application id -- {org}/{repo}-{random}
  (mirrors the borrower's own claimed tenant in the id, unlike an opaque
  UUID, so a GET .../commitment/{id} response is self-describing without
  a second lookup)."
  [org repo]
  (str org "/" repo "-"
       #?(:cljs (.toString (js/Math.floor (* (js/Math.random) 1e12)) 36)
          :clj  (Long/toString (long (* (rand) 1e12)) 36))))

(defn public-view
  "The stored application, minus `:lender/id`/`:borrower-did` -- see ns
  docstring's `on-request-get-application` note. Everything else
  (principal/purpose/jurisdiction/status/tranche-schedule/personal-
  pledge/lender type & license-verified/borrower-registration-
  verified?) is public: this is a governed loan-based commitment
  record, not a private financial account, and the actor's whole point
  is an auditable, matchable ledger."
  [application]
  (-> application
      (update :lender dissoc :lender/id)
      (dissoc :borrower-did)))

;; ----------------------------- intake -----------------------------

(defn- normalize-lender
  "BUGFIX (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`): a
  REAL wire JSON `\"lender\": {\"type\" .. \"id\" .. \"licenseVerified\"
  ..}` body has no way to natively express the namespaced keyword keys
  (`:lender/type`/`:lender/id`/`:lender/license-verified`) every OTHER
  ns in this repo (`commitledger.store`/`commitledger.governor`/
  `commitledger.edge.auth/require-lender`) expects -- `parse-intake-
  body` previously just selected `:lender`/`\"lender\"` at the TOP level
  without recursing into the nested map's own keys, so `:lender/id` was
  ALWAYS nil for any REAL (non-test-fixture) HTTP caller, making
  `require-lender` unconditionally reject every live `:commitment/
  record` caller. Confirmed empirically (2026-07-24, a real live `POST
  /api/commitment/record` call). Fixed by reusing `commitledger.edge.kv-
  codec/json->lender` (already written for the KV-persistence codec
  path, doing exactly this conversion -- this ns simply never called
  it). A value that's already correctly shaped (`:lender/type` present
  -- e.g. every existing JVM test fixture, which constructs CLJS maps
  directly, bypassing JSON) passes through unchanged."
  [v]
  (cond
    (nil? v) v
    (contains? v :lender/type) v
    :else (kv-codec/json->lender v)))

(defn- normalize-pledge
  "Same fix, same reasoning, for `:personal-pledge` -- its wire keys
  (`milestoneReportCadence` etc) are camelCase, not even a `keyword`-ing
  away from the expected kebab-case (`:milestone-report-cadence`);
  `commitledger.edge.kv-codec/json->pledge` already does this
  conversion correctly."
  [v]
  (cond
    (nil? v) v
    (contains? v :milestone-report-cadence) v
    :else (kv-codec/json->pledge v)))

(defn- parse-intake-body
  "Raw request-body map (already parsed from JSON, string/camelCase keys
  from the wire OR already-kebab-keyword from a test fixture -- accepts
  either, mirroring `cloud_itonami.edge.ma_endpoints`'s own camel->kebab
  body-normalization convention) -> a `commitledger.store` application
  map shape (minus `:id`/`:status`/`:borrower-registration-verified?`,
  which `intake-core!` fills in)."
  [body]
  {:borrower-org-repo (or (get body :borrower-org-repo) (get body "borrowerOrgRepo"))
   :borrower-did (or (get body :borrower-did) (get body "borrowerDid"))
   :requested-principal (or (get body :requested-principal) (get body "requestedPrincipal"))
   :purpose (or (get body :purpose) (get body "purpose"))
   :existing-debt (or (get body :existing-debt) (get body "existingDebt"))
   :annual-income (or (get body :annual-income) (get body "annualIncome"))
   :declared-repayment-capacity (or (get body :declared-repayment-capacity) (get body "declaredRepaymentCapacity"))
   :proposed-term-months (or (get body :proposed-term-months) (get body "proposedTermMonths"))
   :personal-pledge (normalize-pledge (or (get body :personal-pledge) (get body "personalPledge")))
   :lender (normalize-lender (or (get body :lender) (get body "lender")))
   :proposed-rate (or (get body :proposed-rate) (get body "proposedRate"))
   :jurisdiction (or (get body :jurisdiction) (get body "jurisdiction"))
   :tranche-schedule (or (get body :tranche-schedule) (get body "trancheSchedule"))})

(defn- borrower-org-repo->lookup-args
  "The application's own `:borrower-org-repo` (\"{org}/{repo}\") ->
  `[org repo]`, or `[nil nil]` if malformed/absent -- `check-
  registration`'s fail-closed `MockLookup`/`LiveLookup` contract already
  treats an unresolvable id as `false`, matching check 10's own
  well-formedness gate.

  BUGFIX (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`):
  `check-registration` must verify the APPLICATION's OWN claimed
  borrower identity (this ns's own `intake-core!` docstring: 'the
  borrower's OWN claimed org/repo reference' -- and `commitledger.edge.
  registrylookup`'s ns docstring: 'confirming the reference actually
  resolves to a claimed, public ADR-0013 tenant'), NOT the URL path's
  `org`/`repo` route params -- confirmed empirically (2026-07-24,
  against the REAL deployed artifact) that THIS repo's actual Cloudflare
  Pages Functions layout (`functions/api/commitment/intake.js` etc) has
  NO `[org]`/`[repo]` dynamic path segments anywhere, so `params-of`'s
  route params are ALWAYS nil/undefined on live infra -- meaning the
  registry lookup, if it queried `org`/`repo` directly (as it did before
  this fix), would query an empty/empty id and could NEVER match a real
  self-registered tenant regardless of what the application actually
  claims, silently making check 13 impossible to ever pass. The CACAO
  resource-scope check (`auth/verify-cacao-header verifier cacao-header
  org repo`, just above) is UNCHANGED by this fix -- it still gates on
  the URL-derived `org`/`repo` exactly as before; only the REGISTRY
  LOOKUP's target changed. Does not touch, reorder, or reinterpret any
  of the 13 Governor checks."
  [borrower-org-repo]
  (if (and (string? borrower-org-repo) (str/includes? borrower-org-repo "/"))
    (let [parts (str/split borrower-org-repo #"/" 2)]
      (if (= 2 (count parts)) parts [nil nil]))
    [nil nil]))

(defn intake-core!
  "kv-store, lookup, verifier, cacao-header, org, repo, body ->
  promise-like of `{:status int :body map}`. CACAO-gated on the CALLER's
  resources including `kotoba://itonami/{org}/{repo}` -- only the actual
  business owner may submit an application for their own business
  (mirrors `cloud_itonami.edge.register`'s own claim-scope check). Once
  authorized: builds the application, runs the LIVE registry lookup
  against the application's OWN claimed `:borrower-org-repo` (check 13's
  ground truth -- see `borrower-org-repo->lookup-args`'s docstring),
  persists it, returns `{\"ok\": true, \"id\": ...}` (201). Never runs
  the Governor itself (intake is not a HARD-checked op; the Governor's
  11 `:commitment/record` checks, including 13, run when `:commitment/
  record` is later requested -- see `commitledger.governor`)."
  [kv-store lookup verifier cacao-header org repo body]
  (pc/then
   (auth/verify-cacao-header verifier cacao-header org repo)
   (fn [{:keys [ok? response]}]
     (if-not ok?
       (pc/resolved response)
       (let [id (gen-id org repo)
             base (parse-intake-body body)
             [lookup-org lookup-repo] (borrower-org-repo->lookup-args (:borrower-org-repo base))]
         (pc/then
          (lookup/check-registration lookup lookup-org lookup-repo)
          (fn [verified?]
            (let [application (assoc base :id id :status :intake
                                      :borrower-registration-verified? verified?)]
              (pc/then
               (kv/kv-put-application! kv-store id application)
               (fn [_] (auth/json-response {:ok true :id id :borrowerRegistrationVerified verified?} 201)))))))))))

;; ----------------------------- record / tranche-release (run the actor graph) -----------------------------

(defn- disposition->str [d]
  (case d :commit "commit" :hold "hold" :escalate "request-approval" (name d)))

(defn- run-graph!
  "kv-store, checkpoint-store, subject-id, request-map, [opts] ->
  promise-like of `{:status int :body map}`. Loads the FULL cross-
  application store from KV (`commitledger.edge.kv-store/load-store`)
  AND the persisted checkpoint for this thread-id (`commitledger.edge.
  kv-checkpoint/load-checkpointer` -- V3: without this, an interrupted
  run's state would be lost the instant this Function invocation ends,
  and `on-request-post-approve` would have nothing to resume), runs
  `commitledger.operation/build`'s UNMODIFIED StateGraph for `request-
  map`'s `:op` against it (phase 3, `commitledger.advisor/mock-advisor`
  -- see ns docstring for why V2 does not wire an LLM advisor here),
  persists whatever the graph mutated back to KV (both the Store AND the
  checkpoint), and reports the resulting disposition. Shared by
  `on-request-post-record`/`-tranche-release`.
  opts:
    :isic6492-client -- threaded into `commitledger.operation/build`
                        (only ever passed for `:commitment/record`, by
                        `on-request-post-record` -- see ns docstring)
    :wait-until       -- ditto"
  [kv-store checkpoint-store subject request-map & [{:keys [isic6492-client wait-until]}]]
  (pc/then
   (kv/load-store kv-store)
   (fn [st]
     (if-not (store/application st subject)
       (pc/resolved (auth/json-response {:ok false :error "not found" :reason (str subject " has no intake on file")} 404))
       (pc/then
        (kvcp/load-checkpointer checkpoint-store subject)
        (fn [checkpointer]
          (let [build-opts (cond-> {:checkpointer checkpointer}
                              isic6492-client (assoc :isic6492-client isic6492-client)
                              wait-until (assoc :wait-until wait-until))
                actor (op/build st build-opts)
                context {:actor-id "commitledger-edge" :actor-role :platform-operator :phase phase/default-phase}
                result (g/run* actor {:request request-map :context context} {:thread-id subject})
                disposition (cond
                              (= :interrupted (:status result)) :escalate
                              :else (get-in result [:state :disposition]))]
            (pc/then
             (kv/save-store! kv-store st)
             (fn [_]
               (pc/then
                (kvcp/save-checkpoint! checkpoint-store checkpointer subject)
                (fn [_]
                  (auth/json-response
                   {:ok true :id subject :disposition (disposition->str disposition)}
                   (if (= :escalate disposition) 202 200)))))))))))))

(defn record-core!
  "kv-store, checkpoint-store, verifier, cacao-header, org, repo,
  subject, [isic6492-opts] -> promise-like of `{:status int :body map}`.
  CACAO-gated on the caller's `iss` matching the STORED application's
  own `:lender/id` (`commitledger.edge.auth/require-lender`) --
  resources scope is irrelevant here (this is a LENDER identity check,
  not a borrower tenant-ownership check like intake's), so this loads
  the application first, then verifies. `isic6492-opts` (`{:isic6492-
  client .. :wait-until ..}`) is threaded straight into `run-graph!` --
  see that fn's + ns docstring."
  [kv-store checkpoint-store verifier cacao-header org repo subject & [isic6492-opts]]
  (pc/then
   (kv/kv-get-application kv-store subject)
   (fn [application]
     (if-not application
       (pc/resolved (auth/json-response {:ok false :error "not found" :reason (str subject " has no intake on file")} 404))
       (pc/then
        (auth/verify-cacao-header verifier cacao-header org repo)
        (fn [{:keys [ok? iss response]}]
          (if-not ok?
            (pc/resolved response)
            (let [lender-check (auth/require-lender iss application)]
              (if-not (:ok? lender-check)
                (pc/resolved (:response lender-check))
                (run-graph! kv-store checkpoint-store subject
                            {:op :commitment/record :subject subject} isic6492-opts))))))))))

(defn tranche-release-core!
  "Like `record-core!`, plus `:tranche-index`/`:milestone-evidence` from
  the request body -- same lender-identity gate (the lender who recorded
  the commitment is the one authorizing tranche release). Never passes
  isic6492-opts (see ns docstring: only a NEW `:commitment/record`
  triggers an isic-6492 intake)."
  [kv-store checkpoint-store verifier cacao-header org repo subject body]
  (pc/then
   (kv/kv-get-application kv-store subject)
   (fn [application]
     (if-not application
       (pc/resolved (auth/json-response {:ok false :error "not found" :reason (str subject " has no intake on file")} 404))
       (pc/then
        (auth/verify-cacao-header verifier cacao-header org repo)
        (fn [{:keys [ok? iss response]}]
          (if-not ok?
            (pc/resolved response)
            (let [lender-check (auth/require-lender iss application)]
              (if-not (:ok? lender-check)
                (pc/resolved (:response lender-check))
                (let [tranche-index (or (get body :tranche-index) (get body "trancheIndex"))
                      raw-ev (or (get body :milestone-evidence) (get body "milestoneEvidence"))
                      milestone-evidence (when raw-ev
                                           {:milestone-met? (boolean (or (get raw-ev :milestone-met?) (get raw-ev "milestoneMet")))
                                            :evidence (or (get raw-ev :evidence) (get raw-ev "evidence"))})]
                  (run-graph! kv-store checkpoint-store subject
                              {:op :commitment/tranche-release :subject subject
                               :tranche-index tranche-index :milestone-evidence milestone-evidence})))))))))))

;; ----------------------------- approve (V3, resumes an interrupted run) -----------------------------

(defn approve-core!
  "kv-store, checkpoint-store, verifier, cacao-header, org, repo,
  subject, approved?, [isic6492-opts] -> promise-like of `{:status int
  :body map}`. Resumes an interrupted `:request-approval` run for
  `subject` and drives it to `:commit` (if `approved?`) or `:hold` (if
  not) -- `g/run* ... {:thread-id subject :resume? true}` against the
  checkpoint `run-graph!` persisted earlier (see `commitledger.edge.kv-
  checkpoint`).

  IMPORTANT (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`):
  `isic6492-opts` is threaded into `op/build` HERE, not (only) in
  `record-core!` -- because `:commitment/record` ALWAYS escalates on
  its FIRST run (this actor's own structural invariant, `commitledger.
  phase`), the graph NEVER reaches its `:commit` node during the
  INITIAL `record-core!` call. `:commit` is reached only on RESUME,
  i.e. HERE. Each `op/build` call constructs a fresh graph with fresh
  node closures, so the `:commit` node that actually executes on
  resume is the one THIS `op/build` call creates -- an `isic6492-
  client` threaded only into `record-core!`'s (never-reaches-:commit)
  initial call would never fire at all. (`record-core!` still threads
  it too, defensively, for the theoretical case a future phase change
  ever made `:commitment/record` auto-eligible -- see that fn's own
  docstring -- but as of this ADR, THIS is the call site that matters.)

  Who may approve: the SAME lender-identity check
  (`commitledger.edge.auth/require-lender`) `-record`/`-tranche-release`
  already use -- see `docs/adr/0003-isic6492-wiring-and-approval-
  resume.md` Decision 1 for why this option was chosen over a distinct
  operator/reviewer capability string.

  Rejects 409 if `subject` is not CURRENTLY `:interrupted` at
  `:request-approval` (no checkpoint at all, or a checkpoint whose
  status is anything else) -- never double-approve, never approve
  something already `:committed`/`:held`."
  [kv-store checkpoint-store verifier cacao-header org repo subject approved? & [isic6492-opts]]
  (pc/then
   (kv/kv-get-application kv-store subject)
   (fn [application]
     (if-not application
       (pc/resolved (auth/json-response {:ok false :error "not found" :reason (str subject " has no intake on file")} 404))
       (pc/then
        (auth/verify-cacao-header verifier cacao-header org repo)
        (fn [{:keys [ok? iss response]}]
          (if-not ok?
            (pc/resolved response)
            (let [lender-check (auth/require-lender iss application)]
              (if-not (:ok? lender-check)
                (pc/resolved (:response lender-check))
                (pc/then
                 (kvcp/load-checkpointer checkpoint-store subject)
                 (fn [checkpointer]
                   (let [latest (cp/get-latest checkpointer subject)]
                     (if-not (= :interrupted (:status latest))
                       (pc/resolved
                        (auth/json-response
                         {:ok false :error "conflict"
                          :reason (str subject " is not currently awaiting approval (no interrupted run on file)")}
                         409))
                       (pc/then
                        (kv/load-store kv-store)
                        (fn [st]
                          (let [build-opts (cond-> {:checkpointer checkpointer}
                                             (:isic6492-client isic6492-opts) (assoc :isic6492-client (:isic6492-client isic6492-opts))
                                             (:wait-until isic6492-opts) (assoc :wait-until (:wait-until isic6492-opts)))
                                actor (op/build st build-opts)
                                result (g/run* actor {:approval {:status (if approved? :approved :rejected) :by iss}}
                                               {:thread-id subject :resume? true})
                                disposition (get-in result [:state :disposition])]
                            (pc/then
                             (kv/save-store! kv-store st)
                             (fn [_]
                               (pc/then
                                (kvcp/save-checkpoint! checkpoint-store checkpointer subject)
                                (fn [_]
                                  (auth/json-response
                                   {:ok true :id subject :disposition (disposition->str disposition)}
                                   200)))))))))))))))))))))

;; ----------------------------- get by id (public) -----------------------------

(defn get-application-core
  "kv-store, id -> promise-like of `{:status int :body map}`. Public,
  read-only, no CACAO -- see ns docstring."
  [kv-store id]
  (pc/then
   (kv/kv-get-application kv-store id)
   (fn [application]
     (if-not application
       (auth/json-response {:ok false :error "not found" :reason (str id " has no intake on file")} 404)
       (auth/json-response {:ok true :application (public-view application)} 200)))))

;; ----------------------------- Cloudflare Pages Functions entry points (:cljs only) -----------------------------

#?(:cljs
   (defn- ->js-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn- params-of [context]
     (let [params (aget context "params")]
       [(aget params "org") (aget params "repo")])))

#?(:cljs
   (defn- cacao-header-of [context]
     (.get (aget (aget context "request") "headers") "authorization")))

#?(:cljs
   (defn- path-id-of
     "context -> the `[id]` PATH-SEGMENT route param, `js/decodeURIComponent`d.
     BUGFIX (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`):
     Cloudflare Pages' router does NOT decode a `%2F` inside a single
     path SEGMENT into a literal `/` (unlike a QUERY STRING value, which
     `URLSearchParams` decodes correctly -- see `subject-param` below,
     unaffected). This matters here because `gen-id` (`commitledger.edge.
     commitment-endpoints`) prefixes every id with `{org}/{repo}-`, and
     `org`/`repo` are structurally always empty strings on this repo's
     OWN live routing (no `[org]`/`[repo]` path segments exist anywhere
     in `functions/api/commitment/` -- confirmed empirically), so EVERY
     application id in practice starts with a literal `/` today. Without
     this decode, `GET /api/commitment/{id}` and `POST /api/commitment/
     {id}/approve` could never resolve ANY real id. Applies to
     `on-request-get-application` too (pre-existing, unmodified route
     shape -- this fixes both call sites)."
     [context]
     (some-> (aget (aget context "params") "id") js/decodeURIComponent)))

#?(:cljs
   (defn- body-of! [context]
     (-> (.json (aget context "request"))
         (.catch (fn [_] nil)))))

;; kotobase-persistence-migration (docs/adr/0004): `(kv/cloudflare-kv-
;; store env)` -- a synchronous constructor -- is replaced at every
;; call site below by `(kotobase/kotobase-kv-store-from-env! env)`, an
;; ASYNC constructor (minting this actor's own CACAO is; see that ns's
;; docstring). `intake-core!`/`record-core!`/`tranche-release-core!`/
;; `approve-core!`/`get-application-core` ABOVE never change -- they
;; already only depend on the `KVStore` protocol, and `KotobaseKVStore`
;; implements it -- only these `:cljs`-only entry points need to await
;; the store's construction before calling into the core fns. If
;; kotobase.net is unreachable or this actor's identity is misconfigured,
;; `kotobase-kv-store-from-env!` REJECTS, and the `.catch` below turns
;; that into a clear 500 -- there is no KV fallback path at all anymore.

#?(:cljs
   (defn on-request-post-intake [context]
     (let [env (aget context "env")
           [org repo] (params-of context)]
       (-> (js/Promise.all #js [(kotobase/kotobase-kv-store-from-env! env) (body-of! context)])
           (.then (fn [results]
                    (let [kv-store (aget results 0)
                          body (aget results 1)]
                      (if-not body
                        (auth/json-response {:ok false :error "invalid request body"} 400)
                        (intake-core! kv-store (lookup/live-lookup) (auth/live-verifier)
                                      (cacao-header-of context) org repo (js->clj body))))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn- subject-param [context]
     (let [url (js/URL. (aget (aget context "request") "url"))]
       (.get (.-searchParams url) "id"))))

#?(:cljs
   (defn- wait-until-of
     "context -> a fn of promise-like -> promise-like that ALSO registers
     the promise with `context.waitUntil` (when present -- Cloudflare
     Pages Functions provide it), so a fire-and-forget async call
     (`commitledger.operation`'s isic-6492 intake, V3) survives after the
     HTTP response returns instead of being killed with the isolate.
     Mirrors `gftdcojp/cloud-itonami`'s own `cloud_itonami.edge.hr_
     endpoints/meter-proposal!` pattern (`(when (aget context
     \"waitUntil\") (.waitUntil context p))`)."
     [context]
     (fn [p]
       (when (aget context "waitUntil") (.waitUntil context p))
       p)))

#?(:cljs
   (defn on-request-post-record [context]
     (let [env (aget context "env")
           [org repo] (params-of context)
           subject (subject-param context)
           isic6492-base-url (aget env "ISIC6492_BASE_URL")]
       (-> (if-not (seq (str subject))
             (js/Promise.resolve (auth/json-response {:ok false :error "?id= (the application id) is required"} 400))
             (.then (js/Promise.all #js [(kotobase/kotobase-kv-store-from-env! env)
                                        (isic6492/live-client-from-env env isic6492-base-url)])
                    (fn [results]
                      (let [kv-store (aget results 0)
                            client (aget results 1)]
                        (record-core! kv-store (kvcp/cloudflare-checkpoint-store env)
                                      (auth/live-verifier) (cacao-header-of context) org repo subject
                                      {:isic6492-client client :wait-until (wait-until-of context)})))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-post-tranche-release [context]
     (let [env (aget context "env")
           [org repo] (params-of context)
           subject (subject-param context)]
       (-> (if-not (seq (str subject))
             (js/Promise.resolve (auth/json-response {:ok false :error "?id= (the application id) is required"} 400))
             (.then (js/Promise.all #js [(kotobase/kotobase-kv-store-from-env! env) (body-of! context)])
                    (fn [results]
                      (let [kv-store (aget results 0)
                            body (aget results 1)]
                        (tranche-release-core! kv-store (kvcp/cloudflare-checkpoint-store env)
                                                (auth/live-verifier)
                                                (cacao-header-of context) org repo subject (js->clj (or body #js {})))))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-post-approve
     "POST /api/commitment/{id}/approve -- V3
     (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`). Body:
     `{\"approved\": bool}` (or `{\"status\": \"approved\"|\"rejected\"}`)."
     [context]
     (let [env (aget context "env")
           [org repo] (params-of context)
           id (path-id-of context)
           isic6492-base-url (aget env "ISIC6492_BASE_URL")]
       (-> (js/Promise.all #js [(kotobase/kotobase-kv-store-from-env! env)
                               (body-of! context)
                               (isic6492/live-client-from-env env isic6492-base-url)])
           (.then (fn [results]
                    (let [kv-store (aget results 0)
                          body (aget results 1)
                          client (aget results 2)
                          b (js->clj (or body #js {}))
                          approved? (boolean (or (get b "approved")
                                                 (= "approved" (get b "status"))))]
                      (approve-core! kv-store (kvcp/cloudflare-checkpoint-store env)
                                     (auth/live-verifier) (cacao-header-of context) org repo id approved?
                                     {:isic6492-client client :wait-until (wait-until-of context)}))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-get-application [context]
     (let [env (aget context "env")
           id (path-id-of context)]
       (-> (kotobase/kotobase-kv-store-from-env! env)
           (.then (fn [kv-store] (get-application-core kv-store id)))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))
