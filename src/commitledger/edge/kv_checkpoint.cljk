(ns commitledger.edge.kv-checkpoint
  "The load-before/persist-after seam that lets an `:request-approval`-
  interrupted `langgraph.graph` run survive across separate, stateless
  Cloudflare Pages Functions invocations -- the missing piece
  `docs/adr/0003-isic6492-wiring-and-approval-resume.md` adds so
  `commitledger.edge.commitment-endpoints/on-request-post-approve` can
  actually resume a run `on-request-post-record`/`-tranche-release`
  interrupted in an EARLIER request.

  `langgraph.checkpoint/Checkpointer`'s `-put!`/`-get-latest`/`-list-
  checkpoints` are SYNCHRONOUS (called directly inside `langgraph.graph/
  run-loop`'s superstep reduce) -- real Cloudflare KV access is async
  (`.get`/`.put` return Promises), so a Checkpointer literally CANNOT
  proxy KV calls directly. This ns instead mirrors `commitledger.edge.
  kv-store`'s own `load-store`/`save-store!` pattern applied to
  checkpoints instead of the Store: `load-checkpointer` asynchronously
  fetches the ONE persisted checkpoint for `thread-id` (if any) from KV,
  then returns a real, synchronous, in-memory `Checkpointer` (a `reify`
  over a fresh atom seeded with that one checkpoint) for `langgraph.
  graph/run*` to use for THIS request; `save-checkpoint!` asynchronously
  persists whatever that in-memory Checkpointer ends up holding, once
  the graph run has finished, back to KV.

  Serialization: `pr-str`/`edn/read-string` of the WHOLE checkpoint map
  (`{:step :state :frontier :status}`), not a bespoke field-by-field
  JSON codec like `commitledger.edge.kv-codec` -- `:state` carries rich,
  keyword-heavy nested StateGraph data (`:proposal`/`:verdict`/
  `:disposition`/`:record`/`:context`/`:request`, per `commitledger.
  operation`'s own channel shapes) with no natural camelCase JSON
  mapping worth hand-writing. This EXACTLY mirrors `langgraph.checkpoint/
  datomic-checkpointer`'s own `(pr-str state)`/`(edn/read-string ...)`
  precedent (`orgs/kotoba-lang/langgraph`) -- not a new pattern, just
  applied to a KV blob instead of a Datomic-API attribute."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [commitledger.edge.pcompat :as pc]
            [langgraph.checkpoint :as cp]))

(defprotocol CheckpointStore
  (cs-get [store thread-id] "-> promise-like of the persisted checkpoint map for thread-id, or nil")
  (cs-put! [store thread-id ckpt] "-> promise-like of nil"))

;; ----------------------------- codec (pure, portable) -----------------------------

(defn checkpoint->text [ckpt] (pr-str ckpt))
(defn text->checkpoint [text] (when text (edn/read-string text)))

;; ----------------------------- MemCheckpointStore (tests, in-process default) -----------------------------

(defrecord MemCheckpointStore [a] ;; a: atom of {thread-id -> pr-str text}
  CheckpointStore
  (cs-get [_ thread-id] (pc/resolved (text->checkpoint (get @a thread-id))))
  (cs-put! [_ thread-id ckpt] (swap! a assoc thread-id (checkpoint->text ckpt)) (pc/resolved nil)))

(defn mem-checkpoint-store [] (->MemCheckpointStore (atom {})))

;; ----------------------------- CloudflareCheckpointStore (real edge runtime) -----------------------------

#?(:cljs
   (defn- ckpt-key [thread-id] (str "checkpoint:" thread-id)))

#?(:cljs
   (defrecord CloudflareCheckpointStore [env binding-name]
     CheckpointStore
     (cs-get [_ thread-id]
       (-> (.get (aget env binding-name) (ckpt-key thread-id))
           (.then (fn [raw] (text->checkpoint raw)))))
     (cs-put! [_ thread-id ckpt]
       (.then (.put (aget env binding-name) (ckpt-key thread-id) (checkpoint->text ckpt))
              (fn [_] nil)))))

#?(:cljs
   (defn cloudflare-checkpoint-store
     "The real CheckpointStore, against the `env` binding a Cloudflare
     Pages Function's `context` carries -- `binding-name` defaults to
     `COMMITMENT_LEDGER_KV` (the SAME namespace `commitledger.edge.kv-
     store` uses, distinguished only by the `checkpoint:` key prefix --
     no separate KV namespace needed for this)."
     ([env] (cloudflare-checkpoint-store env "COMMITMENT_LEDGER_KV"))
     ([env binding-name] (->CloudflareCheckpointStore env binding-name))))

;; ----------------------------- the load-before/persist-after seam -----------------------------

(defn load-checkpointer
  "cs, thread-id -> promise-like of a `langgraph.checkpoint/Checkpointer`
  seeded with the persisted checkpoint (if any) for thread-id -- see ns
  docstring. NOT a `ClaimableCheckpointer` (concurrent-resume-claim
  safety, `langgraph.checkpoint`'s own docstring) -- this in-memory
  reify is fresh per request/atom, so a real cross-request concurrent-
  resume race is not closed here; a known, documented gap (see
  `docs/adr/0003-isic6492-wiring-and-approval-resume.md`'s
  Consequences), not silently papered over -- exactly the same class of
  gap `commitledger.edge.kv-store`'s own docstring already names for
  the read-modify-write Store round-trip."
  [cs thread-id]
  (pc/then
   (cs-get cs thread-id)
   (fn [ckpt]
     (let [a (atom (if ckpt {thread-id [ckpt]} {}))]
       (reify cp/Checkpointer
         (-put! [_ tid c] (swap! a update tid (fnil conj []) c) c)
         (-get-latest [_ tid] (peek (get @a tid [])))
         (-list-checkpoints [_ tid] (vec (sort-by :step (get @a tid [])))))))))

(defn save-checkpoint!
  "cs, checkpointer, thread-id -> promise-like of nil. Persists the
  checkpointer's current latest checkpoint for thread-id back to `cs` --
  the save-side inverse of `load-checkpointer`, called once after the
  actor graph has finished running for this request. A no-op (resolves
  immediately) if there is nothing to persist (a graph run that never
  wrote a checkpoint for this thread-id at all)."
  [cs checkpointer thread-id]
  (if-let [latest (cp/get-latest checkpointer thread-id)]
    (cs-put! cs thread-id latest)
    (pc/resolved nil)))
