(ns commitledger.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:commitment/record` and `:commitment/tranche-release`
  must NEVER be members of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.phase :as phase]))

(deftest commitment-record-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a matched-lender commitment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :commitment/record))
          (str "phase " n " must not auto-commit :commitment/record")))))

(deftest tranche-release-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a tranche-release authorization"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :commitment/tranche-release))
          (str "phase " n " must not auto-commit :commitment/tranche-release")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":application/intake moves no capital -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:application/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :application/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :commitment/record} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :commitment/tranche-release} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :application/intake} :commit)))))
