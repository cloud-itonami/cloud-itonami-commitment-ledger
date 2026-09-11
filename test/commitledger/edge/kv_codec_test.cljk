(ns commitledger.edge.kv-codec-test
  "`commitledger.edge.kv-codec`'s explicit application<->JSON-safe-map
  round-trip -- pure, no `js/` interop, so this exercises the EXACT
  transform the real `:cljs`-only `CloudflareKVStore` runs either side
  of `js/JSON.stringify`/`.parse`, without needing a JSON text codec on
  the JVM at all."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.edge.kv-codec :as codec]
            [commitledger.store :as store]))

(def sample-application
  (-> (store/demo-data) :applications (get "app-clean")))

(deftest application-round-trips-through-json-safe-shape
  (testing "every PRESENT, non-nil field, including namespaced :lender/* keyword
            keys and :status, survives ->json/->application unchanged. A field
            explicitly present-but-nil (app-clean's own :declared-repayment-
            capacity) is omitted by ->json (a JSON-safe map has no reason to
            carry an explicit null for an absent optional field) and so is
            absent, not nil, after the round-trip -- compare against the
            same nil-stripped shape, not the raw fixture."
    (let [expected (into {} (remove (comp nil? val)) sample-application)
          round (-> sample-application codec/application->json codec/json->application)]
      (is (= expected round)))))

(deftest json-safe-shape-has-plain-string-keys-and-values-only
  (testing "no keywords, sets, or namespaced keys remain -- safe for a real js/JSON.stringify"
    (let [safe (codec/application->json sample-application)]
      (is (every? string? (keys safe)))
      (is (every? string? (keys (get safe "lender"))))
      (is (string? (get safe "status")))
      (is (= "committed" (get (codec/application->json (assoc sample-application :status :committed)) "status"))))))

(deftest lender-round-trips
  (let [lender {:lender/type :individual :lender/id "did:key:z6MkX" :lender/license-verified false}]
    (is (= lender (-> lender codec/lender->json codec/json->lender)))))

(deftest pledge-round-trips
  (let [pledge {:milestone-report-cadence "monthly"
                :mentor-checkin-commitment "biweekly"
                :progress-report-obligation "quarterly"}]
    (is (= pledge (-> pledge codec/pledge->json codec/json->pledge)))))

(deftest milestone-evidence-round-trips
  (let [ev {:milestone-met? true :evidence "month-1 sales report"}]
    (is (= ev (-> ev codec/milestone-evidence->json codec/json->milestone-evidence)))))

(deftest nil-fields-are-omitted-not-nulled
  (testing "cond-> only assocs present fields -- json->application of a minimal map never
            introduces spurious nil-valued keys the original didn't have"
    (let [minimal {:id "x" :status :intake}
          round (-> minimal codec/application->json codec/json->application)]
      (is (= minimal round)))))

(deftest ledger-state-round-trips
  (let [state {:ledger [{:t :governor-hold :op :commitment/record :disposition :hold
                         :basis [:spec-basis-missing :equity-language-detected]}]
               :commitment-history [{"record_id" "JPN-COMMIT-000000" "lender_id" "did:key:zLender"}]
               :tranche-release-history []
               :released-tranches {"app-clean" #{0 1}}
               :commitment-sequences {"JPN" 1}
               :tranche-sequences {"JPN" 0}}
        round (-> state codec/ledger-state->json codec/json->ledger-state)]
    (is (= state round))))
