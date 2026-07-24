(ns commitledger.edge.kotobase-identity
  "Self-mint CACAO identity for THIS actor's calls to kotobase.net
  (kotobase-persistence-migration, docs/adr/0004). REUSES the same
  self-mint identity/seed `commitledger.edge.cacao-mint`/`commitledger.
  edge.isic6492-client` already established for this actor's isic-6492
  outbound calls (`$COMMITMENT_LEDGER_ACTOR_SEED`/`_DID`, a Cloudflare
  Pages secret + public var this actor already has, generated once by
  `scripts/generate-actor-identity.cljs`) -- no new key, no owner
  hand-off, one actor identity used for every outbound self-mint this
  actor ever needs. Only the RESOURCE SCOPE minted differs per
  destination (isic-6492's intake endpoint vs kotobase.net's datomic.*
  XRPC surface).

  Resource scope / aud / domain shape follows `cloud-itonami-isic-8291`'s
  `dossier.kotobase-identity/kotobase-resources`/`default-kotobase-aud`/
  `default-kotobase-domain` byte-for-byte (the closest existing
  precedent for a self-mint-to-kotobase CACAO in this fleet, itself
  following `kotobase-commoncrawl-actor`'s `commoncrawl.identity` and
  `cloud_itonami.identity-core/kotobase-resources`): `kotoba://op/
  datom:read`, `kotoba://op/datom:transact`, `kotoba://can/kotobase:pin`
  (net-kotobase's edge auth gate hardcodes this exact resource string as
  required regardless of the actual op -- see `crm.kotobase/mint-cacao`'s
  docstring, cloud-itonami-isic-5820, for the confirmed live-server
  finding this fleet re-derived independently the same way), `kotoba://
  graph/<db-name>`; `aud \"did:web:kotobase.net\"`, `domain
  \"kotobase.net\"`.

  Graph CID derivation (`canonical-graph`) is a faithful CLJS port of
  `crm.kotobase/canonical-graph` (cloud-itonami-isic-5820,
  ADR-2607184000) -- CIDv1/dag-cbor/sha2-256 of \"kotoba/db/<did>/<db-
  name>\", multibase 'b'-prefixed base32-lower, cross-checked byte-for-
  byte against that JVM implementation's own worked example in this
  ns's test. Necessarily ASYNC here (unlike the JVM version's
  synchronous `MessageDigest`) since `js/crypto.subtle.digest` returns a
  Promise -- every caller of `canonical-graph`/`mint-kotobase-cacao!`
  below threads through a real Promise under `:cljs`.

  CLJS-only (js/crypto.subtle, matching `commitledger.edge.cacao-mint`'s
  own platform constraint -- see that ns's docstring for why there is no
  JVM branch: reimplementing Ed25519/SHA-256 wire format on the JVM
  would violate this fleet's own \"do not reinvent the wire format\"
  rule; `clojure -M:dev:test` is JVM-only by design here, so this ns's
  own JVM test is a documented no-op stub, exactly `commitledger.edge.
  cacao-mint-test`'s own precedent -- the real round-trip runs via
  `scripts/verify-kotobase-identity-roundtrip.cljs` under nbb).

  NOTE the `:require` clause below is itself `:cljs`-only (a whole
  reader-conditional `(:require ...)` clause, not a spliced list of
  specs) -- unlike `commitledger.edge.isic6492-client`/`cacao-mint-
  test`'s own pattern (a FIXED, always-present require alongside a
  `#?@(:cljs [...] :clj [])` splice), this ns has NOTHING it needs to
  require on `:clj` at all, and a `:require` clause with zero specs
  (`#?@(:clj [])` splicing into an otherwise-empty `:require`) is
  rejected by `clojure.core.specs.alpha/ns-form` -- confirmed directly
  while writing this ns. A conditional `(:require ...)` clause with no
  `:clj` branch simply omits the WHOLE clause under `:clj`, which is
  valid (a `ns` form need not have a `:require` clause at all)."
  #?(:cljs (:require [commitledger.edge.cacao :as cacao]
                     [commitledger.edge.cacao-mint :as mint]
                     [commitledger.edge.cbor :as cbor]
                     [commitledger.edge.isic6492-client :as isic6492])))

(def default-kotobase-aud
  "net-kotobase's pod enforces aud == did:web:kotobase.net (confirmed,
  dossier.kotobase-identity/default-kotobase-aud) -- a mismatch is
  rejected with 'cacao audience mismatch'."
  "did:web:kotobase.net")

