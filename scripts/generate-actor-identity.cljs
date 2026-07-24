;; nbb script -- generates this actor's OWN outbound self-mint identity
;; (docs/adr/0003-isic6492-wiring-and-approval-resume.md), ONCE, offline.
;;
;; Usage:  nbb --classpath src scripts/generate-actor-identity.cljs
;;
;; Prints the did:key and the exportable private-key material (standard
;; base64) to STDOUT. NEVER writes the private key into any committed
;; file -- the operator is expected to pipe/copy the printed value
;; straight into `wrangler pages secret put COMMITMENT_LEDGER_ACTOR_SEED
;; --project-name cloud-itonami-commitment-ledger` and then discard it
;; (this script itself holds no state after the process exits).
;;
;; Runtime priority note (CLAUDE.md): nbb over JVM for tooling. nbb runs
;; on Node, which HAS `globalThis.crypto.subtle` (confirmed: Node v26.3.0,
;; 2026-07-24) -- this script is nbb's own empirical confirmation that
;; `js/crypto.subtle` Ed25519 keygen/export works under nbb, the same
;; runtime `commitledger.edge.cacao-mint`/`commitledger.edge.cacao`
;; target at request time (Cloudflare Workers, a sibling V8-based
;; isolate with the same WebCrypto surface).
;;
;; Key-format finding (see `commitledger.edge.isic6492-client/import-
;; signing-key`'s own docstring for the full empirical record): Ed25519
;; PRIVATE key raw-format EXPORT is NOT supported on this runtime family
;; ("Unable to export Ed25519 private key using raw format") -- only
;; PUBLIC keys support "raw". This script therefore exports the private
;; key as JWK and prints just its 'd' field (the 32-byte raw seed,
;; re-base64'd from JWK's base64url) as the secret -- exactly the byte
;; string `commitledger.edge.isic6492-client/import-signing-key`
;; reconstructs a JWK from at request time (needing only that seed PLUS
;; the public did:key, both printed here).

(ns generate-actor-identity
  (:require [commitledger.edge.cacao-mint :as mint]))

(defn- b64url->bytes [s]
  (let [pad (case (mod (count s) 4) 2 "==" 3 "=" "")
        std (-> s (.replaceAll "-" "+") (.replaceAll "_" "/") (str pad))
        bin (js/atob std)
        n (.-length bin)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(-> (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
    (.then (fn [kp]
             (js/Promise.all #js [(js/crypto.subtle.exportKey "jwk" (.-privateKey kp))
                                   (js/crypto.subtle.exportKey "raw" (.-publicKey kp))])))
    (.then (fn [results]
             (let [jwk (aget results 0)
                   pub-raw (js/Uint8Array. (aget results 1))
                   seed-bytes (b64url->bytes (aget jwk "d"))
                   did (mint/did-key-from-raw-ed25519-pub pub-raw)
                   seed-b64 (mint/bytes->base64 seed-bytes)]
               (println "COMMITMENT_LEDGER_ACTOR_DID=" did)
               (println "COMMITMENT_LEDGER_ACTOR_SEED=" seed-b64)
               (println)
               (println ";; Store with e.g.:")
               (println ";;   wrangler pages secret put COMMITMENT_LEDGER_ACTOR_SEED --project-name cloud-itonami-commitment-ledger")
               (println ";;   (paste the COMMITMENT_LEDGER_ACTOR_SEED value above, then discard this terminal output)")
               (println ";; and set COMMITMENT_LEDGER_ACTOR_DID as a plain (non-secret) Pages var --")
               (println ";; it is public information (it IS the public key, base58-encoded).")))
           )
    (.catch (fn [e]
              (js/console.error "generate-actor-identity failed:" (.-message e))
              (js/process.exit 1))))
