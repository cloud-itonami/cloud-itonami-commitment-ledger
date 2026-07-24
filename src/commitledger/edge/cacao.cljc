(ns commitledger.edge.cacao
  "CAIP-122 / SIWE (EIP-4361) CACAO verify for the edge -- a direct,
  faithful port of `cloud_itonami.edge.cacao` (`orgs/gftdcojp/cloud-
  itonami`, AGPL-3.0-or-later; see `commitledger.edge.base58`'s ns
  docstring for the full local-mirror-not-cross-repo-require rationale
  and the license-compatibility check in `docs/adr/0002-http-edge-live-
  registry-verification.md`). Verify-only: this never mints a CACAO,
  only checks one a client presents -- this actor needs NO CACAO
  self-mint identity for its own outbound calls (the registry lookup,
  `commitledger.edge.registrylookup`, is a public unauthenticated GET;
  see that ns's docstring). Ed25519 signature verification runs on the
  platform's native Web Crypto (`js/crypto.subtle`) rather than a
  hand-rolled curve implementation -- the only ported pieces are the
  wire format (base58btc, definite-length CBOR, SIWE reconstruction),
  which have no Web Crypto equivalent. CLJS-only, algorithm UNCHANGED
  from upstream -- do not reinvent the wire format.

  Crypto-valid does not mean valid forever: a signature never expires on
  its own, so a temporal check (300s future-`iat` skew tolerance, `exp`
  enforced when present, 7-day max-age fallback when absent) is applied
  after crypto verify succeeds. A captured-and-replayed CACAO past its
  window is rejected here even though the signature itself still checks
  out."
  (:require [clojure.string :as str]
            [commitledger.edge.base58 :as base58]
            [commitledger.edge.cbor :as cbor]))

(def future-skew-sec
  "How far into the future :iat may be before it's rejected (clock skew tolerance)."
  300)

(def max-age-sec
  "Fallback session lifetime when a CACAO carries no :exp."
  (* 7 24 60 60))

(defn- parse-utc-seconds
  "Strict `YYYY-MM-DDTHH:MM:SSZ` -> unix seconds, or nil. Round-trips
  through toISOString to reject anything Date.parse accepts more loosely
  than the CACAO wire format allows."
  [s]
  (when (and s (re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$" s))
    (let [t (js/Date.parse s)]
      (when-not (js/isNaN t)
        (when (= (str/replace (.toISOString (js/Date. t)) ".000Z" "Z") s)
          (js/Math.floor (/ t 1000)))))))

(defn- resources-shape-error
  "nil, or an error string, for `resources` (a JS array or nil/undefined).
  siwe-message renders each element as its own \"- <r>\" line, joined by
  \\n with every other line -- the Ed25519 signature only authenticates
  the FINAL joined text, not the array's element boundaries, so a
  resource string containing an embedded \"\\n- \" sequence
  reconstructs to byte-identical signed bytes as if it had been split
  into two separate array elements at that point. No legitimate resource
  URI (kotoba://...) ever needs an embedded newline, so rejecting any is
  purely a tightening, not a compatibility break."
  [resources]
  (when (and resources (pos? (aget resources "length")))
    (when (some #(str/includes? % "\n") (array-seq resources))
      "CACAO resources must not contain embedded newlines")))

(defn- temporal-error [payload now-sec]
  (let [iat (aget payload "iat")
        exp (aget payload "exp")
        iat-sec (parse-utc-seconds iat)
        exp-sec (parse-utc-seconds exp)]
    (cond
      (nil? iat-sec) "invalid CACAO iat"
      (> (- iat-sec now-sec) future-skew-sec) "CACAO iat is too far in the future"
      (and exp (nil? exp-sec)) "invalid CACAO exp"
      (and exp-sec (> now-sec exp-sec)) "expired CACAO"
      (and (not exp) (> (- now-sec iat-sec) max-age-sec)) "CACAO max age exceeded"
      :else nil)))

(defn now-sec [] (js/Math.floor (/ (js/Date.now) 1000)))

(defn base64->bytes
  [b64]
  (let [bin (js/atob b64)
        n (aget bin "length")
        out (js/Uint8Array. n)]
    (dotimes [i n]
      (aset out i (.charCodeAt bin i)))
    out))

;; did:key:z6Mk... (Ed25519, multicodec 0xed01) -> raw 32-byte public key.
(defn- did-key->pubkey [did]
  (when-not (and (string? did) (str/starts-with? did "did:key:z"))
    (throw (js/Error. "expected a did:key:z... multibase did")))
  (let [bytes (base58/decode (subs did (count "did:key:z")))]
    (when (or (< (aget bytes "length") 2)
              (not= (aget bytes 0) 0xed)
              (not= (aget bytes 1) 0x01))
      (throw (js/Error. "not an Ed25519 did:key (expected 0xed01 multicodec)")))
    (.slice bytes 2)))

;; The exact EIP-4361 plaintext this actor's own mint (if ever built --
;; see ns docstring, none exists in V2) would sign, and `verify`
;; reconstructs from the CBOR payload. Line-for-line match to upstream's
;; own siwe-message: this must accept/reject exactly what the canonical
;; verifier would.
(defn siwe-message
  [payload]
  (let [iss (aget payload "iss")
        addr (last (str/split iss #":"))
        domain (aget payload "domain")
        aud (aget payload "aud")
        version (aget payload "version")
        nonce (aget payload "nonce")
        iat (aget payload "iat")
        exp (aget payload "exp")
        resources (aget payload "resources")
        lines (atom [(str domain " wants you to sign in with your Ethereum account:")
                     addr ""])]
    (swap! lines conj (str "URI: " aud) (str "Version: " version)
           "Chain ID: 1" (str "Nonce: " nonce) (str "Issued At: " iat))
    (when exp
      (swap! lines conj (str "Expiration Time: " exp)))
    (when (and resources (pos? (aget resources "length")))
      (swap! lines conj "Resources:")
      (doseq [r (array-seq resources)]
        (swap! lines conj (str "- " r))))
    (str/join "\n" @lines)))

(defn verify
  "Verify a base64 CACAO: signature AND temporal window (see
  temporal-error). Returns a Promise of {:valid bool :iss did :payload
  {...}} on success, or {:valid false :error message} on any decode/
  signature/temporal failure -- exception-safe, since this always runs
  against untrusted client input."
  ([cacao-b64] (verify cacao-b64 (now-sec)))
  ([cacao-b64 at-sec]
   (-> (js/Promise.resolve nil)
       (.then
        (fn []
          (let [m (cbor/decode (base64->bytes cacao-b64))
                p (aget m "p")
                s (aget m "s")]
            (when (or (nil? p) (nil? s))
              (throw (js/Error. "malformed CACAO: missing p or s")))
            (let [iss (aget p "iss")
                  payload #js {:iss iss
                               :aud (aget p "aud")
                               :iat (aget p "iat")
                               :exp (aget p "exp")
                               :nonce (aget p "nonce")
                               :domain (aget p "domain")
                               :version (aget p "version")
                               :resources (aget p "resources")}
                  sig-bytes (base64->bytes (aget s "s"))
                  pub-bytes (did-key->pubkey iss)]
              (-> (.importKey js/crypto.subtle "raw" pub-bytes #js {:name "Ed25519"} false #js ["verify"])
                  (.then (fn [key]
                           (let [msg-bytes (.encode (js/TextEncoder.) (siwe-message payload))]
                             (.verify js/crypto.subtle "Ed25519" key sig-bytes msg-bytes))))
                  (.then (fn [sig-valid?]
                           (let [resources-err (resources-shape-error (aget payload "resources"))
                                 temporal-err (temporal-error payload at-sec)]
                             (cond
                               (not sig-valid?) #js {:valid false :iss iss}
                               resources-err #js {:valid false :error resources-err :iss iss}
                               temporal-err #js {:valid false :error temporal-err :iss iss}
                               :else #js {:valid true :iss iss :payload payload})))))))))
       (.catch (fn [error]
                 #js {:valid false :error (aget error "message")})))))