(def default-kotobase-domain "kotobase.net")

(def default-db-name
  "This actor's kotobase.net tenant database name -- the commitment-
  ledger application/ledger/commitment/tranche-release history."
  "commitment-ledger")

(defn kotobase-resources
  "CACAO resource scope for a kotobase.net graph read/write.

  BUGFIX (kotobase-persistence-migration, docs/adr/0004): `:graph`
  resource MUST be this actor's OWN did:key, NOT a computed CID (this
  ns's own `canonical-graph`, which followed `crm.kotobase/canonical-
  graph`'s ADR-2607184000-documented derivation) and NOT `db-name`.
  Confirmed directly against production, 2026-07-24, by reading
  `kotoba-lang/kotobase-client`'s `kotobase.cacao.cljs` (the client SDK
  net-kotobase's own edge is built to accept): its own docstring states
  the 'apex' (kotobase.net clj-edge/cf-wasm) profile, 'live-probed
  2026-07-09', requires ':graph = the issuer did:key' -- NOT a derived
  graph CID the way the legacy pod/tenant-worker profile (and `crm.
  kotobase`'s own `canonical-graph`, ADR-2607184000) uses. A live write
  scoped to `(str \"kotoba://graph/\" (canonical-graph-CID ...))`
  consistently 401'd; the SAME write scoped to `(str \"kotoba://graph/\"
  did)` succeeded (HTTP 200, real `datom_count`/`novelty_size` in the
  response) -- this fn now reflects that confirmed-live shape. The op
  capability prefix (`kotoba://can/<op>` vs the earlier `kotoba://op/
  <op>`) does not appear to matter to net-kotobase's own gate (`kotoba-
  cf-wasm.auth/validate-payload` only checks for the literal `kotoba://
  can/kotobase:pin` string, not the op resource's own shape) but this fn
  uses `can/` throughout to match `kotobase.cacao.cljs`'s own
  convention exactly, since that IS the confirmed-live one.

  `did` is this actor's own did:key (the CACAO's `iss`); `op` is
  \"datom:read\" or \"datom:transact\" (callers mint a SEPARATE CACAO
  per direction, same as before -- `kotobase-store`'s read/write conns
  each call this with their own `op`)."
  [did op]
  ["kotoba://can/kotobase:pin"
   (str "kotoba://can/" op)
   (str "kotoba://graph/" did)])

;; ───────── graph CID (byte-identical port of crm.kotobase/canonical-graph) ────

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

#?(:cljs
   (defn- base32-lower-no-pad
     "js/Uint8Array -> multibase base32-lower (no padding) string --
     ported from `crm.kotobase/base32-lower-no-pad` (JVM `StringBuilder`
     loop) to a portable `reduce`, matching this repo's OWN byte-
     manipulation idiom (`commitledger.edge.base58`'s `array-seq
     (js/Array.from bytes)` convention, not `seq` directly on a typed
     array)."
     [bytes]
     (let [{:keys [bits value chars]}
           (reduce
            (fn [{:keys [bits value chars]} b]
              (let [b (bit-and (int b) 0xff)
                    value (bit-or (bit-shift-left value 8) b)
                    bits (+ bits 8)]
                (loop [bits bits value value chars chars]
                  (if (>= bits 5)
                    (recur (- bits 5) value
                           (conj chars (nth b32 (bit-and (unsigned-bit-shift-right value (- bits 5)) 31))))
                    {:bits bits :value value :chars chars}))))
            {:bits 0 :value 0 :chars []}
            (array-seq (js/Array.from bytes)))
           chars (if (pos? bits)
                   (conj chars (nth b32 (bit-and (bit-shift-left value (- 5 bits)) 31)))
                   chars)]
       (apply str chars))))

#?(:cljs
   (defn- sha256
     "js/Uint8Array -> promise-like of a 32-byte js/Uint8Array digest."
     [bytes]
     (.then (js/crypto.subtle.digest "SHA-256" bytes) (fn [buf] (js/Uint8Array. buf)))))

#?(:cljs
   (defn graph-cid-from-name
     "CIDv1/dag-cbor/sha2-256 of `name`, multibase 'b'-prefixed
     base32-lower -> promise-like of the CID string. Matches
     `crm.kotobase/graph-cid-from-name`/`kotobase.graph/graph-cid-from-
     name` (net-kotobase clj-edge) byte-for-byte."
     [name]
     (let [utf8 (.encode (js/TextEncoder.) name)]
       (.then (sha256 utf8)
              (fn [hash]
                (let [prefix (js/Uint8Array.from #js [0x01 0x71 0x12 0x20])
                      cid (js/Uint8Array. (+ (.-length prefix) (.-length hash)))]
                  (.set cid prefix 0)
                  (.set cid hash (.-length prefix))
                  (str "b" (base32-lower-no-pad cid))))))))

#?(:cljs
   (defn canonical-graph
     "This actor's deterministic graph CID for `db-name` under `did` ->
     promise-like of the CID string -- the edge (kotobase.net) recomputes
     exactly this from the DID + db-name on every tenant write."
     [did db-name]
     (graph-cid-from-name (str "kotoba/db/" did "/" db-name))))

;; ───────── seed / identity (reuses commitledger.edge.isic6492-client's) ────

#?(:cljs
   (defn signing-key-from-env
     "env -> promise-like of `{:did :sign-fn}` (a `commitledger.edge.
     cacao-mint/mint`-compatible sign-fn closing over the imported
     CryptoKey), reusing `$COMMITMENT_LEDGER_ACTOR_SEED`/
     `$COMMITMENT_LEDGER_ACTOR_DID` (this actor's ALREADY-provisioned
     self-mint identity) via `commitledger.edge.isic6492-client/import-
     signing-key` -- fail-soft nil when unset (same convention as that
     ns's own `live-client-from-env`)."
     [env]
     (let [seed-b64 (aget env "COMMITMENT_LEDGER_ACTOR_SEED")
           did (aget env "COMMITMENT_LEDGER_ACTOR_DID")]
       (if (or (not seed-b64) (not did))
         (js/Promise.resolve nil)
         (.then (isic6492/import-signing-key seed-b64 did)
                (fn [priv-key]
                  {:did did
                   :sign-fn (fn [msg-bytes] (.sign js/crypto.subtle "Ed25519" priv-key msg-bytes))}))))))

#?(:cljs
   (defn- iso8601-seconds
     "epoch-ms -> strict `YYYY-MM-DDTHH:MM:SSZ` (no fractional seconds).
     Mirrors `dossier.kotobase-identity/iso8601-seconds` -- the exact
     format kotobase-server's CACAO verifier requires for BOTH `iat` and
     `exp`."
     [epoch-ms]
     (.replace (.toISOString (js/Date. epoch-ms)) #"\.\d{3}Z$" "Z")))

;; ───────── kotobase.net-specific CBOR envelope ──────────────────────────────
;;
;; BUGFIX (kotobase-persistence-migration, docs/adr/0004): net-kotobase's
;; edge auth gate (`kotobase-cf-wasm.auth/verify-cacao`, the actual code
;; behind kotobase.net's XRPC) REQUIRES the signature sub-map to carry
;; `"t": "EdDSA"` --
;;   (if (or (nil? p) (not= (aget s "t") "EdDSA") (nil? (aget s "s")))
;;     (err "invalid CACAO payload") ...)
;; -- which `commitledger.edge.cacao-mint/mint` + `commitledger.edge.cbor/
;; encode-cacao-envelope` never included (that pair was built for, and
;; remains correct for, `commitledger.edge.cacao/verify` -- THIS repo's
;; own, more lenient CLIENT-facing verifier for borrower/lender CACAOs,
;; which never reads `s.t` at all). Confirmed directly, 2026-07-24: a
;; CACAO minted via the existing `mint`/`encode-cacao-envelope` pair
;; verified TRUE against both `commitledger.edge.cacao/verify` (this
;; repo's own) AND `cacao.core/verify` (kotoba-lang/org-chainagnostic-
;; cacao, the canonical portable library) -- ruling out a broken
;; signature or SIWE-message mismatch -- yet kotobase.net itself
;; rejected it with a generic 401 "Unauthorized" on every call, traced
;; by reading `kotobase-cf-wasm.auth.cljs` (net-kotobase, read-only,
;; not modified) to exactly this missing field. `commitledger.edge.
;; cacao-mint/mint` + `encode-cacao-envelope` are UNCHANGED (still
;; correct, still used, for this actor's EXISTING isic-6492 calls) --
;; this ns builds its OWN envelope for kotobase.net calls only, reusing
;; `commitledger.edge.cacao/siwe-message` (the signed plaintext
;; construction, confirmed byte-compatible) and `commitledger.edge.
;; cbor/header`+`encode-text`+`encode-map` (widened from private to
;; public for exactly this reuse -- see those fns' own docstrings).
;;
;; Also encodes the `p` map's keys in DAG-CBOR CANONICAL order (sorted by
;; byte-length then lexicographically -- RFC 8949 core deterministic
;; encoding, which `@ipld/dag-cbor` -- net-kotobase's decoder -- both
;; PRODUCES on encode and may reject non-canonical input on decode) --
;; defensive, since the missing `s.t` field alone was already a
;; sufficient, confirmed explanation, but this repo's own hand-rolled
;; `encode-cacao-envelope`/`encode-map` never sorted keys at all
;; (insertion order only) and there is no confirmation `@ipld/dag-cbor`
;; tolerates that.

#?(:cljs
   (defn- canonical-sort-pairs
     "`[[k v] ...]` -> the same pairs sorted by DAG-CBOR's core
     deterministic key order (byte-length, then bytewise-lexicographic --
     equivalent to plain length-then-lexicographic for the ASCII keys
     this envelope ever uses)."
     [pairs]
     (sort-by (fn [[k _]] [(count k) k]) pairs)))

#?(:cljs
   (defn- encode-kotobase-cacao-envelope
     "`{\"h\": {\"t\": \"caip122\"}, \"p\": <p-pairs, CANONICALLY sorted>,
     \"s\": {\"t\": \"EdDSA\", \"s\": sig-b64}}`, as a plain Clojure vector
     of byte ints (matching `commitledger.edge.cbor/encode-cacao-
     envelope`'s own return-shape convention)."
     [p-pairs sig-b64]
     (into (cbor/header 5 3)
           (concat (cbor/encode-text "h") (cbor/encode-map [["t" "caip122"]])
                   (cbor/encode-text "p") (cbor/encode-map (canonical-sort-pairs p-pairs))
                   (cbor/encode-text "s") (cbor/encode-map (canonical-sort-pairs [["t" "EdDSA"] ["s" sig-b64]]))))))

#?(:cljs
   (defn mint-kotobase-cacao!
     "`{:did :sign-fn}` (from `signing-key-from-env`), `op` (\"datom:read\"
     or \"datom:transact\" -- see `kotobase-resources`'s docstring for
     why the resource scope is per-op, not per-db-name) -> promise-like
     of a base64 CACAO string, `aud`/`domain` fixed to kotobase.net's
     pod requirements (see ns docstring). A FRESH CACAO every call --
     never cache/reuse one across requests (kotobase-server's nonce-
     replay protection 401s a second `datomic.transact` presenting the
     same CACAO's nonce, confirmed against production, ADR-2607184000
     --- `commitledger.edge.kotobase-store`'s write path mints one per
     `:transact!` call for exactly this reason; reads reuse a single
     mint safely, same as that ADR's `crm.kotobase/kotobase-store`).

     Builds the SIWE plaintext via `commitledger.edge.cacao/siwe-
     message` (confirmed byte-compatible -- see ns docstring) and signs
     it directly with `sign-fn`, then assembles the envelope via THIS
     ns's own `encode-kotobase-cacao-envelope` (NOT `commitledger.edge.
     cacao-mint/mint`'s -- see that fn's own docstring for exactly why:
     the shared one is missing kotobase.net's required `s.t` field)."
     [{:keys [did sign-fn]} op]
     (let [now-ms (js/Date.now)
           ttl-sec 3600
           iat (iso8601-seconds now-ms)
           exp (iso8601-seconds (+ now-ms (* ttl-sec 1000)))
           nonce (str (js/Math.floor (* (js/Math.random) 1e12)))
           resources (kotobase-resources did op)
           payload #js {:iss did :aud default-kotobase-aud :iat iat :exp exp
                        :nonce nonce :domain default-kotobase-domain :version "1"
                        :resources (clj->js resources)}
           msg (cacao/siwe-message payload)
           msg-bytes (.encode (js/TextEncoder.) msg)]
       (.then (sign-fn msg-bytes)
              (fn [sig-ab]
                (let [sig-b64 (mint/bytes->base64 (js/Uint8Array. sig-ab))
                      p-pairs [["iss" did] ["aud" default-kotobase-aud] ["iat" iat] ["exp" exp]
                               ["nonce" nonce] ["domain" default-kotobase-domain] ["version" "1"]
                               ["resources" resources]]
                      envelope-bytes (encode-kotobase-cacao-envelope p-pairs sig-b64)]
                  (mint/bytes->base64 (js/Uint8Array.from (clj->js envelope-bytes)))))))))
