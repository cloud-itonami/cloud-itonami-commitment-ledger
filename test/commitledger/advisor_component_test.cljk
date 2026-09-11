(ns commitledger.advisor-component-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private source
  (slurp "src/commitledger/advisor.kotoba"))

(defn- compile-kir []
  (-> (compiler/check-source source {:allow #{[:cap/call 11]}})
      :hir
      ir/lower))

(deftest advisor-is-one-typed-llm-effect
  (let [kir (compile-kir)
        function (first (filter #(= 'advise (:name %)) (:functions kir)))
        calls (filter #(and (seq? %) (= 'typed-cap-call (first %)))
                      (tree-seq coll? seq (:body function)))]
    (is (re-find #"\(llm/generate request\)" source))
    (is (not (re-find #"\(typed-cap-call" source)))
    (is (= 1 (count calls)))
    (is (= 11 (second (first calls))))
    (is (= [:ref :commitledger.llm/request] (nth (first calls) 2)))
    (is (= [:ref :commitledger.llm/result] (nth (first calls) 3)))))

(deftest source-packages-with-llm-only-and-no-ambient-wasi
  (let [artifact
        (compiler/compile-component
         source {:allow #{[:cap/call 11]}}
         {:component-abilities
          {11 {:target "ollama://murakumo-main"
               :operation :llm/generate
               :max-bytes 65536
               :max-items 1
               :deadline-ms 30000
               :audit-id "commitment-advisor-v1"}}})]
    (is (= :wasm-component/v1 (:format artifact)))
    (is (= #{:aiueos.component/aiueos-llm-generate}
           (:capabilities artifact)))
    (is (= [:llm/generate] (get-in artifact [:wit :imports])))
    (testing "the guest receives no ambient provider authority"
      (is (empty? (filter #(re-find #"^wasi:" (name %))
                          (get-in artifact [:wit :imports])))))))
