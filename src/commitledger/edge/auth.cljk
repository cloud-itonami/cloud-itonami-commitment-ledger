(ns commitledger.edge.auth
  "Portable CORE of `gftdcojp/cloud-itonami`'s `cloud_itonami.edge.
  tenant-auth`/`cloud_itonami.edge.register` request-verification
  pipeline (CACAO header parse -> verify -> resource-scope check),
  re-shaped as an INJECTION SEAM (`CacaoVerifier`, same swap-not-rewrite
  philosophy as `commitledger.store`'s `Store` and `commitledger.edge.
  registrylookup`'s `Lookup`) so this ns has ZERO `js/` interop and is
  directly unit-testable under `clojure -M:dev:test` (JVM) with a
  `mock-verifier` -- no real Ed25519/Web-Crypto needed to test the AUTH
  GATING LOGIC (resource-scope matching, lender-identity matching,
  response shaping) itself. The REAL crypto (`commitledger.edge.cacao/
  verify`, a faithful port of `cloud_itonami.edge.cacao`) is wired in
  ONLY by the `:cljs`-only `live-verifier` at the bottom of this ns --
  see that fn's docstring for why its own crypto path is NOT re-tested
  here (it is a byte-for-byte port of an upstream file with its own
  20-case passing test suite, `cloud-itonami/test/cloud_itonami/edge/
  cacao_test.cljc`, which needs a CLJS-only Node test runner this repo
  deliberately does not introduce a second toolchain for -- see
  `docs/adr/0002-http-edge-live-registry-verification.md`).

  Responses are PLAIN MAPS (`{:status int :body map}`), not real
  `js/Response` objects -- `commitledger.edge.commitment-endpoints`'
  `:cljs`-only `->js-response` is the one place that boundary gets
  crossed, exactly like `commitledger.edge.kv-store`'s JSON-text boundary
  only exists inside its own `:cljs`-only branch."
  (:require [commitledger.edge.pcompat :as pc]
            #?@(:cljs [[commitledger.edge.cacao :as cacao]]
                :clj  [])))

(defprotocol CacaoVerifier
  (-verify-cacao [v cacao-b64]
    "cacao-b64 -> promise-like of {:valid? bool :iss str|nil :error
    str|nil :resources [str]|nil}. Never throws -- any decode/signature/
    temporal failure resolves to {:valid? false :error ...}, mirroring
    `cloud_itonami.edge.cacao/verify`'s own exception-safety contract
    (this always runs against untrusted client input)."))

(defrecord MockCacaoVerifier [result-fn]
  CacaoVerifier
  (-verify-cacao [_ cacao-b64] (pc/resolved (result-fn cacao-b64))))

(defn mock-verifier
  "A deterministic, canned CacaoVerifier for tests -- `result-fn` maps
  the raw (fake) b64 string to a verdict map. No crypto involved (see ns
  docstring for where the real crypto IS tested -- upstream, not here)."
  [result-fn]
  (->MockCacaoVerifier result-fn))

(defn always-valid-verifier
  "A MockCacaoVerifier that always succeeds for `iss` with the given
  `resources` -- the common positive-path test fixture."
  [iss resources]
  (mock-verifier (fn [_] {:valid? true :iss iss :resources resources})))

;; ----------------------------- pure helpers -----------------------------

(def ^:private cacao-header-re
  "Case-insensitive, mirrors cloud_itonami.edge.tenant-auth/register's own
  shared `cacao-header-re` (built via `js/RegExp` there ONLY because a
  cljs regex literal has no flags; a plain Clojure `#\"(?i)...\"` inline
  flag does the same job portably)."
  #"(?i)^CACAO\s+(.+)$")

