(ns commitledger.edge.kotobase-store-test
  "Mock-based proof that `commitledger.edge.kotobase-store`'s injected
  `:db-api` genuinely satisfies the `{:q :transact! :db :pull :entid}`
  shape `langchain.db`/`langchain.kotoba-db` expect, AND that
  `KotobaseKVStore` (the `commitledger.edge.kv-store/KVStore` protocol
  implementation this ns adds) round-trips correctly, AND that the
  EXISTING, UNCHANGED `commitledger.edge.kv-store/load-store`/`save-
  store!` work against it transparently -- all end-to-end against a
  fake kotobase.net.

  Runs entirely on `:clj` (`clojure -M:dev:test`) via `commitledger.
  edge.kotobase-http/resolved-mock-http-fn` -- no real network, no real
  crypto (`mint-cacao!` here is a plain counting stub, not `commitledger.
  edge.kotobase-identity`'s real CACAO mint, which is CLJS-only real
  Ed25519 -- see that ns's own docstring for why, and `commitledger.edge.
  cacao-mint-test`'s established precedent for the same platform split).
  `commitledger.edge.pcompat/then`'s `:clj` branch is `(f p)` --
  synchronous -- so every assertion below runs directly, no promise
  machinery needed on this platform. `commitledger.edge.kotobase-store/
  json-write`'s `:clj` branch is `pr-str` (see that ns's own docstring:
  its `:clj` branch only ever needs to round-trip through THIS ns's own
  fake backend below, not real kotobase.net JSON), so the fake backend's
  parsed request bodies below carry ordinary Clojure KEYWORD keys
  (`:tx_edn`/`:query_edn`/`:entity`/...), not JSON string keys."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [commitledger.edge.kotobase-http :as khttp]
            [commitledger.edge.kotobase-store :as ks]
            [commitledger.edge.kv-store :as kv]
            [commitledger.edge.pcompat :as pc]
            [commitledger.store :as store]))

;; ───────── a fake kotobase.net XRPC backend ─────────────────────────────────
;;
;; Mirrors `langchain/test/langchain/kotoba_db_test.cljc`'s own
;; `mock-caps` shape (nsid + parsed-body -> canned response), applied one
;; layer up (through `commitledger.edge.kotobase-http/resolved-mock-
;; http-fn`, which wraps a plain respond-fn in `pc/resolved` so it
;; satisfies `kotoba-api-async`'s async contract).

(defn- nsid-from-url [url] (last (str/split url #"/xrpc/")))

(defn- q-projection
  "Which 2 attrs a `:find ?a ?b`-shaped scan query (no `:in`, matches
  every entity of a kind directly -- see `commitledger.store/ledger-
  state`'s own docstring for why these particular queries need no
  jurisdiction/application-id bound up front) is asking for, inferred
  from the `:find` var NAMES (this fake backend's own simplification --
  the real kotobase-server infers nothing, it just runs the Datalog;
  this stands in for that)."
  [find-spec seq-entities]
  (case (str (vec find-spec))
    "[?s ?f]" [:ledger/seq :ledger/fact]
    "[?s ?r]" (if (some #(contains? % :commitment/seq) seq-entities)
                [:commitment/seq :commitment/record]
                [:tranche/seq :tranche/record])
    "[?aid ?idx]" [:released-tranche/application-id :released-tranche/tranche-index]
    "[?j ?n]" (if (some #(contains? % :commit-sequence/jurisdiction) seq-entities)
                [:commit-sequence/jurisdiction :commit-sequence/next]
                [:tranche-sequence/jurisdiction :tranche-sequence/next])
    nil))

(defn- fake-kotobase
  "An atom-backed fake kotobase.net graph: `:application/id`-keyed
  entities in a plain map, everything else (ledger/commitment/tranche/
  released-tranche/sequence entities) in a vector (so multiple distinct
  string-seq values don't collide the way the REAL numeric-identity bug
  this migration fixed would -- this fake backend has no such bug
  either way, since it is keyed by Clojure equality, not kotobase-
  server's own entity-id derivation; ONLY `commitledger.store-numeric-
  identity-test` proves the fix itself). Returns `{:http-fn :nsid-log}`
  -- `nsid-log` records every XRPC nsid this ns's kotobase-store
  actually hits, for the shape-compliance assertions below."
  []
  (let [entities (atom {})
        seq-entities (atom [])
        nsid-log (atom [])]
    {:http-fn
     (khttp/resolved-mock-http-fn
      (fn [{:keys [url body]}]
        (let [nsid (nsid-from-url url)
              parsed (edn/read-string body)]
          (swap! nsid-log conj nsid)
          (case nsid
            "ai.gftd.apps.kotobase.datomic.transact"
            (let [tx (edn/read-string (:tx_edn parsed))]
              (doseq [m tx]
                (if-let [aid (:application/id m)]
                  (swap! entities assoc aid m)
                  (swap! seq-entities conj m)))
              {:status 200 :body (pr-str {:ok true})})

            "ai.gftd.apps.kotobase.datomic.q"
            (let [query (edn/read-string (:query_edn parsed))
                  find-spec (:find query)]
              (if (= find-spec '[[?id ...]])
                {:status 200 :body (pr-str {:rows_edn (mapv (fn [id] [(pr-str id)]) (keys @entities))})}
                (let [[a1 a2] (q-projection find-spec @seq-entities)
                      rows (if a1
                             (keep (fn [m] (when (contains? m a1) [(pr-str (get m a1)) (pr-str (get m a2))])) @seq-entities)
                             [])]
                  {:status 200 :body (pr-str {:rows_edn (vec rows)})})))

            "ai.gftd.apps.kotobase.datomic.pull"
            (let [eid (:entity parsed)
                  m (get @entities eid)]
              {:status 200
               :body (pr-str {:result_edn (pr-str (into {} (map (fn [[k v]] [(pr-str k) #{(if (string? v) v (pr-str v))}])) m))})})

            {:status 404 :body (pr-str {:error (str "no fake handler for " nsid)})})))
      )
     :nsid-log nsid-log}))

(defn- counting-mint-cacao! [call-log]
  (fn [op]
    (swap! call-log conj op)
    (pc/resolved (str "fake-cacao-for-" op))))

(defn- test-remote-store []
  (let [{:keys [http-fn nsid-log]} (fake-kotobase)
        mint-log (atom [])
        s (ks/kotobase-store {:http-fn http-fn :did "did:key:z6MkFakeActor01"
                              :db-name "commitment-ledger-test"
                              :mint-cacao! (counting-mint-cacao! mint-log)})]
    {:store s :nsid-log nsid-log :mint-log mint-log}))

(def ^:private demo-app
  {:id "app-x" :borrower-org-repo "acme/x" :borrower-did "did:key:z6MkX01"
   :requested-principal 100000 :purpose "n" :existing-debt 0 :annual-income 1000000
   :proposed-term-months 6
   :personal-pledge {:milestone-report-cadence "monthly" :mentor-checkin-commitment "biweekly"
                     :progress-report-obligation "quarterly"}
   :lender {:lender/type :institutional :lender/id "did:key:z6MkLenderX01" :lender/license-verified true}
   :proposed-rate 0.1 :jurisdiction "JPN" :status :intake :tranche-schedule [100000]})

;; ─── db-api shape compliance ────────────────────────────────────────────────

(deftest kotobase-store-satisfies-store-protocol-round-trip
  (let [{:keys [store]} (test-remote-store)]
    (testing "empty graph reads back nil/empty"
      (is (nil? (store/application store "nope")))
      (is (= [] (store/all-applications store))))
    (testing "write then read round-trips a real application, via genuine :transact!/:q/:pull calls"
      (store/with-applications store {"app-x" demo-app})
      (is (= "acme/x" (:borrower-org-repo (store/application store "app-x"))))
      (is (= 1 (count (store/all-applications store))))
      (is (= "app-x" (:id (first (store/all-applications store))))))))

(deftest read-cacao-minted-once-write-cacao-minted-fresh-per-transact
  (let [{:keys [store mint-log]} (test-remote-store)]
    (is (= ["datom:read"] @mint-log) "constructing the store mints exactly one shared read CACAO, before any op runs")
    (store/with-applications store {"app-x" demo-app})
    (is (= ["datom:read" "datom:transact"] @mint-log)
        "the first :transact! (with-applications call) mints a FRESH write CACAO")
    (store/with-applications store {"app-y" (assoc demo-app :id "app-y")})
    (is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log)
        "each :transact! (with-applications call) mints a FRESH write CACAO -- kotobase-server's nonce-replay guard 401s a reused one")
    (store/application store "app-x")
    (is (= ["datom:read" "datom:transact" "datom:transact"] @mint-log)
        "reads reuse the single shared read CACAO -- no new mint per read")))

(deftest transact-and-q-and-pull-hit-the-real-kotobase-xrpc-nsids
  (let [{:keys [store nsid-log]} (test-remote-store)]
    (store/with-applications store {"app-x" demo-app})
    (store/all-applications store)
    (is (some #{"ai.gftd.apps.kotobase.datomic.transact"} @nsid-log))
    (is (some #{"ai.gftd.apps.kotobase.datomic.q"} @nsid-log))
    (is (some #{"ai.gftd.apps.kotobase.datomic.pull"} @nsid-log))))

;; ─── KotobaseKVStore satisfies KVStore, so kv-store/load-store & save-store!
;; (the EXISTING, UNCHANGED request-scoped boundary commitment-endpoints.cljc
;; already calls) work against it transparently -- no new load/save API. ────

(deftest kotobase-kv-store-satisfies-kv-store-protocol-round-trip
  (let [{:keys [store]} (test-remote-store)
        kvs (ks/kotobase-kv-store store)]
    (testing "empty graph reads back nil/empty"
      (is (nil? (kv/kv-get-application kvs "nope")))
      (is (= [] (kv/kv-list-ids kvs))))
    (testing "write then read round-trips a real application"
      (kv/kv-put-application! kvs "app-x" demo-app)
      (is (= "acme/x" (:borrower-org-repo (kv/kv-get-application kvs "app-x"))))
      (is (= ["app-x"] (kv/kv-list-ids kvs))))
    (testing "ledger-state round-trips too"
      (kv/kv-put-ledger-state! kvs {:ledger [{:op :seed :disposition :commit}]})
      (is (= [{:op :seed :disposition :commit}] (:ledger (kv/kv-get-ledger-state kvs)))))))

(deftest kv-store-load-store-and-save-store-work-unchanged-against-a-kotobase-backed-kv-store
  (testing "commitledger.edge.kv-store/load-store hydrates a full in-process
           Store from the remote graph (via kv-list-ids + kv-get-application
           + kv-get-ledger-state -- generic KVStore ops, no kotobase-specific
           code in kv-store.cljc at all)"
    (let [{:keys [store]} (test-remote-store)
          kvs (ks/kotobase-kv-store store)]
      (kv/kv-put-application! kvs "app-x" demo-app)
      (kv/kv-put-ledger-state! kvs {:ledger [{:op :seed :disposition :commit}]})
      (let [local (kv/load-store kvs)]
        (is (= "acme/x" (:borrower-org-repo (store/application local "app-x"))))
        (is (= [:commit] (mapv :disposition (store/ledger local)))))))
  (testing "commitledger.edge.kv-store/save-store! persists a mutated
           in-process Store back to the remote graph"
    (let [{:keys [store]} (test-remote-store)
          kvs (ks/kotobase-kv-store store)
          local (store/empty-store)]
      (store/commit-record! local {:effect :application/upsert :value demo-app})
      (store/append-ledger! local {:op :a :disposition :commit})
      (kv/save-store! kvs local)
      (let [reloaded (kv/load-store kvs)]
        (is (= "acme/x" (:borrower-org-repo (store/application reloaded "app-x"))))
        (is (= [:commit] (mapv :disposition (store/ledger reloaded))))))))
