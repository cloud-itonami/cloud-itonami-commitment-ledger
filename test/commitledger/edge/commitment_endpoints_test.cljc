(ns commitledger.edge.commitment-endpoints-test
  "The 5 edge handlers' CORE request/response shape (`intake-core!`/
  `record-core!`/`tranche-release-core!`/`approve-core!`/`get-
  application-core`), using `commitledger.edge.registrylookup/mock-
  lookup` + `commitledger.edge.kv-store/mem-kv-store` + `commitledger.
  edge.kv-checkpoint/mem-checkpoint-store` + `commitledger.edge.auth/
  mock-verifier` -- no real network/Cloudflare runtime, no real crypto
  (see `commitledger.edge.auth-test`'s ns docstring for why). The
  `:cljs`-only `on-request-*` Cloudflare Pages Function entry points at
  the bottom of `commitment_endpoints.cljc` are thin adapters over
  exactly these core fns (parse `context` -> call core fn -> `->js-
  response`) and are exercised at deploy time / by `scripts/verify-edge-
  bundle`-style smoke checks, not by this JVM suite."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.auth :as auth]
            [commitledger.edge.commitment-endpoints :as ep]
            [commitledger.edge.isic6492-client :as isic6492]
            [commitledger.edge.kv-checkpoint :as kvcp]
            [commitledger.edge.kv-store :as kv]
            [commitledger.edge.registrylookup :as lookup]))

(def valid-pledge
  {:milestone-report-cadence "monthly" :mentor-checkin-commitment "biweekly"
   :progress-report-obligation "quarterly"})

(def institutional-lender-id "did:key:z6MkInstitutionalBank01")

(def intake-body
  {:borrower-org-repo "acme/ramen-cart" :borrower-did "did:key:z6MkAcmeRamenCart01"
   :requested-principal 300000 :purpose "working capital"
   :existing-debt 100000 :annual-income 3000000 :proposed-term-months 12
   :personal-pledge valid-pledge
   :lender {:lender/type :institutional :lender/id institutional-lender-id :lender/license-verified true}
   :proposed-rate 0.15 :jurisdiction "JPN" :tranche-schedule [300000]})

(defn- verifier-for [iss resources] (auth/mock-verifier (fn [_] {:valid? true :iss iss :resources resources})))

;; ----------------------------- intake -----------------------------

(deftest intake-requires-borrower-resource-scope
  (testing "no Authorization header at all -> 401"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          v (verifier-for "did:key:zBorrower" ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status]} (ep/intake-core! kvs l v nil "acme" "ramen-cart" intake-body)]
      (is (= 401 status))))
  (testing "resources scoped to a DIFFERENT org/repo -> 403, never reaches the lookup/KV"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          v (verifier-for "did:key:zBorrower" ["kotoba://itonami/other/repo"])
          {:keys [status]} (ep/intake-core! kvs l v "CACAO abc" "acme" "ramen-cart" intake-body)]
      (is (= 403 status))
      (is (= [] (kv/kv-list-ids kvs))))))

(deftest intake-happy-path-stores-application-with-live-verified-true
  (let [kvs (kv/mem-kv-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        v (verifier-for "did:key:zBorrower" ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status body]} (ep/intake-core! kvs l v "CACAO abc" "acme" "ramen-cart" intake-body)]
    (is (= 201 status))
    (is (true? (:ok body)))
    (is (string? (:id body)))
    (is (true? (:borrowerRegistrationVerified body)))
    (let [stored (kv/kv-get-application kvs (:id body))]
      (is (true? (:borrower-registration-verified? stored)))
      (is (= "acme/ramen-cart" (:borrower-org-repo stored)))
      (is (= :intake (:status stored))))))

(deftest intake-honestly-records-unverified-when-lookup-says-so
  (testing "the live lookup returning false is stored as false, never silently upgraded"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {}) ;; nothing claimed -- fail-closed
          v (verifier-for "did:key:zBorrower" ["kotoba://itonami/acme/ramen-cart"])
          {:keys [body]} (ep/intake-core! kvs l v "CACAO abc" "acme" "ramen-cart" intake-body)
          stored (kv/kv-get-application kvs (:id body))]
      (is (false? (:borrower-registration-verified? stored))))))

