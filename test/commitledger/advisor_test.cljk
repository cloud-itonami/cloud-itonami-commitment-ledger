(ns commitledger.advisor-test
  "`commitledger.advisor`'s proposal-shape contract as executable tests.
  `mock-advisor` (the default everywhere in this repo's own test suite)
  is exercised directly through `infer`; `llm-advisor` is exercised
  against `langchain.model/mock-model` (deterministic, no network)."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.advisor :as advisor]
            [commitledger.store :as store]
            [langchain.model :as model]))

;; ----------------------------- application/intake -----------------------------

(deftest intake-proposal-normalizes-only-never-invents
  (let [db (store/seed-db)
        p (advisor/infer db {:op :application/intake :subject "app-clean"
                             :patch {:id "app-clean" :requested-principal 310000}})]
    (is (= :application/upsert (:effect p)))
    (is (= {:id "app-clean" :requested-principal 310000} (:value p)))
    (is (nil? (:stake p)))
    (is (>= (:confidence p) 0.9))))

;; ----------------------------- commitment/record -----------------------------

(deftest record-proposal-cites-spec-basis-and-matches-lender-when-clean
  (let [db (store/seed-db)
        p (advisor/infer db {:op :commitment/record :subject "app-clean"})]
    (is (= :commitment/mark-recorded (:effect p)))
    (is (= :actuation/commitment-record (:stake p)))
    (is (seq (:cites p)))
    (is (= "did:key:z6MkInstitutionalBank01" (:matched-lender-id (:value p))))
    (is (>= (:confidence p) 0.6))))

(deftest record-proposal-on-unregistered-jurisdiction-is-honest-low-confidence-empty-cites
  (testing "never fabricates a spec-basis for a jurisdiction absent from commitledger.facts"
    (let [db (store/seed-db)
          p (advisor/infer db {:op :commitment/record :subject "app-no-spec"})]
      (is (= :commitment/mark-recorded (:effect p)))
      (is (empty? (:cites p)))
      (is (< (:confidence p) 0.6)))))

(deftest record-proposal-always-carries-the-actuation-stake
  (testing "even for a fixture that will fail governor checks, the advisor's own stake never omits the actuation marker"
    (let [db (store/seed-db)]
      (doseq [subject ["app-unlicensed" "app-rate-exceeded" "app-capacity-exceeded"
                       "app-equity-language" "app-pledge-incomplete" "app-borrower-missing"]]
        (is (= :actuation/commitment-record (:stake (advisor/infer db {:op :commitment/record :subject subject}))))))))

;; ----------------------------- commitment/tranche-release -----------------------------

(deftest tranche-release-proposal-echoes-request-fields-never-invents-evidence
  (let [db (store/seed-db)
        ev {:milestone-met? true :evidence "month-1 sales report"}
        p (advisor/infer db {:op :commitment/tranche-release :subject "app-clean"
                             :tranche-index 0 :milestone-evidence ev})]
    (is (= :commitment/mark-tranche-released (:effect p)))
    (is (= :actuation/tranche-release (:stake p)))
    (is (= 0 (:tranche-index (:value p))))))

(deftest tranche-release-proposal-low-confidence-when-evidence-missing
  (let [db (store/seed-db)
        p (advisor/infer db {:op :commitment/tranche-release :subject "app-clean" :tranche-index 0})]
    (is (< (:confidence p) 0.6))))

;; ----------------------------- unrecognized op -----------------------------

(deftest unrecognized-op-is-a-safe-noop
  (let [db (store/seed-db)
        p (advisor/infer db {:op :something/unknown :subject "app-clean"})]
    (is (= :noop (:effect p)))
    (is (= 0.0 (:confidence p)))
    (is (nil? (:stake p)))))

;; ----------------------------- Advisor protocol -----------------------------

(deftest mock-advisor-satisfies-advisor-protocol
  (is (satisfies? advisor/Advisor (advisor/mock-advisor))))

;; ----------------------------- llm-advisor (mock-model, no network) -----------------------------

(deftest llm-advisor-parses-a-well-formed-edn-response
  (let [db (store/seed-db)
        chat (model/mock-model
              [{:role :assistant
                :content (pr-str {:summary "ok" :rationale "r" :cites ["x"]
                                  :effect :commitment/mark-recorded
                                  :value {:application-id "app-clean"}
                                  :stake :actuation/commitment-record :confidence 0.8})}])
        adv (advisor/llm-advisor chat)
        p (advisor/-advise adv db {:op :commitment/record :subject "app-clean"})]
    (is (= :commitment/mark-recorded (:effect p)))
    (is (= 0.8 (:confidence p)))
    (is (= ["x"] (:cites p)))))

(deftest llm-advisor-malformed-response-is-a-safe-low-confidence-noop
  (testing "an LLM hiccup can never auto-record a commitment or auto-release a tranche"
    (let [db (store/seed-db)
          chat (model/mock-model [{:role :assistant :content "not-a-valid-edn-map ]["}])
          adv (advisor/llm-advisor chat)
          p (advisor/-advise adv db {:op :commitment/record :subject "app-clean"})]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p))))))

(deftest llm-advisor-satisfies-the-same-advisor-protocol-as-mock-advisor
  (let [chat (model/mock-model [{:role :assistant :content "{}"}])]
    (is (satisfies? advisor/Advisor (advisor/llm-advisor chat)))))

;; ----------------------------- trace -----------------------------

(deftest trace-produces-a-decision-grounded-audit-record
  (let [request {:op :commitment/record :subject "app-clean"}
        proposal {:summary "s" :rationale "r" :cites ["c"] :confidence 0.9}
        f (advisor/trace request proposal)]
    (is (= :commitllm-proposal (:t f)))
    (is (= :commitment/record (:op f)))
    (is (= "app-clean" (:subject f)))
    (is (= 0.9 (:confidence f)))))
