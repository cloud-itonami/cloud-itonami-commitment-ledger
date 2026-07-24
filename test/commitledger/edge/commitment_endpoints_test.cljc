(ns commitledger.edge.commitment-endpoints-test
  "The 4 edge handlers' CORE request/response shape (`intake-core!`/
  `record-core!`/`tranche-release-core!`/`get-application-core`), using
  `commitledger.edge.registrylookup/mock-lookup` + `commitledger.edge.
  kv-store/mem-kv-store` + `commitledger.edge.auth/mock-verifier` -- no
  real network/Cloudflare runtime, no real crypto (see `commitledger.
  edge.auth-test`'s ns docstring for why). The `:cljs`-only `on-request-
  *` Cloudflare Pages Function entry points at the bottom of `commitment
  _endpoints.cljc` are thin adapters over exactly these core fns (parse
  `context` -> call core fn -> `->js-response`) and are exercised at
  deploy time / by `scripts/verify-edge-bundle`-style smoke checks, not
  by this JVM suite."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.auth :as auth]
            [commitledger.edge.commitment-endpoints :as ep]
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

;; ----------------------------- record -----------------------------

(defn- intake! [kvs l id-org id-repo]
  (let [v (verifier-for "did:key:zBorrower" [(str "kotoba://itonami/" id-org "/" id-repo)])
        {:keys [body]} (ep/intake-core! kvs l v "CACAO abc" id-org id-repo intake-body)]
    (:id body)))

(deftest record-requires-lender-identity-match
  (testing "a caller whose CACAO iss does not match the application's :lender/id is forbidden"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          impostor (verifier-for "did:key:zNotTheLender" ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status]} (ep/record-core! kvs impostor "CACAO abc" "acme" "ramen-cart" id)]
      (is (= 403 status)))))

(deftest record-unknown-application-is-404
  (let [kvs (kv/mem-kv-store)
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/record-core! kvs v "CACAO abc" "acme" "ramen-cart" "no-such-id")]
    (is (= 404 status))))

(deftest record-happy-path-escalates-to-request-approval-never-auto-commits
  (testing "governor-clean + verified registration -> the actor still ALWAYS pauses for human
            approval (neither actuation is ever auto-eligible, at any phase) -- this endpoint
            reports that honestly as request-approval and does not fabricate a commit"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {"acme/ramen-cart" true})
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          {:keys [status body]} (ep/record-core! kvs v "CACAO abc" "acme" "ramen-cart" id)]
      (is (true? (:ok body)))
      (is (= "request-approval" (:disposition body)))
      (is (= 202 status))
      (is (= :intake (:status (kv/kv-get-application kvs id))) "nothing committed yet"))))

(deftest record-holds-when-borrower-registration-not-verified
  (testing "check 13 fires through the FULL HTTP path when the live lookup said unverified"
    (let [kvs (kv/mem-kv-store)
          l (lookup/mock-lookup {}) ;; unverified
          id (intake! kvs l "acme" "ramen-cart")
          v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
          {:keys [body]} (ep/record-core! kvs v "CACAO abc" "acme" "ramen-cart" id)]
      (is (= "hold" (:disposition body))))))

;; ----------------------------- tranche-release -----------------------------

(deftest tranche-release-requires-lender-identity-match
  (let [kvs (kv/mem-kv-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        impostor (verifier-for "did:key:zNotTheLender" ["kotoba://itonami/acme/ramen-cart"])
        {:keys [status]} (ep/tranche-release-core! kvs impostor "CACAO abc" "acme" "ramen-cart" id
                                                    {:tranche-index 0 :milestone-evidence {:milestone-met? true :evidence "n/a"}})]
    (is (= 403 status))))

(deftest tranche-release-before-record-is-held
  (let [kvs (kv/mem-kv-store)
        l (lookup/mock-lookup {"acme/ramen-cart" true})
        id (intake! kvs l "acme" "ramen-cart")
        v (verifier-for institutional-lender-id ["kotoba://itonami/acme/ramen-cart"])
        {:keys [body]} (ep/tranche-release-core! kvs v "CACAO abc" "acme" "ramen-cart" id
                                                  {:tranche-index 0 :milestone-evidence {:milestone-met? true :evidence "n/a"}})]
    (is (= "hold" (:disposition body)))))

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
