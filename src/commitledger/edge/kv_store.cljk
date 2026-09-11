(ns commitledger.edge.kv-store
  "HTTP-request-scoped persistence for the commitment-ledger edge layer,
  backed by a Cloudflare KV namespace -- SEPARATE FROM (does not
  replace) `commitledger.store`'s own `MemStore`/`DatomicStore` SSoT the
  Governor/StateGraph tests exercise. A Cloudflare Pages Function is
  stateless per invocation, so each request reconstructs a fresh, real
  `commitledger.store/empty-store` from whatever this ns loaded, runs the
  actor graph against it (`commitledger.operation/build`), then persists
  the result back here before responding -- mirroring
  `cloud_itonami.edge.workspace_store`'s own \"KV .get/.put instead of
  java.io, otherwise unmodified pure `.cljc` logic\" pattern (that ns's
  own docstring), applied to this actor's `Store` protocol instead of
  `cloud-itonami.store`'s conn.

  Two KV entry KINDS, one namespace:
    - `application:{id}`  -- ONE `commitment-application` map, JSON
      (`commitledger.edge.kv-codec/application->json`/`json->
      application`), the literal per-id entity this ns's own docstring
      is named for.
    - `index`              -- JSON array of every known application id
      (mirrors `cloud_itonami.edge.tenant/registry-index`'s own \"a KV
      namespace has no native list-by-prefix from an edge Worker, so
      maintain an explicit index key\" pattern) -- needed so a fresh
      request can rebuild the FULL cross-application state
      (`commitledger.governor`'s check 6, `individual-lender-loan-count-
      exceeds-threshold-violations`, and check 12's double-release guard
      both need to see PRIOR applications/records, not just the one this
      request is about).
    - `ledger-state`        -- ONE JSON blob of the Store's own
      cross-application aggregate (`:ledger`/`:commitment-history`/
      `:tranche-release-history`/`:released-tranches`/`:commitment-
      sequences`/`:tranche-sequences`, `commitledger.edge.kv-codec/
      ledger-state->json`/`json->ledger-state`) -- the audit trail and
      the double-release/loan-count ground truth, kept alongside (not
      instead of) the per-id application entries.

  Fail-soft on read, matching `cloud_itonami.edge.workspace_store`'s own
  convention: a missing/malformed key resolves to nil/empty rather than
  throwing -- a fresh actor state, not a 500."
  (:require [commitledger.edge.kv-codec :as codec]
            [commitledger.edge.pcompat :as pc]
            [commitledger.store :as store]))

(defprotocol KVStore
  (kv-get-application [store id] "-> promise-like of commitledger.store application map, or nil")
  (kv-put-application! [store id application] "-> promise-like of nil; also adds id to the index if new")
  (kv-list-ids [store] "-> promise-like of a vector of every known application id")
  (kv-get-ledger-state [store] "-> promise-like of the cross-application aggregate map (see ns docstring), or {} if absent")
  (kv-put-ledger-state! [store ledger-state] "-> promise-like of nil"))

;; ----------------------------- MemKVStore (tests, in-process default) -----------------------------

(defrecord MemKVStore [a] ;; a: atom of {:apps {id -> json-safe-map (string keys)} :index [id ...] :ledger-state json-safe-map}
  KVStore
  (kv-get-application [_ id]
    (pc/resolved
     ;; Round-trip through the SAME application->json/json->application
     ;; encode+decode pair the real CloudflareKVStore uses (just not
     ;; through actual js/JSON text) -- proves the JSON-safe shape is
     ;; genuinely lossless for whatever's stored, on a platform with no
     ;; js/JSON at all.
     (some-> (get-in @a [:apps id]) codec/json->application)))
  (kv-put-application! [_ id application]
    (swap! a (fn [s]
               (-> s
                   (assoc-in [:apps id] (codec/application->json application))
                   (update :index (fn [idx] (if (some #{id} idx) idx (conj (vec idx) id)))))))
    (pc/resolved nil))
  (kv-list-ids [_] (pc/resolved (vec (:index @a []))))
  (kv-get-ledger-state [_] (pc/resolved (codec/json->ledger-state (:ledger-state @a))))
  (kv-put-ledger-state! [_ ledger-state]
    (swap! a assoc :ledger-state (codec/ledger-state->json ledger-state))
    (pc/resolved nil)))

(defn mem-kv-store
  "A KVStore backed by an in-process atom -- the deterministic default
  for tests, no Cloudflare runtime needed."
  [] (->MemKVStore (atom {:apps {} :index [] :ledger-state nil})))

;; ----------------------------- CloudflareKVStore (real edge runtime) -----------------------------

#?(:cljs
   (defn- app-key [id] (str "application:" id)))

#?(:cljs
   (defrecord CloudflareKVStore [env binding-name]
     KVStore
     (kv-get-application [_ id]
       (-> (.get (aget env binding-name) (app-key id))
           (.then (fn [raw] (when raw (codec/json->application (js->clj (js/JSON.parse raw))))))))
     (kv-put-application! [store id application]
       (let [kv (aget env binding-name)]
         (-> (kv-list-ids store)
             (.then (fn [idx]
                      (let [idx2 (if (some #{id} idx) idx (conj (vec idx) id))]
                        (js/Promise.all
                         #js [(.put kv (app-key id) (js/JSON.stringify (clj->js (codec/application->json application))))
                              (.put kv "index" (js/JSON.stringify (clj->js idx2)))]))))
             (.then (fn [_] nil)))))
     (kv-list-ids [_]
       (-> (.get (aget env binding-name) "index")
           (.then (fn [raw] (if raw (vec (js->clj (js/JSON.parse raw))) [])))))
     (kv-get-ledger-state [_]
       (-> (.get (aget env binding-name) "ledger-state")
           (.then (fn [raw] (codec/json->ledger-state (when raw (js->clj (js/JSON.parse raw))))))))
     (kv-put-ledger-state! [_ ledger-state]
       (.put (aget env binding-name) "ledger-state"
             (js/JSON.stringify (clj->js (codec/ledger-state->json ledger-state)))))))

#?(:cljs
   (defn cloudflare-kv-store
     "The real KVStore, against the `env` binding a Cloudflare Pages
     Function's `context` carries -- `binding-name` defaults to
     `COMMITMENT_LEDGER_KV` (see `wrangler.jsonc`)."
     ([env] (cloudflare-kv-store env "COMMITMENT_LEDGER_KV"))
     ([env binding-name] (->CloudflareKVStore env binding-name))))

;; ----------------------------- reconstruct/persist a commitledger.store Store -----------------------------

(defn- collect
  "[promise-like ...] -> promise-like of a vector of settled values, in
  order -- a portable `js/Promise.all` substitute (see `pcompat`'s ns
  docstring: `:clj` needs no real await machinery, `:cljs` chains real
  Promises one at a time, which is fine at this actor's request-scoped
  application-count scale)."
  [promises]
  (if (empty? promises)
    (pc/resolved [])
    (pc/then (first promises)
             (fn [v] (pc/then (collect (rest promises)) (fn [rest-vs] (into [v] rest-vs)))))))

(defn load-store
  "kv-store, [ids] -> promise-like of a fresh `commitledger.store/
  empty-store` reconstructed from every application in `ids` (defaults to
  `kv-list-ids`) plus the cross-application ledger-state -- ready for
  `commitledger.operation/build` to run a graph against. `commitledger.
  store` itself never changes; this is purely the KV -> Store
  rehydration boundary (see ns docstring)."
  ([kv-store] (pc/then (kv-list-ids kv-store) (fn [ids] (load-store kv-store ids))))
  ([kv-store ids]
   (pc/then (collect (mapv #(kv-get-application kv-store %) ids))
            (fn [apps]
              (pc/then (kv-get-ledger-state kv-store)
                       (fn [ledger-state]
                         (let [applications (into {} (map vector ids apps))]
                           (store/empty-store (assoc ledger-state :applications applications)))))))))

(defn save-store!
  "kv-store, store -> promise-like of nil. Persists EVERY application
  currently in `store` (`store/all-applications`, never a subset -- a
  subset would silently drop sequence/index coverage for jurisdictions
  outside it) back to KV plus the store's current cross-application
  ledger-state -- the save-side inverse of `load-store`. Called once,
  after the actor graph has finished mutating `store` for this request."
  [kv-store st]
  (let [apps (store/all-applications st)
        ids (mapv :id apps)
        jurisdictions (into #{} (keep :jurisdiction) apps)]
    (pc/then
     (collect (mapv (fn [id] (kv-put-application! kv-store id (store/application st id))) ids))
     (fn [_]
       (kv-put-ledger-state! kv-store
                              {:ledger (store/ledger st)
                               :commitment-history (store/commitment-history st)
                               :tranche-release-history (store/tranche-release-history st)
                               :released-tranches (into {} (map (fn [id] [id (store/released-tranches-of st id)])) ids)
                               :commitment-sequences (into {} (map (fn [j] [j (store/next-commitment-sequence st j)])) jurisdictions)
                               :tranche-sequences (into {} (map (fn [j] [j (store/next-tranche-sequence st j)])) jurisdictions)})))))
