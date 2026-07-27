(ns commitledger.storage-bridge-component-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private source
  (slurp "src/commitledger/storage_bridge.kotoba"))

(defn- compile-kir []
  (-> (compiler/check-source source {:allow #{[:cap/call 12]}})
      :hir
      ir/lower))

(deftest bridge-is-one-typed-storage-effect
  (let [kir (compile-kir)
        function (first (filter #(= 'transact (:name %)) (:functions kir)))
        calls (filter #(and (seq? %) (= 'typed-cap-call (first %)))
                      (tree-seq coll? seq (:body function)))]
    (is (= 1 (count calls)))
    (is (= 12 (second (first calls))))
    (is (= [:ref :commitledger.storage/request] (nth (first calls) 2)))
    (is (= [:ref :commitledger.storage/result] (nth (first calls) 3)))))

(deftest source-packages-with-storage-only-and-no-ambient-wasi
  (let [artifact
        (compiler/compile-component
         source {:allow #{[:cap/call 12]}}
         {:component-abilities
          {12 {:target "http://127.0.0.1:18921/v1/storage"
               :operation :storage/transact
               :max-bytes 65536
               :max-items 1
               :deadline-ms 10000
               :audit-id "commitment-kotobase-v1"}}})]
    (is (= :wasm-component/v1 (:format artifact)))
    (is (= #{:aiueos.component/aiueos-storage-transact}
           (:capabilities artifact)))
    (is (= [:storage/transact] (get-in artifact [:wit :imports])))
    (testing "the guest receives no ambient provider authority"
      (is (empty? (filter #(re-find #"^wasi:" (name %))
                          (get-in artifact [:wit :imports])))))))
