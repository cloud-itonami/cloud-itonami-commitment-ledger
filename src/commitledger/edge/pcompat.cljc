(ns commitledger.edge.pcompat
  "Minimal portable async seam so `commitledger.edge.*`'s CORE logic
  (registrylookup/kv-store/auth request-verification) can run
  IDENTICALLY on JVM (tests -- synchronous, no event loop needed) and
  CLJS (the real edge runtime -- real `js/Promise` chains against
  `fetch`/Cloudflare KV), without literally reinventing Promises. This is
  the async analog of the injection-seam philosophy
  `commitledger.store`'s `Store` protocol already uses for storage
  (swap the backend, not the caller) -- here applied to \"what does it
  mean to sequence an async step\" instead of \"where do bytes live\".

  `:clj` `then` just calls `f` on `p` directly (a JVM test's
  `MockLookup`/`MemKVStore`/`MockCacaoVerifier` are already synchronous
  values, not real promises -- there is no event loop to yield to in a
  `clojure.test` run). `:cljs` `then` chains a real `js/Promise`. Callers
  that thread several steps together (`commitledger.edge.auth`'s
  `verify-cacao-for-resource`, `commitledger.edge.commitment-endpoints`'
  handler bodies) use ONLY `resolved`/`then` from this ns, never
  `:cljs`-only `.then` directly, so the SAME handler-core function body
  runs on both platforms."
  )

(defn resolved
  "Lift a plain value into whatever this platform's \"already-settled
  promise\" is -- a real resolved `js/Promise` under `:cljs`, or just the
  value itself under `:clj` (nothing to lift; `then` below never actually
  needs a wrapper on JVM)."
  [v]
  #?(:cljs (js/Promise.resolve v)
     :clj  v))

(defn then
  "Sequence `f` after `p` settles, portably. `:clj`: `p` is already a
  plain value (see ns docstring) -- just call `(f p)`. `:cljs`: `p` is a
  real `js/Promise` -- `(.then p f)`."
  [p f]
  #?(:cljs (.then p f)
     :clj  (f p)))
