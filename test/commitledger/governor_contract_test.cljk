(ns commitledger.governor-contract-test
  "The governor contract as executable tests -- the commitment-ledger
  analog of `credit.governor-contract-test` (`cloud-itonami-isic-6492`)
  and `factoring.governor-contract-test` (`cloud-itonami-isic-6493`).
  The single invariant under test:

    Commitment-LLM never records a commitment or releases a tranche the
    CommitmentLedgerGovernor would reject, NEITHER `:commitment/record`
    NOR `:commitment/tranche-release` ever auto-commits at any phase,
    `:application/intake` (no capital risk) MAY auto-commit when clean,
    and every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [commitledger.store :as store]
            [commitledger.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :platform-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- record! [actor subject tid]
  (exec-op actor tid {:op :commitment/record :subject subject} operator)
  (approve! actor tid))

;; ----------------------------- clean end-to-end path -----------------------------

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :application/intake :subject "app-clean"
                   :patch {:id "app-clean" :requested-principal 305000}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 305000 (:requested-principal (store/application db "app-clean"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest commitment-record-always-needs-approval-then-commits
  (testing "record is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :commitment/record :subject "app-clean"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :committed (:status (store/application db "app-clean"))))
        (is (= 1 (count (store/commitment-history db))))))))

(deftest tranche-release-clean-path-commits-then-double-release-is-held
  (testing "(a) release commits when pledge-milestone evidence present and tranche not already released"
    (let [[db actor] (fresh)
          _ (record! actor "app-clean" "t3pre")
          ev {:milestone-met? true :evidence "month-1 sales report + mentor sign-off"}
          r1 (exec-op actor "t3" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 0 :milestone-evidence ev} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (store/tranche-already-released? db "app-clean" 0)))
        (is (= 1 (count (store/tranche-release-history db))))))
    (testing "(b) a double-release attempt on the same tranche is blocked, never reaches a human"
      (let [[db actor] (fresh)
            _ (record! actor "app-clean" "t4pre")
            ev {:milestone-met? true :evidence "month-1 sales report + mentor sign-off"}
            _ (exec-op actor "t4a" {:op :commitment/tranche-release :subject "app-clean"
                                    :tranche-index 0 :milestone-evidence ev} operator)
            _ (approve! actor "t4a")
            res (exec-op actor "t4" {:op :commitment/tranche-release :subject "app-clean"
                                     :tranche-index 0 :milestone-evidence ev} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (not= :interrupted (:status res)) "settles immediately, no interrupt")
        (is (some #{:tranche-already-released} (-> (store/ledger db) last :basis)))
        (is (= 1 (count (store/tranche-release-history db))) "still only the one earlier release")))))

;; ----------------------------- check 1: spec-basis-missing -----------------------------

(deftest check-1-spec-basis-missing-is-held
  (testing "an unregistered jurisdiction -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :commitment/record :subject "app-no-spec"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:spec-basis-missing} (-> (store/ledger db) first :basis)))
      (is (false? (store/application-already-committed? db "app-no-spec"))))))

;; ----------------------------- check 2: institutional-lender-license-not-verified -----------------------------

(deftest check-2-institutional-lender-license-not-verified-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t6" {:op :commitment/record :subject "app-unlicensed"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:institutional-lender-license-not-verified} (-> (store/ledger db) first :basis)))
    (is (false? (store/application-already-committed? db "app-unlicensed")))))

;; ----------------------------- check 3: rate-ceiling-exceeded -----------------------------

