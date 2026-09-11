(ns commitledger.edge.kv-store-test
  "`commitledger.edge.kv-store`'s `MemKVStore` (an in-memory KV stub, no
  real network/Cloudflare runtime needed) plus the `load-store`/`save-
  store!` <-> `commitledger.store` rehydration boundary. The real
  `CloudflareKVStore` is `:cljs`-only KV-binding interop, exercised at
  deploy time, not by this suite -- see `commitledger.edge.auth-test`'s
  ns docstring for the same reasoning applied to CACAO verify."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.kv-store :as kv]
            [commitledger.store :as store]))

(def sample-app
  {:id "acme/ramen-cart-abc123" :borrower-org-repo "acme/ramen-cart"
   :borrower-did "did:key:z6MkAcmeRamenCart01" :requested-principal 300000
   :purpose "working capital" :existing-debt 100000 :annual-income 3000000
   :proposed-term-months 12
   :personal-pledge {:milestone-report-cadence "monthly"
                      :mentor-checkin-commitment "biweekly"
                      :progress-report-obligation "quarterly"}
   :lender {:lender/type :institutional :lender/id "did:key:z6MkInstitutionalBank01" :lender/license-verified true}
   :proposed-rate 0.15 :jurisdiction "JPN" :status :intake
   :tranche-schedule [300000] :borrower-registration-verified? true})

(deftest put-then-get-round-trips
  (let [kvs (kv/mem-kv-store)]
    (kv/kv-put-application! kvs (:id sample-app) sample-app)
    (is (= sample-app (kv/kv-get-application kvs (:id sample-app))))))

(deftest get-missing-id-is-nil
  (let [kvs (kv/mem-kv-store)]
    (is (nil? (kv/kv-get-application kvs "no-such-id")))))

(deftest list-ids-tracks-every-put-once
  (let [kvs (kv/mem-kv-store)]
    (kv/kv-put-application! kvs "a" (assoc sample-app :id "a"))
    (kv/kv-put-application! kvs "b" (assoc sample-app :id "b"))
    (kv/kv-put-application! kvs "a" (assoc sample-app :id "a" :requested-principal 999))
    (is (= #{"a" "b"} (set (kv/kv-list-ids kvs))))
    (is (= 999 (:requested-principal (kv/kv-get-application kvs "a"))) "put again overwrites, doesn't duplicate")))

(deftest ledger-state-defaults-to-empty
  (let [kvs (kv/mem-kv-store)]
    (is (= {} (kv/kv-get-ledger-state kvs)))))

(deftest ledger-state-round-trips
  (let [kvs (kv/mem-kv-store)
        state {:ledger [{:t :committed :op :application/intake :disposition :commit}]
               :commitment-history [] :tranche-release-history []
               :released-tranches {} :commitment-sequences {"JPN" 1} :tranche-sequences {}}]
    (kv/kv-put-ledger-state! kvs state)
    (is (= state (kv/kv-get-ledger-state kvs)))))

;; ----------------------------- load-store / save-store! -----------------------------

(deftest load-store-with-no-applications-is-a-fresh-empty-store
  (let [kvs (kv/mem-kv-store)
        st (kv/load-store kvs)]
    (is (= [] (store/all-applications st)))
    (is (= [] (store/ledger st)))))

(deftest load-store-reconstructs-applications-and-cross-application-state
  (let [kvs (kv/mem-kv-store)]
    (kv/kv-put-application! kvs (:id sample-app) sample-app)
    (kv/kv-put-ledger-state! kvs {:commitment-sequences {"JPN" 3}})
    (let [st (kv/load-store kvs)]
      (is (= sample-app (store/application st (:id sample-app))))
      (is (= 3 (store/next-commitment-sequence st "JPN"))))))

(deftest save-store-persists-mutations-back-to-kv
  (let [kvs (kv/mem-kv-store)
        st (store/empty-store {:applications {(:id sample-app) sample-app}})]
    ;; simulate the actor graph committing this application
    (store/commit-record! st {:effect :commitment/mark-recorded :path [(:id sample-app)]})
    (kv/save-store! kvs st)
    (is (= :committed (:status (kv/kv-get-application kvs (:id sample-app)))))
    (is (= 1 (count (:commitment-history (kv/kv-get-ledger-state kvs)))))
    (is (= 1 (get (:commitment-sequences (kv/kv-get-ledger-state kvs)) "JPN")))))

(deftest save-then-load-is-idempotent-for-cross-application-lender-count
  (testing "the check-6 ground truth (individual-lender-commitment-count) survives a save/load cycle"
    (let [kvs (kv/mem-kv-store)
          lender-id "did:key:z6MkIndividualLenderRepeat"
          app (fn [id] (-> sample-app (assoc :id id)
                           (assoc :lender {:lender/type :individual :lender/id lender-id :lender/license-verified false})
                           (assoc :proposed-rate 0.0 :requested-principal 50000)))
          st (store/empty-store {:applications {"a" (app "a") "b" (app "b")}})]
      (store/commit-record! st {:effect :commitment/mark-recorded :path ["a"]})
      (store/commit-record! st {:effect :commitment/mark-recorded :path ["b"]})
      (kv/save-store! kvs st)
      (let [st2 (kv/load-store kvs)]
        (is (= 2 (store/individual-lender-commitment-count st2 lender-id)))))))
