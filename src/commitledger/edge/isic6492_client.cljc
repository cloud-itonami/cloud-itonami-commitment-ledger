(ns commitledger.edge.isic6492-client
  "Outbound client to `cloud-itonami-isic-6492`'s edge intake endpoint
  (`POST /api/loan/intake`, `docs/adr/0002-http-edge-loan-intake.md`
  there) -- the exact SAME `Lookup`-shaped injection-seam pattern
  `commitledger.edge.registrylookup` already establishes for THIS
  actor's own inbound-lookup dependency (protocol + `MockLookup`/
  `Mock*Client` for tests + a `:cljs`-only `Live*` for the real edge
  runtime), applied here to an OUTBOUND call instead.

  `commitledger.operation`'s `:commit` node calls `-intake-loan-
  application` FIRE-AND-FORGET with respect to commit success -- see
  that ns's own docstring and `docs/adr/0003-isic6492-wiring-and-
  approval-resume.md` Decision 2 for why: isic-6492 is an INDEPENDENTLY
  governed actor; a network hiccup or an isic-6492-side hold on ITS OWN
  intake must never roll back or fail THIS actor's own already-committed
  `:commitment/record`. `-intake-loan-application` therefore NEVER
  throws/rejects -- mirroring `Lookup`'s and `CacaoVerifier`'s own
  exception-safety contract exactly -- it always resolves to `{:ok? bool
  :id str|nil :error str|nil}`, and the CALLER (`commitledger.
  operation`'s `:commit` node) is the one that turns that into an
  audit-ledger fact (`:t :isic6492-intake-attempted`)."
  (:require [commitledger.edge.pcompat :as pc]
            #?@(:cljs [[commitledger.edge.base58 :as base58]
                       [commitledger.edge.cacao :as cacao]
                       [commitledger.edge.cacao-mint :as mint]
                       [clojure.string :as str]]
                :clj  [])))

(defprotocol Isic6492Client
  (-intake-loan-application [client application]
    "A COMMITTED commitledger.store application map -> promise-like of
    {:ok? bool :id str|nil :error str|nil}. NEVER throws/rejects -- see
    ns docstring."))

;; ----------------------------- Mock (tests, no network) -----------------------------

(defrecord MockIsic6492Client [result-fn]
  Isic6492Client
  (-intake-loan-application [_ application] (pc/resolved (result-fn application))))

(defn mock-client
  ([] (mock-client (fn [_] {:ok? true :id "mock-isic6492-application-id"})))
  ([result-fn] (->MockIsic6492Client result-fn)))

(defn always-ok-client
  [id] (mock-client (fn [_] {:ok? true :id id})))

(defn always-failing-client
  [error] (mock-client (fn [_] {:ok? false :error error})))

;; ----------------------------- wire shape -----------------------------

(defn ->intake-payload
  "application -> the SUBSET of fields isic-6492's own `POST /api/loan/
  intake` schema understands (`credit.edge.loan-endpoints/parse-intake-
  body`, that repo) -- principal/jurisdiction/borrower-org-repo/purpose.
  Deliberately NEVER sends `:personal-pledge` or any other commitment-
  ledger-specific field isic-6492 has no schema for."
  [application]
  {:requested-principal (:requested-principal application)
   :jurisdiction (:jurisdiction application)
   :borrower-org-repo (:borrower-org-repo application)
   :purpose (:purpose application)})

;; ----------------------------- real wiring (:cljs only) -----------------------------

#?(:cljs
   (defn- std-b64->b64url [s]
     (-> s (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" ""))))

#?(:cljs
   (defn import-signing-key
     "seed-b64 (standard base64, the 32-byte raw Ed25519 private seed
     `scripts/generate-actor-identity.cljs` printed and
     `COMMITMENT_LEDGER_ACTOR_SEED` stores), did (this actor's own
     did:key, `COMMITMENT_LEDGER_ACTOR_DID`, used to derive the JWK 'x'
     public component) -> promise-like of a CryptoKey usable for
     `js/crypto.subtle.sign`.

     Reconstructs a JWK rather than `js/crypto.subtle.importKey \"raw\"
     seed-bytes ...` for the private key -- confirmed empirically
     (2026-07-24, Node v26 WebCrypto, the same runtime family Cloudflare
     Workers uses) that raw-format PRIVATE-key EXPORT is unsupported for
     Ed25519 (`Unable to export Ed25519 private key using raw format`),
     and raw-format PRIVATE-key IMPORT is likewise rejected
     (`Unsupported key usage for a Ed25519 key`) -- only the PUBLIC key
     supports raw import/export (this is what `commitledger.edge.cacao/
     verify` already relies on for signature verification, unaffected).
     JWK import DOES work for a private key when both 'd' (the private
     seed) and 'x' (the public component) are present -- 'x' is
     recoverable from `did` alone (base58-decode, drop the 2-byte
     0xed01 multicodec prefix) without needing to store it separately."
     [seed-b64 did]
     (let [seed-bytes (cacao/base64->bytes seed-b64)
           pub-bytes (.slice (base58/decode (subs did (count "did:key:z"))) 2)
           d (std-b64->b64url (mint/bytes->base64 seed-bytes))
           x (std-b64->b64url (mint/bytes->base64 pub-bytes))
           jwk #js {:kty "OKP" :crv "Ed25519" :d d :x x}]
       (.importKey js/crypto.subtle "jwk" jwk #js {:name "Ed25519"} false #js ["sign"]))))

#?(:cljs
   (defrecord LiveIsic6492Client [base-url iss sign-fn]
     Isic6492Client
     (-intake-loan-application [_ application]
       (let [;; cacao.cljc's parse-utc-seconds requires strict
             ;; YYYY-MM-DDTHH:MM:SSZ -- no fractional seconds -- so the
             ;; raw .toISOString() (which includes millis) must be
             ;; truncated. Confirmed empirically via
             ;; scripts/verify-cacao-mint-roundtrip.cljs (a bare
             ;; .toISOString() iat fails verify with "invalid CACAO
             ;; iat" even though the signature itself is valid).
             iat (.replace (.toISOString (js/Date.)) #"\.\d{3}Z$" "Z")
             fields {:aud (str base-url "/api/loan/intake")
                     :domain (try (.-hostname (js/URL. base-url)) (catch :default _ base-url))
                     :version "1"
                     :nonce (str (js/Math.floor (* (js/Math.random) 1e12)))
                     :iat iat
                     :resources []}]
         (-> (mint/mint iss sign-fn fields)
             (.then (fn [{:keys [cacao-b64]}]
                      (js/fetch (str base-url "/api/loan/intake")
                                #js {:method "POST"
                                     :headers #js {"content-type" "application/json"
                                                   "authorization" (str "CACAO " cacao-b64)}
                                     :body (js/JSON.stringify (clj->js (->intake-payload application)))})))
             (.then (fn [resp]
                      (-> (.json resp)
                          (.catch (fn [_] #js {}))
                          (.then (fn [data]
                                   (if (.-ok resp)
                                     {:ok? true :id (aget data "id")}
                                     {:ok? false :error (str "isic-6492 intake failed: HTTP " (.-status resp))}))))))
             ;; Operational observability for this fire-and-forget call
             ;; (commitledger.operation's :commit node never awaits or
             ;; surfaces this synchronously to the HTTP response -- see
             ;; that ns's docstring) -- visible via `wrangler pages
             ;; deployment tail`, not part of the portable core.
             (.then (fn [result]
                      (js/console.log "isic6492-client intake result:" (clj->js result))
                      result))
             (.catch (fn [e]
                       (let [result {:ok? false :error (str "isic-6492 intake request error: " (ex-message e))}]
                         (js/console.error "isic6492-client intake error:" (clj->js result))
                         result))))))))

#?(:cljs
   (defn live-client-from-env
     "env, base-url -> promise-like of a LiveIsic6492Client, or nil if
     `COMMITMENT_LEDGER_ACTOR_SEED`/`COMMITMENT_LEDGER_ACTOR_DID` are not
     configured (fail-soft -- a missing self-mint identity must not
     crash the commit path; `commitledger.operation`'s `:commit` node
     already treats a nil `isic6492-client` as 'skip the call', exactly
     what most JVM tests rely on)."
     [env base-url]
     (let [seed-b64 (aget env "COMMITMENT_LEDGER_ACTOR_SEED")
           did (aget env "COMMITMENT_LEDGER_ACTOR_DID")]
       (if (or (not seed-b64) (not did))
         (js/Promise.resolve nil)
         (.then (import-signing-key seed-b64 did)
                (fn [priv-key]
                  (->LiveIsic6492Client base-url did
                                        (fn [msg-bytes] (.sign js/crypto.subtle "Ed25519" priv-key msg-bytes)))))))))