(deftest check-3-rate-ceiling-exceeded-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t7" {:op :commitment/record :subject "app-rate-exceeded"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:rate-ceiling-exceeded} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 4: individual-lender-not-interest-free -----------------------------

(deftest check-4-individual-lender-not-interest-free-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t8" {:op :commitment/record :subject "app-individual-interest"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:individual-lender-not-interest-free} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 5: individual-lender-principal-exceeds-cap -----------------------------

(deftest check-5-individual-lender-principal-exceeds-cap-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t9" {:op :commitment/record :subject "app-individual-cap"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:individual-lender-principal-exceeds-cap} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 6: individual-lender-loan-count-exceeds-threshold -----------------------------

(deftest check-6-individual-lender-loan-count-exceeds-threshold-is-held
  (testing "three clean individual-lender commitments from the SAME lender commit; a fourth is held"
    (let [[db actor] (fresh)]
      (record! actor "app-individual-repeat-1" "t10a")
      (record! actor "app-individual-repeat-2" "t10b")
      (record! actor "app-individual-repeat-3" "t10c")
      (is (= 3 (store/individual-lender-commitment-count db "did:key:z6MkIndividualLenderRepeat")))
      (let [res (exec-op actor "t10" {:op :commitment/record :subject "app-individual-repeat-4"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:individual-lender-loan-count-exceeds-threshold} (-> (store/ledger db) last :basis)))
        (is (false? (store/application-already-committed? db "app-individual-repeat-4")))))))

;; ----------------------------- check 7: capacity-ratio-exceeded -----------------------------

(deftest check-7-capacity-ratio-exceeded-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :commitment/record :subject "app-capacity-exceeded"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:capacity-ratio-exceeded} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 8: equity-language-detected -----------------------------

(deftest check-8-equity-language-detected-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t12" {:op :commitment/record :subject "app-equity-language"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:equity-language-detected} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 9: personal-pledge-incomplete (distinctive) -----------------------------

(deftest check-9-personal-pledge-incomplete-is-held
  (testing "this actor's own distinctive check"
    (let [[db actor] (fresh)
          res (exec-op actor "t13" {:op :commitment/record :subject "app-pledge-incomplete"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:personal-pledge-incomplete} (-> (store/ledger db) first :basis))))))

;; ----------------------------- check 10: borrower-not-self-registered -----------------------------

(deftest check-10-borrower-not-self-registered-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t14" {:op :commitment/record :subject "app-borrower-missing"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:borrower-not-self-registered} (-> (store/ledger db) first :basis)))))

;; ----------------------------- check 11: tranche-release-precondition (missing evidence) -----------------------------

(deftest check-11-tranche-release-missing-milestone-evidence-is-held
  (let [[db actor] (fresh)
        _ (record! actor "app-clean" "t15pre")
        res (exec-op actor "t15" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 0} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:milestone-evidence-missing} (-> (store/ledger db) last :basis)))
    (is (empty? (store/tranche-release-history db)))))

(deftest check-11-tranche-release-before-commitment-is-held
  (let [[db actor] (fresh)
        ev {:milestone-met? true :evidence "n/a"}
        res (exec-op actor "t16" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 0 :milestone-evidence ev} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:commitment-not-recorded} (-> (store/ledger db) first :basis)))))

(deftest check-11-tranche-release-out-of-range-is-held
  (let [[db actor] (fresh)
        _ (record! actor "app-clean" "t17pre")
        ev {:milestone-met? true :evidence "n/a"}
        res (exec-op actor "t17" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 99 :milestone-evidence ev} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:tranche-index-out-of-range} (-> (store/ledger db) last :basis)))))

;; ----------------------------- check 12: tranche-already-released -----------------------------
;; (covered end-to-end by tranche-release-clean-path-commits-then-double-release-is-held above)

;; ----------------------------- check 13: borrower-registration-not-verified (V2) -----------------------------

(deftest check-13-borrower-registration-not-verified-is-held
  (testing ":borrower-registration-verified? false (the live GET /api/open-business lookup, at
            intake time, did not find this borrower as a claimed ADR-0013 tenant) -> HOLD, never
            reaches a human -- independent of check 10's reference-field well-formedness, which
            this fixture passes (a well-formed but never-actually-verified reference)"
    (let [[db actor] (fresh)
          res (exec-op actor "t18" {:op :commitment/record :subject "app-registration-unverified"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:borrower-registration-not-verified} (-> (store/ledger db) first :basis)))
      (is (false? (store/application-already-committed? db "app-registration-unverified"))))))

(deftest check-13-borrower-registration-verified-true-does-not-block-an-otherwise-clean-record
  (testing "the positive case: app-clean already carries :borrower-registration-verified? true
            (set in demo-data, mirroring what a real intake would have set from a live lookup
            that found the borrower claimed) and reaches human approval + commits exactly like
            every other clean :commitment/record proposal -- check 13 does not fire and does not
            block it (same end-to-end path clean-intake-auto-commits/commitment-record-always-
            needs-approval-then-commits above already prove for the OTHER eleven checks)"
    (let [[db actor] (fresh)
          res (exec-op actor "t19" {:op :commitment/record :subject "app-clean"} operator)]
      (is (= :interrupted (:status res)) "governor-clean -> escalates to approval, same as before V2")
      (let [r2 (approve! actor "t19")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :committed (:status (store/application db "app-clean"))))))))

;; ----------------------------- write-only-through-ledger -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :application/intake :subject "app-clean"
                          :patch {:id "app-clean" :requested-principal 305000}} operator)
      (exec-op actor "b" {:op :commitment/record :subject "app-no-spec"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
