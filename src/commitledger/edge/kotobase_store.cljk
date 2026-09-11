(ns commitledger.edge.kotobase-store
  "The injected-`:db-api` `commitledger.store/DatomicStore` constructor
  pointed at a live kotobase-server graph (kotobase.net), self-sovereign
  via this actor's own Ed25519 key -- kotobase-persistence-migration,
  docs/adr/0004-kotobase-persistence-migration.md. Mirrors `crm.store/
  store-with-api` + `crm.kotobase/kotobase-store` (cloud-itonami-isic-
  5820, ADR-2607184000) precisely, with ONE structural adaptation this
  ns's own docstring documents in full: `crm.kotobase` runs on the JVM
  (blocking `java.net.http.HttpClient`), so its `db-api` fns return
  plain values synchronously; THIS actor is a real Cloudflare Pages
  Function, which has NO synchronous I/O primitive at all (`js/fetch` is
  unconditionally async) -- so this ns is built on `langchain.kotoba-db/
  kotoba-api-async` (added by this same migration) instead of `kotoba-
  api`, and every `db-api` fn genuinely returns a promise-like (a real
  `js/Promise` under `:cljs`, the plain value itself under `:clj` --
  `commitledger.edge.pcompat`'s own established duality, one layer
  down). `commitledger.store/DatomicStore`'s OWN methods
  (`application`/`all-applications`/`with-applications`/`ledger-state`/
  `with-ledger-state`) are ALREADY written to tolerate this (see that
  ns's `chain` helper) -- this ns only has to build a correctly-shaped
  `db-api` + `conn`, not touch the Store methods themselves.

  `commitledger.operation`'s StateGraph (`langgraph.graph/run*`) still
  runs fully SYNCHRONOUSLY against an in-process snapshot store every
  request, exactly as it did against KV -- unchanged, and unchanged is
  the point. Rather than replacing `commitledger.edge.kv-store/load-
  store`/`save-store!` (the existing request-scoped hydrate/persist
  functions `commitledger.edge.commitment-endpoints` already calls)
  with a NEW, differently-shaped boundary, this ns instead implements
  `commitledger.edge.kv-store`'s OWN `KVStore` protocol
  (`KotobaseKVStore` below) against kotobase.net -- the SAME `kv-get-
  application`/`kv-put-application!`/`kv-list-ids`/`kv-get-ledger-
  state`/`kv-put-ledger-state!` contract `MemKVStore` (tests) and
  `CloudflareKVStore` (the KV this migration replaces) already
  implement. This means `commitledger.edge.kv-store/load-store`/`save-
  store!` -- and every `*-core!` fn in `commitledger.edge.commitment-
  endpoints`, and EVERY existing test in `commitment_endpoints_test.
  cljc` that drives them via `kv/mem-kv-store` -- need ZERO changes:
  only the bottom-of-file `on-request-*` Cloudflare Pages Function entry
  points swap `(kv/cloudflare-kv-store env)` for `(kotobase-kv-store-
  from-env! env)` (now async, since minting a CACAO is; those entry
  points already thread through `.then`/`pc/then` chains, so this is a
  same-shape swap one level up, not a rewrite)."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [commitledger.edge.kotobase-http :as khttp]
            [commitledger.edge.kotobase-identity :as identity]
            [commitledger.edge.kv-store :as kv]
            [commitledger.edge.pcompat :as pc]
            [commitledger.store :as store]
            [langchain.kotoba-db :as kdb]))

;; ───────── db-api (langchain.kotoba-db/kotoba-api-async, EDN wire body) ────

(defn- json-write
  "The OUTER request-body wire format kotobase-server's XRPC endpoints
  actually expect (real JSON -- content-type is `application/json`,
  `langchain.kotoba-db/req-headers`) -- distinct from the individual
  EDN-string FIELDS inside that JSON (`tx_edn`/`query_edn`/etc, already
  `pr-str`'d by `langchain.kotoba-db` itself before this fn ever sees
  them). `:clj` uses `pr-str` instead -- harmless, since the `:clj`
  branch of this ns is ONLY ever exercised by this ns's own JVM tests
  against a self-consistent mock `:http-fn` (this ns's real kotobase.net
  traffic is `:cljs`-only, see `commitledger.edge.kotobase-http`'s own
  docstring), not real kotobase.net wire traffic."
  [m]
  #?(:cljs (js/JSON.stringify (clj->js m))
     :clj  (pr-str m)))

(defn- json-read
  [s]
  #?(:cljs (js->clj (js/JSON.parse s) :keywordize-keys true)
     :clj  (edn/read-string s)))

(defn db-api-for
  "`http-fn` -> a `langchain.kotoba-db/kotoba-api-async` map."
  [http-fn]
  (kdb/kotoba-api-async {:http-fn http-fn :json-write json-write :json-read json-read}))

;; ───────── Store constructor (mirrors crm.kotobase/kotobase-store) ─────────

