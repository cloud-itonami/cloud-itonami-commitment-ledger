(ns commitledger.edge.cacao-mint
  "CAIP-122/SIWE CACAO mint for the edge -- a direct, faithful PORT of
  `gftdcojp/cloud-itonami`'s `cloud_itonami.edge.cacao-mint`
  (AGPL-3.0-or-later; same license-compatibility check + local-mirror-
  not-cross-repo-require convention already established by this repo's
  OWN `commitledger.edge.{cacao,base58,cbor}` ports -- see those ns
  docstrings and `docs/adr/0002-http-edge-live-registry-verification.md`
  Decision 3). Algorithm UNCHANGED from upstream -- do not reinvent the
  wire format.

  This is this actor's FIRST self-mint outbound identity (ADR-0002
  Decision 4 explicitly scoped V2 to need none; `docs/adr/0003-isic6492-
  wiring-and-approval-resume.md` records why V3 now does: calling
  `cloud-itonami-isic-6492`'s own CACAO-gated `POST /api/loan/intake`
  requires presenting a CACAO this actor itself signs). The actor's
  identity is generated ONCE, offline, by `scripts/generate-actor-
  identity.cljs` (an nbb script -- NEVER at request time, and NEVER
  written into a committed file) and stored as the
  `COMMITMENT_LEDGER_ACTOR_SEED` Cloudflare Pages secret; at request
  time `commitledger.edge.isic6492-client`'s `LiveIsic6492Client`
  imports it and calls `mint` below with a `sign-fn` closing over the
  imported key. See that ns's docstring for exactly how the raw private
  key is imported (a JWK reconstruction, not `js/crypto.subtle.
  importKey \"raw\" ...` for the PRIVATE key -- confirmed empirically
  that this runtime's Ed25519 implementation does not support raw-format
  PRIVATE key export/import, only public; `scripts/generate-actor-
  identity.cljs`'s own docstring records the same finding).

  CLJS-only (js/crypto.subtle, js/Promise, js/btoa)."
  (:require [commitledger.edge.base58 :as base58]
            [commitledger.edge.cbor :as cbor]
            [commitledger.edge.cacao :as cacao]))

(defn did-key-from-raw-ed25519-pub
  "did:key:z... (Ed25519, multicodec 0xed01) from a raw 32-byte public
  key -- the mint-side inverse of `cacao.cljc`'s private `did-key-
  >pubkey`."
  [raw-pub-bytes]
  (str "did:key:z" (base58/encode (js/Uint8Array.from
                                   (into [0xed 0x01] (array-seq (js/Array.from raw-pub-bytes)))))))

(defn bytes->base64 [bytes]
  (let [arr (js/Array.from bytes)]
    (js/btoa (apply str (map js/String.fromCharCode (array-seq arr))))))

(defn mint
  "Sign `fields` (:domain :aud :version :nonce :iat :exp :resources --
  all strings except :resources, a vector-of-strings or nil) as `iss`
  using `sign-fn` (a fn of msg-bytes -> Promise<sig-bytes>, e.g.
  `#(js/crypto.subtle.sign \"Ed25519\" priv-key %)`), and assemble the
  base64 CACAO blob `cacao/verify` accepts unmodified. Returns a
  Promise<{:cacao-b64 :iss}>."
  [iss sign-fn fields]
  (let [payload #js {:iss iss
                      :aud (:aud fields)
                      :iat (:iat fields)
                      :exp (:exp fields)
                      :nonce (:nonce fields)
                      :domain (:domain fields)
                      :version (or (:version fields) "1")
                      :resources (clj->js (or (:resources fields) []))}
        msg (cacao/siwe-message payload)
        msg-bytes (.encode (js/TextEncoder.) msg)]
    (-> (sign-fn msg-bytes)
        (.then
         (fn [sig-ab]
           (let [sig-b64 (bytes->base64 (js/Uint8Array. sig-ab))
                 p-pairs (cond-> [["iss" iss]
                                  ["aud" (:aud fields)]
                                  ["iat" (:iat fields)]
                                  ["nonce" (:nonce fields)]
                                  ["domain" (:domain fields)]
                                  ["version" (or (:version fields) "1")]]
                           (:exp fields) (conj ["exp" (:exp fields)])
                           (seq (:resources fields)) (conj ["resources" (vec (:resources fields))]))
                 outer (cbor/encode-cacao-envelope p-pairs sig-b64)]
             {:cacao-b64 (bytes->base64 (js/Uint8Array.from (clj->js outer)))
              :iss iss}))))))
