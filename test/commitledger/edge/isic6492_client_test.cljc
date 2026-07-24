(ns commitledger.edge.isic6492-client-test
  "`commitledger.edge.isic6492-client`'s MOCK-based contract -- the
  protocol shape `commitledger.operation`'s `:commit` node actually
  depends on (`-intake-loan-application` never throws, always resolves
  to `{:ok? bool :id str|nil :error str|nil}`), plus `->intake-payload`'s
  pure field-selection (the exact 4 fields isic-6492 understands, never
  `:personal-pledge`). The REAL `LiveIsic6492Client` (`:cljs`-only --
  self-mint identity import, real `js/fetch`) is not exercised here --
  same reasoning as every other `Live*` wiring in this ns family
  (`commitledger.edge.auth-test`'s ns docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.isic6492-client :as isic6492]))

(def sample-application
  {:id "acme/ramen-cart-abc" :borrower-org-repo "acme/ramen-cart"
   :borrower-did "did:key:z6MkAcmeRamenCart01" :requested-principal 300000
   :purpose "working capital" :jurisdiction "JPN"
   :personal-pledge {:milestone-report-cadence "monthly"}
   :lender {:lender/type :institutional :lender/id "did:key:zLender01"}})

(deftest mock-client-default-resolves-ok
  (let [c (isic6492/mock-client)
        result (isic6492/-intake-loan-application c sample-application)]
    (is (true? (:ok? result)))
    (is (string? (:id result)))))

(deftest always-ok-client-returns-given-id
  (let [c (isic6492/always-ok-client "isic6492-app-42")
        result (isic6492/-intake-loan-application c sample-application)]
    (is (true? (:ok? result)))
    (is (= "isic6492-app-42" (:id result)))))

(deftest always-failing-client-never-throws
  (testing "a failure resolves cleanly, it never throws -- see ns docstring"
    (let [c (isic6492/always-failing-client "isic-6492 intake failed: HTTP 500")
          result (isic6492/-intake-loan-application c sample-application)]
      (is (false? (:ok? result)))
      (is (= "isic-6492 intake failed: HTTP 500" (:error result))))))

(deftest custom-result-fn-client
  (let [c (isic6492/mock-client (fn [app] {:ok? true :id (str "wrapped-" (:id app))}))
        result (isic6492/-intake-loan-application c sample-application)]
    (is (= "wrapped-acme/ramen-cart-abc" (:id result)))))

;; ----------------------------- ->intake-payload (pure) -----------------------------

(deftest intake-payload-selects-only-isic6492-understood-fields
  (let [payload (isic6492/->intake-payload sample-application)]
    (is (= 300000 (:requested-principal payload)))
    (is (= "JPN" (:jurisdiction payload)))
    (is (= "acme/ramen-cart" (:borrower-org-repo payload)))
    (is (= "working capital" (:purpose payload)))
    (is (not (contains? payload :personal-pledge))
        "isic-6492 has no schema for personal-pledge -- must never be sent")
    (is (not (contains? payload :lender))
        "lender identity is commitment-ledger-specific, never sent")
    (is (not (contains? payload :borrower-did))
        "borrower-did is commitment-ledger-specific, never sent")
    (is (= 4 (count payload)) "exactly the 4 documented fields, nothing more")))
