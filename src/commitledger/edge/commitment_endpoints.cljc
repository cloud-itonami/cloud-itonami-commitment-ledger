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

  `:commitment/record`/`:commitment/tranche-release` run the EXISTING,
  UNMODIFIED `commitledger.operation/build` StateGraph -- this ns never
  reimplements Governor/Phase logic, it only wires HTTP <-> the actor.
  Per the actor's own structural invariant (`commitledger.phase`'s ns
  docstring: neither actuation is EVER auto-eligible, at any phase),
  running the graph against a governor-clean proposal for either
  actuation ALWAYS pauses at `:request-approval` (`g/run*` returns
  `:status :interrupted`) -- this V2 HTTP surface exposes that as
  disposition `\"request-approval\"` and stops there; it does NOT
  implement the human-approval RESUME step (that needs a durable,
  authenticated operator UI/API, explicitly deferred -- see the ADR's
  Consequences). `on-request-post-record`/`-tranche-release` therefore
  never actually commit a record via this endpoint alone -- by design,
  matching the actor's own \"never silently auto-commit past
  :request-approval\" invariant, not a bug."
  (:require [commitledger.edge.auth :as auth]
            [commitledger.edge.kv-store :as kv]
            [commitledger.edge.pcompat :as pc]
            [commitledger.edge.registrylookup :as lookup]
            [commitledger.operation :as op]
            [commitledger.phase :as phase]
            [commitledger.store :as store]
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
   :personal-pledge (or (get body :personal-pledge) (get body "personalPledge"))
   :lender (or (get body :lender) (get body "lender"))
   :proposed-rate (or (get body :proposed-rate) (get body "proposedRate"))
   :jurisdiction (or (get body :jurisdiction) (get body "jurisdiction"))
   :tranche-schedule (or (get body :tranche-schedule) (get body "trancheSchedule"))})

(defn intake-core!
  "kv-store, lookup, verifier, cacao-header, org, repo, body ->
  promise-like of `{:status int :body map}`. CACAO-gated on the CALLER's
  resources including `kotoba://itonami/{org}/{repo}` -- only the actual
  business owner may submit an application for their own business
  (mirrors `cloud_itonami.edge.register`'s own claim-scope check). Once
  authorized: builds the application, runs the LIVE registry lookup
  (check 13's ground truth), persists it, returns `{\"ok\": true, \"id\":
  ...}` (201). Never runs the Governor itself (intake is not a
  HARD-checked op; the Governor's 11 `:commitment/record` checks,
  including 13, run when `:commitment/record` is later requested -- see
  `commitledger.governor`)."
  [kv-store lookup verifier cacao-header org repo body]
  (pc/then
   (auth/verify-cacao-header verifier cacao-header org repo)
   (fn [{:keys [ok? response]}]
     (if-not ok?
       (pc/resolved response)
       (let [id (gen-id org repo)
             base (parse-intake-body body)]
         (pc/then
          (lookup/check-registration lookup org repo)
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
  "kv-store, subject-id, request-map -> promise-like of
  `{:status int :body map}`. Loads the FULL cross-application store from
  KV (`commitledger.edge.kv-store/load-store`), runs `commitledger.
  operation/build`'s UNMODIFIED StateGraph for `request-map`'s `:op`
  against it (phase 3, `commitledger.advisor/mock-advisor` -- see ns
  docstring for why V2 does not wire an LLM advisor here), persists
  whatever the graph mutated back to KV, and reports the resulting
  disposition. Shared by `on-request-post-record`/`-tranche-release`."
  [kv-store subject request-map]
  (pc/then
   (kv/load-store kv-store)
   (fn [st]
     (if-not (store/application st subject)
       (pc/resolved (auth/json-response {:ok false :error "not found" :reason (str subject " has no intake on file")} 404))
       (let [actor (op/build st)
             context {:actor-id "commitledger-edge" :actor-role :platform-operator :phase phase/default-phase}
             result (g/run* actor {:request request-map :context context} {:thread-id subject})
             disposition (cond
                           (= :interrupted (:status result)) :escalate
                           :else (get-in result [:state :disposition]))]
         (pc/then
          (kv/save-store! kv-store st)
          (fn [_]
            (auth/json-response
             {:ok true :id subject :disposition (disposition->str disposition)}
             (if (= :escalate disposition) 202 200)))))))))

(defn record-core!
  "kv-store, verifier, cacao-header, org, repo, subject -> promise-like
  of `{:status int :body map}`. CACAO-gated on the caller's `iss`
  matching the STORED application's own `:lender/id`
  (`commitledger.edge.auth/require-lender`) -- resources scope is
  irrelevant here (this is a LENDER identity check, not a borrower
  tenant-ownership check like intake's), so this loads the application
  first, then verifies."
  [kv-store verifier cacao-header org repo subject]
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
                (run-graph! kv-store subject {:op :commitment/record :subject subject}))))))))))

(defn tranche-release-core!
  "Like `record-core!`, plus `:tranche-index`/`:milestone-evidence` from
  the request body -- same lender-identity gate (the lender who recorded
  the commitment is the one authorizing tranche release)."
  [kv-store verifier cacao-header org repo subject body]
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
                  (run-graph! kv-store subject
                              {:op :commitment/tranche-release :subject subject
                               :tranche-index tranche-index :milestone-evidence milestone-evidence})))))))))))

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
   (defn- body-of! [context]
     (-> (.json (aget context "request"))
         (.catch (fn [_] nil)))))

#?(:cljs
   (defn on-request-post-intake [context]
     (let [env (aget context "env")
           [org repo] (params-of context)]
       (-> (body-of! context)
           (.then (fn [body]
                    (if-not body
                      (auth/json-response {:ok false :error "invalid request body"} 400)
                      (intake-core! (kv/cloudflare-kv-store env) (lookup/live-lookup) (auth/live-verifier)
                                    (cacao-header-of context) org repo (js->clj body)))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn- subject-param [context]
     (let [url (js/URL. (aget (aget context "request") "url"))]
       (.get (.-searchParams url) "id"))))

#?(:cljs
   (defn on-request-post-record [context]
     (let [env (aget context "env")
           [org repo] (params-of context)
           subject (subject-param context)]
       (-> (if-not (seq (str subject))
             (js/Promise.resolve (auth/json-response {:ok false :error "?id= (the application id) is required"} 400))
             (record-core! (kv/cloudflare-kv-store env) (auth/live-verifier) (cacao-header-of context) org repo subject))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-post-tranche-release [context]
     (let [env (aget context "env")
           [org repo] (params-of context)
           subject (subject-param context)]
       (-> (if-not (seq (str subject))
             (js/Promise.resolve (auth/json-response {:ok false :error "?id= (the application id) is required"} 400))
             (.then (body-of! context)
                    (fn [body]
                      (tranche-release-core! (kv/cloudflare-kv-store env) (auth/live-verifier)
                                              (cacao-header-of context) org repo subject (js->clj (or body #js {}))))))
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))

#?(:cljs
   (defn on-request-get-application [context]
     (let [env (aget context "env")
           id (aget (aget context "params") "id")]
       (-> (get-application-core (kv/cloudflare-kv-store env) id)
           (.then ->js-response)
           (.catch (fn [e] (->js-response (auth/json-response {:ok false :error "request failed" :reason (ex-message e)} 500))))))))