(defn kotobase-store
  "A `commitledger.store/Store` (via `commitledger.store/store-with-
  api`) backed by a live kotobase-server graph, self-sovereign via this
  actor's own Ed25519 key. Returns a promise-like of a ready
  `DatomicStore` (minting the shared read CACAO is itself async).

  opts:
    :http-fn      REQUIRED -- an async :http-fn (`kotoba-api-async`'s
                  contract): production passes `commitledger.edge.
                  kotobase-http/fetch-http-fn`, tests pass
                  `commitledger.edge.kotobase-http/resolved-mock-http-fn`.
    :did          this actor's own did:key (for the wire's x-kotoba-did
                  header AND the CACAO resource scope -- see
                  `kotobase-identity/kotobase-resources`'s docstring).
    :db-name      tenant database name (default `kotobase-identity/
                  default-db-name`) -- used for BOTH reads and writes
                  (no precomputed graph CID needed -- BUGFIX, see
                  `kotobase-identity/kotobase-resources`'s docstring:
                  confirmed directly against production, 2026-07-24,
                  that omitting `:graph` entirely and letting
                  `langchain.kotoba-db/read-scope`'s own `db_name`
                  fallback resolve the tenant graph server-side works
                  for reads exactly the same way it already did for
                  writes -- no separate CID derivation needed at all).
    :url          kotobase-server base URL (default
                  \"https://kotobase.net\").
    :mint-cacao!  REQUIRED -- `(fn [op] => promise-like of a base64
                  CACAO string)`, `op` \"datom:read\" or
                  \"datom:transact\". Called ONCE for the shared read
                  conn and freshly for EVERY `:transact!` call --
                  kotobase-server's nonce-replay protection 401s a
                  reused CACAO's second write (confirmed,
                  ADR-2607184000; see `kotobase-identity/mint-kotobase-
                  cacao!`'s docstring)."
  [{:keys [http-fn url db-name mint-cacao! did]
    :or {url "https://kotobase.net" db-name identity/default-db-name}}]
  (let [api (db-api-for http-fn)]
    (pc/then
     (mint-cacao! "datom:read")
     (fn [read-cacao]
       (let [read-conn (kdb/kotoba-conn* url db-name {:cacao read-cacao :did did})
             write-conn! (fn []
                           (pc/then (mint-cacao! "datom:transact")
                                    (fn [write-cacao]
                                      (kdb/kotoba-conn* url db-name {:cacao write-cacao :did did}))))
             remote-api {:transact! (fn [_conn tx-data]
                                      (pc/then (write-conn!) (fn [wc] ((:transact! api) wc tx-data))))
                         :db identity
                         :q (fn [query _conn & inputs] (apply (:q api) query read-conn inputs))
                         :pull (fn [_conn pattern eid] ((:pull api) read-conn pattern eid))
                         :entid (fn [_conn eid] ((:entid api) read-conn eid))}]
         (store/store-with-api remote-api read-conn))))))

;; ───────── KVStore protocol impl (replaces CloudflareKVStore in PRODUCTION) ─

(defrecord KotobaseKVStore [remote-store]
  kv/KVStore
  (kv-get-application [_ id] (store/application remote-store id))
  (kv-put-application! [_ id application]
    (pc/then (store/with-applications remote-store {id application}) (fn [_] nil)))
  (kv-list-ids [_] (pc/then (store/all-applications remote-store) (fn [apps] (mapv :id apps))))
  (kv-get-ledger-state [_] (store/ledger-state remote-store))
  (kv-put-ledger-state! [_ ledger-state]
    (pc/then (store/with-ledger-state remote-store ledger-state) (fn [_] nil))))

(defn kotobase-kv-store
  "A `commitledger.edge.kv-store/KVStore` backed by `remote-store` (a
  `kotobase-store` result). `commitledger.edge.kv-store/load-store`/
  `save-store!` -- and therefore every `*-core!` fn in `commitledger.
  edge.commitment-endpoints` -- work against this UNCHANGED."
  [remote-store]
  (->KotobaseKVStore remote-store))

;; ───────── production wiring (:cljs only -- real identity + real fetch) ────

#?(:cljs
   (defn kotobase-kv-store-from-env!
     "env -> promise-like of a ready `KVStore` backed by kotobase.net,
     using this actor's own self-mint identity ($COMMITMENT_LEDGER_
     ACTOR_SEED/_DID, already provisioned for this actor's isic-6492
     outbound calls -- see `commitledger.edge.kotobase-identity`'s ns
     docstring) + real `js/fetch` (`commitledger.edge.kotobase-http/
     fetch-http-fn`). Drop-in replacement for `commitledger.edge.kv-
     store/cloudflare-kv-store` at every `on-request-*` call site.

     FAIL-CLOSED: rejects (never resolves to a degraded/KV-backed store)
     if the identity is unconfigured or CACAO minting fails -- the edge
     handler's own `.catch` turns any rejection into a clear 5xx. This
     migration's explicit requirement: if kotobase.net is unreachable,
     the request fails loudly, it does NOT silently fall back to KV
     (that would let Governor checks read stale/inconsistent state)."
     [env]
     (pc/then
      (identity/signing-key-from-env env)
      (fn [signing-key]
        (if-not signing-key
          (js/Promise.reject
           (js/Error. "commitledger.edge.kotobase-store: no self-mint identity -- $COMMITMENT_LEDGER_ACTOR_SEED/$COMMITMENT_LEDGER_ACTOR_DID unset"))
          (pc/then
           (kotobase-store
            {:http-fn khttp/fetch-http-fn
             :did (:did signing-key)
             :db-name identity/default-db-name
             :mint-cacao! (fn [op] (identity/mint-kotobase-cacao! signing-key op))})
           kotobase-kv-store))))))
