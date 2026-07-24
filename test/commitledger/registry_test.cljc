(ns commitledger.registry-test
  (:require [clojure.test :refer [deftest is]]
            [commitledger.registry :as r]))

;; ----------------------------- rate-ceiling-for-principal -----------------------------

(deftest rate-ceiling-tiers-match-risoku-seigen-hou-article-1
  (is (= 0.20 (r/rate-ceiling-for-principal 50000)))
  (is (= 0.20 (r/rate-ceiling-for-principal 99999)))
  (is (= 0.18 (r/rate-ceiling-for-principal 100000)))
  (is (= 0.18 (r/rate-ceiling-for-principal 999999)))
  (is (= 0.15 (r/rate-ceiling-for-principal 1000000)))
  (is (= 0.15 (r/rate-ceiling-for-principal 5000000))))

;; ----------------------------- individual-lender constants -----------------------------

(deftest individual-lender-principal-cap-mirrors-tier-1-threshold
  (is (= r/rate-ceiling-tier-1-max r/individual-lender-principal-cap)))

(deftest individual-lender-loan-count-threshold-is-three
  (is (= 3 r/individual-lender-loan-count-threshold)))

;; ----------------------------- compute-capacity-ratio -----------------------------

(deftest capacity-ratio-is-existing-debt-plus-requested-over-income
  (is (= 0.25 (r/compute-capacity-ratio {:existing-debt 500000 :requested-principal 500000 :annual-income 4000000})))
  (is (= 0.875 (r/compute-capacity-ratio {:existing-debt 500000 :requested-principal 3000000 :annual-income 4000000}))))

(deftest capacity-ratio-falls-back-to-declared-repayment-capacity
  (is (= 0.5 (r/compute-capacity-ratio {:existing-debt 0 :requested-principal 500000 :annual-income nil
                                        :declared-repayment-capacity 1000000}))))

(deftest capacity-ratio-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-capacity-ratio {:existing-debt 0 :requested-principal 0 :annual-income 0})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-capacity-ratio {:existing-debt -1 :requested-principal 0 :annual-income 100})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-capacity-ratio {:existing-debt 0 :requested-principal -1 :annual-income 100}))))

(deftest capacity-ratio-ceiling-is-a-real-well-known-constant
  (is (= 0.43 r/capacity-ratio-ceiling)))

;; ----------------------------- equity-language? -----------------------------

(deftest equity-language-detects-blocklist-tokens
  (is (r/equity-language? "in exchange for an equity stake"))
  (is (r/equity-language? "cap table allocation"))
  (is (r/equity-language? "配当を希望します"))
  (is (not (r/equity-language? "working capital for ramen cart ingredients")))
  (is (not (r/equity-language? nil)))
  (is (not (r/equity-language? ""))))

;; ----------------------------- personal-pledge-complete? -----------------------------

(deftest personal-pledge-complete-requires-all-fields-non-blank
  (is (r/personal-pledge-complete? {:milestone-report-cadence "monthly"
                                    :mentor-checkin-commitment "biweekly"
                                    :progress-report-obligation "quarterly"}))
  (is (not (r/personal-pledge-complete? {:milestone-report-cadence "monthly"
                                         :mentor-checkin-commitment ""
                                         :progress-report-obligation "quarterly"})))
  (is (not (r/personal-pledge-complete? {:milestone-report-cadence "monthly"})))
  (is (not (r/personal-pledge-complete? nil))))

;; ----------------------------- borrower-ref-well-formed? -----------------------------

(deftest borrower-ref-well-formed-requires-org-slash-repo-and-did
  (is (r/borrower-ref-well-formed? "acme/ramen-cart" "did:key:z6MkAcme01"))
  (is (not (r/borrower-ref-well-formed? "" "did:key:z6MkAcme01")))
  (is (not (r/borrower-ref-well-formed? "acme/ramen-cart" nil)))
  (is (not (r/borrower-ref-well-formed? "acme/ramen-cart" "")))
  (is (not (r/borrower-ref-well-formed? "acmeramen-cart" "did:key:z6MkAcme01")))
  (is (not (r/borrower-ref-well-formed? "acme/ramen-cart" "not-a-did"))))

;; ----------------------------- register-commitment -----------------------------

(deftest commitment-record-is-a-draft-not-a-real-disbursement
  (let [result (r/register-commitment "app-1" "did:key:zLender" 300000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest commitment-record-assigns-commitment-number
  (let [result (r/register-commitment "app-1" "did:key:zLender" 300000 "JPN" 7)]
    (is (= (get result "commitment_number") "JPN-COMMIT-000007"))
    (is (= (get-in result ["record" "application_id"]) "app-1"))
    (is (= (get-in result ["record" "lender_id"]) "did:key:zLender"))
    (is (= (get-in result ["record" "principal"]) 300000))
    (is (= (get-in result ["record" "kind"]) "commitment-record-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest commitment-record-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-commitment "" "did:key:zLender" 300000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-commitment "app-1" "" 300000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-commitment "app-1" "did:key:zLender" -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-commitment "app-1" "did:key:zLender" 300000 "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-commitment "app-1" "did:key:zLender" 300000 "JPN" -1))))

;; ----------------------------- register-tranche-release -----------------------------

(deftest tranche-release-is-a-draft-not-a-real-transfer
  (let [result (r/register-tranche-release "app-1" 0 150000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest tranche-release-assigns-release-number
  (let [result (r/register-tranche-release "app-1" 1 150000 "JPN" 3)]
    (is (= (get result "release_number") "JPN-TRANCHE-000003"))
    (is (= (get-in result ["record" "tranche_index"]) 1))
    (is (= (get-in result ["record" "amount"]) 150000))
    (is (= (get-in result ["record" "kind"]) "tranche-release-draft"))))

(deftest tranche-release-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-tranche-release "" 0 150000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-tranche-release "app-1" -1 150000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-tranche-release "app-1" 0 -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-tranche-release "app-1" 0 150000 "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-tranche-release "app-1" 0 150000 "JPN" -1))))

;; ----------------------------- append -----------------------------

(deftest history-is-append-only
  (let [d1 (r/register-commitment "app-1" "did:key:zLender" 300000 "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-commitment "app-2" "did:key:zLender" 100000 "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-COMMIT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-COMMIT-000001" (get-in hist2 [1 "record_id"])))))
