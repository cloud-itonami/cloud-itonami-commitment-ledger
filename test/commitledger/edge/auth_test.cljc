(ns commitledger.edge.auth-test
  "`commitledger.edge.auth`'s PORTABLE auth-gating logic (CACAO header
  parse, resource-scope matching, lender-identity matching, response
  shaping), exercised with `mock-verifier` -- no real Ed25519/Web-Crypto
  signature crypto anywhere in this test file, by design.

  The REAL crypto path (`commitledger.edge.cacao/verify`, ported
  byte-for-byte from `cloud_itonami.edge.cacao`) is NOT re-tested here.
  It is CLJS-only (`js/crypto.subtle` has no JVM equivalent to port
  without reimplementing Ed25519/CBOR/base58 signature verification from
  scratch -- explicitly out of scope: \"don't reinvent the wire
  format\"), and the sibling repo it was ported from already carries a
  genuine, currently-passing 20-case signature-crypto test suite against
  the SAME algorithm (`gftdcojp/cloud-itonami`'s own `test/cloud_itonami/
  edge/cacao_test.cljc`, `base58_test.cljc`, `cbor_test.cljc`) — which
  itself needs a dedicated CLJS Node test runner
  (`cloud_itonami.edge.cljs-test-runner`, a SEPARATE toolchain from that
  repo's own JVM `clojure -M:test`) precisely because these files have no
  `:clj` branch. This repo deliberately does not stand up a second CLJS
  test toolchain just to re-run the identical, unmodified algorithm a
  second time (see `docs/adr/0002-http-edge-live-registry-
  verification.md`'s Alternatives table) -- what IS re-verified here is
  everything this repo actually ADDS on top: the auth-gating decision
  logic itself."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.auth :as auth]))

;; ----------------------------- verify-cacao-header -----------------------------

(deftest missing-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zLender" :resources ["kotoba://itonami/acme/ramen-cart"]}))
        {:keys [ok? response]} (auth/verify-cacao-header v nil "acme" "ramen-cart")]
    (is (false? ok?))
    (is (= 401 (:status response)))
    (is (= "unauthorized" (get-in response [:body :error])))))

(deftest malformed-authorization-header-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zLender" :resources []}))
        {:keys [ok?]} (auth/verify-cacao-header v "Bearer sometoken" "acme" "ramen-cart")]
    (is (false? ok?))))

(deftest case-insensitive-cacao-scheme
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zLender" :resources ["kotoba://itonami/acme/ramen-cart"]}))]
    (is (true? (:ok? (auth/verify-cacao-header v "cacao abc123" "acme" "ramen-cart"))))
    (is (true? (:ok? (auth/verify-cacao-header v "CACAO abc123" "acme" "ramen-cart"))))))

(deftest invalid-signature-is-401
  (let [v (auth/mock-verifier (fn [_] {:valid? false :error "expired CACAO"}))
        {:keys [ok? response]} (auth/verify-cacao-header v "CACAO abc" "acme" "ramen-cart")]
    (is (false? ok?))
    (is (= 401 (:status response)))
    (is (= "expired CACAO" (get-in response [:body :reason])))))

(deftest wrong-resource-scope-is-403
  (testing "a CACAO minted for a DIFFERENT org/repo cannot be replayed here"
    (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zX" :resources ["kotoba://itonami/other/repo"]}))
          {:keys [ok? response]} (auth/verify-cacao-header v "CACAO abc" "acme" "ramen-cart")]
      (is (false? ok?))
      (is (= 403 (:status response)))
      (is (re-find #"kotoba://itonami/acme/ramen-cart" (get-in response [:body :reason]))))))

(deftest correct-resource-scope-passes
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zX" :resources ["kotoba://itonami/acme/ramen-cart"]}))
        {:keys [ok? iss response]} (auth/verify-cacao-header v "CACAO abc" "acme" "ramen-cart")]
    (is (true? ok?))
    (is (= "did:key:zX" iss))
    (is (nil? response))))

(deftest multi-element-resources-still-matches
  (let [v (auth/mock-verifier (fn [_] {:valid? true :iss "did:key:zX"
                                       :resources ["kotoba://op/*" "kotoba://itonami/acme/ramen-cart"]}))]
    (is (true? (:ok? (auth/verify-cacao-header v "CACAO abc" "acme" "ramen-cart"))))))

;; ----------------------------- resources-scoped-to? (pure) -----------------------------

(deftest resources-scoped-to-exact-match-only
  (is (true? (auth/resources-scoped-to? ["kotoba://itonami/acme/ramen-cart"] "acme" "ramen-cart")))
  (is (false? (auth/resources-scoped-to? ["kotoba://itonami/acme/other-biz"] "acme" "ramen-cart")))
  (is (false? (auth/resources-scoped-to? nil "acme" "ramen-cart")))
  (is (false? (auth/resources-scoped-to? [] "acme" "ramen-cart"))))

;; ----------------------------- require-lender -----------------------------

(def sample-application
  {:id "acme/ramen-cart-abc" :lender {:lender/type :institutional :lender/id "did:key:zLender01" :lender/license-verified true}})

(deftest require-lender-matching-iss-passes
  (let [{:keys [ok? response]} (auth/require-lender "did:key:zLender01" sample-application)]
    (is (true? ok?))
    (is (nil? response))))

(deftest require-lender-mismatched-iss-is-403
  (let [{:keys [ok? response]} (auth/require-lender "did:key:zSomeoneElse" sample-application)]
    (is (false? ok?))
    (is (= 403 (:status response)))))

(deftest require-lender-nil-iss-is-403
  (let [{:keys [ok?]} (auth/require-lender nil sample-application)]
    (is (false? ok?))))

(deftest lender-authorized-predicate
  (is (true? (auth/lender-authorized? "did:key:zLender01" sample-application)))
  (is (false? (auth/lender-authorized? "did:key:zOther" sample-application)))
  (is (false? (auth/lender-authorized? nil sample-application))))