(defn resources-scoped-to?
  "Mirrors `cloud_itonami.edge.register`'s own `resources-scoped-to?` --
  a CACAO's `resources` must explicitly name this `{org}/{repo}`
  (`kotoba://itonami/{org}/{repo}`), the exact scope
  `cloud-itonami.identity-core/itonami-resources` mints. Without this, a
  CACAO minted for an unrelated purpose (a different tenant's session,
  or a kotobase.net session from the same underlying actor identity)
  would have a genuinely valid signature and pass the temporal window,
  so it could otherwise be replayed here."
  [resources org repo]
  (let [want (str "kotoba://itonami/" org "/" repo)]
    (boolean (some #{want} (or resources [])))))

(defn json-response
  "A portable response SHAPE, not a real js/Response -- see ns
  docstring."
  [body status]
  {:status status :body body})

(defn lender-authorized?
  "Only the actual named lender (the CACAO's own verified `iss`) may
  trigger `:commitment/record`/`:commitment/tranche-release` for an
  application -- mirrors the borrower-side resource-scope check's
  'only the actual owner acts for their own thing' shape, applied to the
  LENDER identity `:lender/id` already carries on the stored
  application (never a fresh claim the request itself makes)."
  [iss application]
  (boolean (and iss (= iss (get-in application [:lender :lender/id])))))

;; ----------------------------- the verification pipeline -----------------------------

(defn verify-cacao-header
  "verifier, cacao-header (the raw `Authorization` header string, or
  nil), org, repo -> promise-like of {:ok? bool :iss str|nil :response
  map|nil}. `:response` is set (a `json-response`) whenever `:ok?` is
  false -- the caller's job is only to short-circuit on it, mirroring
  `cloud_itonami.edge.tenant-auth`'s own `short-circuit?` chain shape
  (here expressed as a plain map field instead of an `instance?` check,
  since there is no real `js/Response` class on `:clj`)."
  [verifier cacao-header org repo]
  (let [m (when cacao-header (re-matches cacao-header-re cacao-header))]
    (if-not m
      (pc/resolved {:ok? false :iss nil
                    :response (json-response {:ok false :error "unauthorized"
                                              :reason "requires Authorization: CACAO <b64>"} 401)})
      (pc/then
       (-verify-cacao verifier (second m))
       (fn [{:keys [valid? iss error resources]}]
         (cond
           (not (and valid? iss))
           {:ok? false :iss nil
            :response (json-response {:ok false :error "unauthorized"
                                      :reason (or error "invalid or expired CACAO")} 401)}

           (not (resources-scoped-to? resources org repo))
           {:ok? false :iss nil
            :response (json-response {:ok false :error "forbidden"
                                      :reason (str "CACAO resources must include kotoba://itonami/" org "/" repo)} 403)}

           :else
           {:ok? true :iss iss :response nil}))))))

(defn require-lender
  "Second-stage gate for `:commitment/record`/`:commitment/tranche-
  release`: given the already-CACAO-verified `iss` and the stored
  `application`, confirm `iss` matches the application's own
  `:lender/id` -- 'only the actual named lender may act for their own
  commitment', the record/tranche-release analog of `verify-cacao-
  header`'s borrower-side resource-scope check. Returns {:ok? bool
  :response map|nil}, same short-circuit shape."
  [iss application]
  (if (lender-authorized? iss application)
    {:ok? true :response nil}
    {:ok? false
     :response (json-response {:ok false :error "forbidden"
                               :reason "only the lender named on this application (:lender/id) may authorize it"} 403)}))

;; ----------------------------- real wiring (:cljs only) -----------------------------

#?(:cljs
   (defrecord LiveCacaoVerifier []
     CacaoVerifier
     (-verify-cacao [_ cacao-b64]
       (.then (cacao/verify cacao-b64)
              (fn [result]
                {:valid? (boolean (aget result "valid"))
                 :iss (aget result "iss")
                 :error (aget result "error")
                 :resources (some-> (aget result "payload") (aget "resources") array-seq vec)})))))

#?(:cljs
   (defn live-verifier
     "The real CacaoVerifier, wired to `commitledger.edge.cacao/verify` (a
     faithful, unmodified-wire-format port of `cloud_itonami.edge.cacao`
     -- see ns docstring for why its own crypto is not re-tested by this
     repo's JVM suite: it needs `js/crypto.subtle`, which has no JVM
     equivalent to port to without reimplementing Ed25519/CBOR/base58
     signature verification wholesale, exactly the thing the task this
     port was written for explicitly said not to do (\"don't reinvent the
     wire format\"). The port is byte-for-byte faithful to a file with
     its own 20-case passing test suite upstream)."
     [] (->LiveCacaoVerifier)))
