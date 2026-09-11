(ns commitledger.edge.registrylookup-test
  "`commitledger.edge.registrylookup`'s injection-seam contract --
  `MockLookup` only (the real `LiveLookup` is `:cljs`-only `js/fetch`
  interop with no JVM branch, exercised at deploy time / by the sibling
  repo's own live `GET /api/open-business` endpoint it calls, never by
  this repo's own test suite -- see `commitledger.edge.auth-test`'s ns
  docstring for the identical reasoning applied to CACAO verify)."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.registrylookup :as lookup]))

(deftest mock-lookup-true-case
  (testing "a claimed {org}/{repo} resolves self-registered? true"
    (let [l (lookup/mock-lookup {"acme/ramen-cart" true})]
      (is (true? (lookup/check-registration l "acme" "ramen-cart"))))))

(deftest mock-lookup-false-case
  (testing "an unclaimed/absent {org}/{repo} resolves self-registered? false, fail-closed"
    (let [l (lookup/mock-lookup {"acme/ramen-cart" true})]
      (is (false? (lookup/check-registration l "acme" "some-other-biz")))
      (is (false? (lookup/check-registration l "unrelated" "repo"))))))

(deftest mock-lookup-empty-answers-defaults-to-false
  (let [l (lookup/mock-lookup)]
    (is (false? (lookup/check-registration l "acme" "ramen-cart")))))

(deftest check-registration-org-repo-are-joined-with-a-slash
  (testing "matches gftdcojp/cloud-itonami's own id shape -- {org}/{repo}, one slash"
    (let [l (lookup/mock-lookup {"cloud-itonami/cloud-itonami-commitment-ledger" true})]
      (is (true? (lookup/check-registration l "cloud-itonami" "cloud-itonami-commitment-ledger")))
      (is (false? (lookup/check-registration l "cloud-itonami" "cloud-itonami-commitment-ledger-typo"))))))
