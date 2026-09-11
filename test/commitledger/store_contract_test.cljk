(ns commitledger.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `credit.store-contract-test`
  (`cloud-itonami-isic-6492`) for the same pattern on a sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "acme/ramen-cart" (:borrower-org-repo (store/application s "app-clean"))))
      (is (= "JPN" (:jurisdiction (store/application s "app-clean"))))
      (is (= 300000 (:requested-principal (store/application s "app-clean"))))
      (is (= :institutional (:lender/type (:lender (store/application s "app-clean")))))
      (is (= [150000 150000] (:tranche-schedule (store/application s "app-clean"))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/commitment-history s)))
      (is (= [] (store/tranche-release-history s)))
      (is (= #{} (store/released-tranches-of s "app-clean")))
      (is (zero? (store/next-commitment-sequence s "JPN")))
      (is (zero? (store/next-tranche-sequence s "JPN")))
      (is (false? (store/application-already-committed? s "app-clean")))
      (is (false? (store/tranche-already-released? s "app-clean" 0)))
      (is (zero? (store/individual-lender-commitment-count s "did:key:z6MkIndividualLenderRepeat"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert
                                 :value {:id "app-clean" :requested-principal 310000}})
        (is (= 310000 (:requested-principal (store/application s "app-clean"))))
        (is (= "acme/ramen-cart" (:borrower-org-repo (store/application s "app-clean"))) "borrower ref preserved"))
      (testing "commitment/record drafts a commitment record and advances the sequence"
        (store/commit-record! s {:effect :commitment/mark-recorded :path ["app-clean"]})
        (is (= "JPN-COMMIT-000000" (get (first (store/commitment-history s)) "record_id")))
        (is (= "commitment-record-draft" (get (first (store/commitment-history s)) "kind")))
        (is (= "did:key:z6MkInstitutionalBank01" (get (first (store/commitment-history s)) "lender_id")))
        (is (= :committed (:status (store/application s "app-clean"))))
        (is (= 1 (count (store/commitment-history s))))
        (is (= 1 (store/next-commitment-sequence s "JPN")))
        (is (true? (store/application-already-committed? s "app-clean")))
        (is (false? (store/application-already-committed? s "app-no-spec")))
        (is (= 1 (store/individual-lender-commitment-count s "did:key:z6MkInstitutionalBank01"))))
      (testing "commitment/tranche-release drafts a release and marks it released"
        (store/commit-record! s {:effect :commitment/mark-tranche-released :path ["app-clean"]
                                 :value {:tranche-index 0}})
        (is (= "JPN-TRANCHE-000000" (get (first (store/tranche-release-history s)) "record_id")))
        (is (= 0 (get (first (store/tranche-release-history s)) "tranche_index")))
        (is (= 150000 (get (first (store/tranche-release-history s)) "amount")))
        (is (true? (store/tranche-already-released? s "app-clean" 0)))
        (is (false? (store/tranche-already-released? s "app-clean" 1)))
        (is (= 1 (count (store/tranche-release-history s))))
        (is (= 1 (store/next-tranche-sequence s "JPN"))))
      (testing "a second tranche release for a DIFFERENT index does not collide with the first"
        (store/commit-record! s {:effect :commitment/mark-tranche-released :path ["app-clean"]
                                 :value {:tranche-index 1}})
        (is (true? (store/tranche-already-released? s "app-clean" 1)))
        (is (= 2 (count (store/tranche-release-history s)))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/application s "nope")))
    (is (= [] (store/all-applications s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/commitment-history s)))
    (is (= [] (store/tranche-release-history s)))
    (is (zero? (store/next-commitment-sequence s "JPN")))
    (store/with-applications s {"x" {:id "x" :borrower-org-repo "acme/x" :borrower-did "did:key:z6MkX01"
                                     :requested-principal 100000 :purpose "n"
                                     :existing-debt 0 :annual-income 1000000
                                     :proposed-term-months 6
                                     :personal-pledge {:milestone-report-cadence "monthly"
                                                       :mentor-checkin-commitment "biweekly"
                                                       :progress-report-obligation "quarterly"}
                                     :lender {:lender/type :institutional :lender/id "did:key:z6MkLenderX01" :lender/license-verified true}
                                     :proposed-rate 0.1 :jurisdiction "JPN" :status :intake
                                     :tranche-schedule [100000]}})
    (is (= "acme/x" (:borrower-org-repo (store/application s "x"))))))