(deftest intake-registry-lookup-uses-the-applications-own-borrower-org-repo-not-url-params
  (testing "docs/adr/0003-isic6492-wiring-and-approval-resume.md bugfix: the registry lookup
            queries the BODY's :borrower-org-repo, not the (on live infra, always-nil) URL
            org/repo route params -- proven here by passing DIFFERENT url-org/url-repo than the
            body's actual borrowerOrgRepo and confirming the lookup still resolves correctly"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          v (verifier-for "did:key:zBorrower" ["kotoba://itonami/url-org/url-repo"])
          body (assoc intake-body :borrower-org-repo "acme/ramen-cart")
          {:keys [status body]} (ep/intake-core! kvs l v "CACAO abc" "url-org" "url-repo" body)]
      (is (= 201 status))
      (is (true? (:borrowerRegistrationVerified body))
          "resolved true even though url-org/url-repo ('url-org/url-repo') never appear in the
           mock-lookup's answers -- proves the lookup used the body's borrower-org-repo instead"))))

(deftest intake-registry-lookup-fails-closed-on-malformed-borrower-org-repo
  (let [kvs (kv/mem-kv-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        v (verifier-for "did:key:zBorrower" ["kotoba://itonami/acme/ramen-cart"])
        body (assoc intake-body :borrower-org-repo "not-a-slash-shaped-value")
        {:keys [body]} (ep/intake-core! kvs l v "CACAO abc" "acme" "ramen-cart" body)]
    (is (false? (:borrowerRegistrationVerified body)))))

(deftest intake-decodes-a-REAL-wire-JSON-lender-and-pledge-shape
  (testing "docs/adr/0003-isic6492-wiring-and-approval-resume.md bugfix: a REAL HTTP caller's
            JSON body has STRING keys throughout (camelCase, no namespaced keywords possible on
            the wire) -- parse-intake-body must decode :lender/:personal-pledge into the exact
            namespaced-keyword / kebab-case shape commitledger.store/commitledger.governor/
            commitledger.edge.auth/require-lender all expect, not just select the top-level key.
            Confirmed empirically this was previously broken (:lender/id always nil for any REAL
            caller, unconditionally rejecting every live :commitment/record) -- this test
            constructs the body the way js->clj ACTUALLY produces it (string keys all the way
            down), not the pre-shaped CLJS map every other test fixture in this file uses."
    (let [wire-lender {"type" "individual" "id" "did:key:zRealWireLender" "licenseVerified" false}
          wire-pledge {"milestoneReportCadence" "monthly"
                       "mentorCheckinCommitment" "biweekly check-in"
                       "progressReportObligation" "quarterly report"}
          wire-body (-> intake-body (assoc :lender wire-lender) (assoc :personal-pledge wire-pledge))
          kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          v (verifier-for "did:key:zBorrower" ["kotoba://itonami/acme/ramen-cart"])
          {:keys [body]} (ep/intake-core! kvs l v "CACAO abc" "acme" "ramen-cart" wire-body)
          stored (kv/kv-get-application kvs (:id body))]
      (is (= :individual (get-in stored [:lender :lender/type])))
      (is (= "did:key:zRealWireLender" (get-in stored [:lender :lender/id])))
      (is (false? (get-in stored [:lender :lender/license-verified])))
      (is (= "monthly" (:milestone-report-cadence (:personal-pledge stored))))
      (is (= "biweekly check-in" (:mentor-checkin-commitment (:personal-pledge stored))))
      (is (= "quarterly report" (:progress-report-obligation (:personal-pledge stored)))))))

;; ----------------------------- record -----------------------------

(defn- intake! [kvs l id-org id-repo]
  (let [v (verifier-for "did:key:zBorrower" [(str "kotoba://itonami/" id-org "/" id-repo)])
        {:keys [body]} (ep/intake-core! kvs l v "CACAO abc" id-org id-repo intake-body)]
    (:id body)))

(deftest record-requires-lender-identity-match
  (testing "a caller whose CACAO iss does not match the application's :lender/id is forbidden"
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          impostor (verifier-for "did:key:zNotTheLender" ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status]} (ep/record-core! kvs cps impostor "CACAO abc" "acme" "ramen-cart" id)]
      (is (= 403 status)))))

(deftest record-unknown-application-is-404
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" "no-such-id")]
    (is (= 404 status))))

(deftest record-happy-path-escalates-to-request-approval-never-auto-commits
  (testing "governor-clean + verified registration -> the actor still ALWAYS pauses for human
            approval (neither actuation is ever auto-eligible, at any phase) -- this endpoint
            reports that honestly as request-approval and does not fabricate a commit"
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status body]} (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)]
      (is (true? (:ok body)))
      (is (= "request-approval" (:disposition body)))
      (is (= 202 status))
      (is (= :intake (:status (kv/kv-get-application kvs id))) "nothing committed yet"))))

(deftest record-holds-when-borrower-registration-not-verified
  (testing "check 13 fires through the FULL HTTP path when the live lookup said unverified"
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {}) ;; unverified
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          {:keys [body]} (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)]
      (is (= "hold" (:disposition body))))))

;; ----------------------------- tranche-release -----------------------------

(deftest tranche-release-requires-lender-identity-match
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        impostor (verifier-for "did:key:zNotTheLender" ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/tranche-release-core! kvs cps impostor "CACAO abc" "acme" "ramen-cart" id
                                                    {:tranche-index 0 :milestone-evidence {:milestone-met? true :evidence "n/a"}})]
    (is (= 403 status))))

