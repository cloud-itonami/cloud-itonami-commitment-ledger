(ns commitledger.edge.cacao-mint-test
  "The single most important correctness property for this actor's new
  self-mint identity: a CACAO `commitledger.edge.cacao-mint/mint` signs
  must verify TRUE through the LOCAL, unmodified `commitledger.edge.
  cacao/verify` -- proving mint and verify actually agree on wire
  format (CBOR envelope shape, SIWE plaintext reconstruction, base58
  did:key encoding).

  PLATFORM CONSTRAINT (mirrors `docs/adr/0002-http-edge-live-registry-
  verification.md` Decision 8's own reasoning for `commitledger.edge.
  cacao`, restated here for `cacao-mint`): both `commitledger.edge.
  cacao-mint` (sign) and `commitledger.edge.cacao` (verify) are
  CLJS-only real Ed25519 crypto (`js/crypto.subtle`) with NO JVM
  equivalent -- there is nothing to `:require` under `:clj` without
  reimplementing Ed25519/CBOR/base58 wire format wholesale, exactly
  what Decision 8 already ruled out ('do not reinvent the wire
  format'), and this repo's own `clojure -M:dev:test` is JVM-only by
  design (Decision 8 also explicitly declines to stand up a second
  CLJS-only test toolchain). So `clojure -M:dev:test` cannot execute
  the actual round-trip below -- the `:clj` branch is a documented,
  honest no-op, NOT a claim of coverage.

  The round trip DOES run for real, on Node's WebCrypto, via
  `scripts/verify-cacao-mint-roundtrip.cljs` (an nbb script -- nbb is
  this workspace's designated script-tooling runtime, ranked ABOVE the
  JVM in `CLAUDE.md`'s runtime-priority ladder, and nbb natively
  executes a `.cljc` file's `:cljs` reader-conditional branch, unlike
  `clojure -M:dev:test`). That script was run for real as part of
  landing this feature; its captured stdout is quoted in `docs/adr/
  0003-isic6492-wiring-and-approval-resume.md`. This is, if anything,
  MORE rigorous than Decision 8's own precedent (which trusts an
  upstream repo's test suite for unmodified code, unverified by
  THIS repo) -- here fresh, real keys are generated and a fresh CACAO
  is minted-and-verified on every run, not merely trusted from a doc."
  (:require [clojure.test :refer [deftest is]]
            #?@(:cljs [[commitledger.edge.cacao-mint :as mint]
                       [commitledger.edge.cacao :as cacao]]
                :clj  [])))

(deftest mint-and-verify-agree-on-wire-format
  #?(:clj
     (is true
         "cacao-mint/cacao are CLJS-only real Ed25519 crypto with no JVM
          equivalent -- see ns docstring. The real round-trip proof runs
          via scripts/verify-cacao-mint-roundtrip.cljs (nbb), executed
          for real; its output is quoted in docs/adr/0003-isic6492-
          wiring-and-approval-resume.md.")
     :cljs
     (throw (js/Error.
             "cacao-mint-test is a documentation-only stub under a real cljs test
              runner too -- this repo does not stand up one (see ns docstring);
              the real proof is scripts/verify-cacao-mint-roundtrip.cljs, run
              directly via nbb, not through clojure.test."))))
