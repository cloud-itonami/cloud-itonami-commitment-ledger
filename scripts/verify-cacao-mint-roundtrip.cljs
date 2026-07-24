;; nbb script -- the REAL round-trip proof `test/commitledger/edge/
;; cacao_mint_test.cljc` documents but cannot execute under `clojure
;; -M:dev:test` (JVM, no js/crypto.subtle -- see that test file's own ns
;; docstring). Generates a fresh Ed25519 keypair, mints a CACAO with
;; `commitledger.edge.cacao-mint/mint`, and verifies it through the
;; LOCAL, unmodified `commitledger.edge.cacao/verify` -- proving mint
;; and verify actually agree on wire format end to end (CBOR envelope,
;; SIWE plaintext, base58 did:key), the single most important
;; correctness property for this actor's new self-mint identity.
;;
;; Usage:  nbb --classpath src scripts/verify-cacao-mint-roundtrip.cljs
;; Exits non-zero (and prints why) on ANY failure -- signature invalid,
;; iss mismatch, or a thrown exception.

(ns verify-cacao-mint-roundtrip
  (:require [commitledger.edge.cacao-mint :as mint]
            [commitledger.edge.cacao :as cacao]))

(-> (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
    (.then (fn [kp]
             (-> (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
                 (.then (fn [pub-ab]
                          (let [pub-bytes (js/Uint8Array. pub-ab)
                                did (mint/did-key-from-raw-ed25519-pub pub-bytes)
                                sign-fn (fn [msg-bytes] (js/crypto.subtle.sign "Ed25519" (.-privateKey kp) msg-bytes))
                                ;; cacao.cljc's parse-utc-seconds requires strict
                                ;; YYYY-MM-DDTHH:MM:SSZ -- no fractional seconds --
                                ;; so the raw .toISOString() (which includes millis)
                                ;; must be truncated. Same fix applies at real
                                ;; request time in isic6492_client.cljc.
                                iat (.replace (.toISOString (js/Date.)) #"\.\d{3}Z$" "Z")
                                fields {:domain "cloud-itonami-commitment-ledger.pages.dev"
                                        :aud "https://cloud-itonami-isic-6492.pages.dev/api/loan/intake"
                                        :version "1"
                                        :nonce "roundtrip-check-1"
                                        :iat iat
                                        :resources []}]
                            (println "generated did:" did)
                            (-> (mint/mint did sign-fn fields)
                                (.then (fn [{:keys [cacao-b64 iss]}]
                                         (println "minted cacao-b64 length:" (count cacao-b64))
                                         (println "minted iss:" iss)
                                         (-> (cacao/verify cacao-b64)
                                             (.then (fn [result]
                                                      (let [valid? (boolean (aget result "valid"))
                                                            verified-iss (aget result "iss")
                                                            err (aget result "error")]
                                                        (println "verify result -- valid:" valid? "iss:" verified-iss "error:" err)
                                                        (if (and valid? (= did verified-iss) (not err))
                                                          (do (println "ROUNDTRIP OK: mint and verify agree on wire format")
                                                              (js/process.exit 0))
                                                          (do (println "ROUNDTRIP FAILED")
                                                              (js/process.exit 1))))))))))))))))
    (.catch (fn [e]
              (js/console.error "verify-cacao-mint-roundtrip threw:" (.-message e))
              (js/process.exit 1))))