(deftest tranche-release-before-record-is-held
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        {:keys [body]} (ep/tranche-release-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id
                                                  {:tranche-index 0 :milestone-evidence {:milestone-met? true :evidence "n/a"}})]
    (is (= "hold" (:disposition body)))))

;; ----------------------------- approve (V3, resumes an interrupted run) -----------------------------

(deftest approve-requires-lender-identity-match
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        _ (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)
        impostor (verifier-for "did:key:zNotTheLender" ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/approve-core! kvs cps impostor "CACAO abc" "acme" "ramen-cart" id true)]
    (is (= 403 status))))

(deftest approve-unknown-application-is-404
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" "no-such-id" true)]
    (is (= 404 status))))

(deftest approve-without-a-prior-interrupted-run-is-409
  (testing "an application that was only intake'd (never :commitment/record'd) has no interrupted
            run to resume -- never double-approve, never approve something not awaiting approval"
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status body]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id true)]
      (is (= 409 status))
      (is (false? (:ok body))))))

(deftest approve-true-resumes-and-commits
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        record-resp (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)
        _ (is (= "request-approval" (:disposition (:body record-resp))) "sanity: it actually interrupted first")
        {:keys [status body]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id true)]
    (is (= 200 status))
    (is (true? (:ok body)))
    (is (= "commit" (:disposition body)))
    (is (= :committed (:status (kv/kv-get-application kvs id))))))

(deftest approve-false-resumes-and-holds
  (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        _ (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)
        {:keys [status body]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id false)]
    (is (= 200 status))
    (is (= "hold" (:disposition body)))
    (is (not= :committed (:status (kv/kv-get-application kvs id))))))

(deftest approve-true-fires-the-isic6492-client-because-commit-only-happens-on-resume
  (testing "docs/adr/0003-isic6492-wiring-and-approval-resume.md: :commitment/record ALWAYS
            escalates on its first run, so :commit -- and therefore the isic-6492 fire-and-forget
            call -- is only ever reached on approve-core!'s RESUME, never on record-core!'s
            initial call. isic6492-opts must be threaded into approve-core!'s own op/build, not
            only record-core!'s (a real bug this test regression-guards)."
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          client (isic6492/always-ok-client "isic6492-live-app-1")
          _ (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id {:isic6492-client client})
          {:keys [status body]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id true
                                                   {:isic6492-client client})
          isic-facts (filter #(= :isic6492-intake-attempted (:t %))
                             (:ledger (kv/kv-get-ledger-state kvs)))]
      (is (= 200 status))
      (is (= "commit" (:disposition body)))
      (is (= 1 (count isic-facts))
          "proves the isic6492-client injected into approve-core! (not just record-core!) actually
           fired -- record-core!'s own call never reaches :commit, so if this fact is present it
           could only have come from approve-core!'s resume"))))

(deftest approve-twice-is-rejected-with-409
  (testing "the SECOND approve call sees status :done (not :interrupted) and is rejected -- never double-approve"
    (let [kvs (kv/mem-kv-store) cps (kvcp/mem-checkpoint-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          _ (ep/record-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id)
          _ (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id true)
          {:keys [status]} (ep/approve-core! kvs cps v "CACAO abc" "acme" "ramen-cart" id true)]
      (is (= 409 status)))))

;; ----------------------------- get-application (public) -----------------------------

(deftest get-application-unknown-id-is-404
  (let [kvs (kv/mem-kv-store)
        {:keys [status]} (ep/get-application-core kvs "no-such-id")]
    (is (= 404 status))))

(deftest get-application-redacts-lender-id-and-borrower-did
  (let [kvs (kv/mem-kv-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        {:keys [status body]} (ep/get-application-core kvs id)]
    (is (= 200 status))
    (is (true? (:ok body)))
    (is (nil? (get-in body [:application :lender :lender/id])))
    (is (nil? (get-in body [:application :borrower-did])))
    (is (= "acme/ramen-cart" (get-in body [:application :borrower-org-repo])) "public fields still present")
    (is (= :institutional (get-in body [:application :lender :lender/type])) "lender TYPE is public, only the id is redacted")))

;; ----------------------------- public-view (pure) -----------------------------

(deftest public-view-strips-only-the-two-documented-fields
  (let [app {:id "x" :borrower-did "did:key:zBorrower" :lender {:lender/id "did:key:zLender" :lender/type :institutional}}
        v (ep/public-view app)]
    (is (not (contains? v :borrower-did)))
    (is (not (contains? (:lender v) :lender/id)))
    (is (= :institutional (get-in v [:lender :lender/type])))
    (is (= "x" (:id v)))))
