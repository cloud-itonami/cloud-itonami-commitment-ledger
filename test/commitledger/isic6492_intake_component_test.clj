(ns commitledger.isic6492-intake-component-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private source
  (slurp "src/commitledger/isic6492_intake.kotoba"))

(defn- compile-kir []
  (-> (compiler/check-source source {:allow #{[:cap/call 4]}})
      :hir
      ir/lower))

(deftest intake-is-one-typed-http-effect
  (let [kir (compile-kir)
        function (first (filter #(= 'intake (:name %)) (:functions kir)))
        calls (filter #(and (seq? %) (= 'typed-cap-call (first %)))
                      (tree-seq coll? seq (:body function)))]
    (is (re-find #"\(http/post request\)" source))
    (is (not (re-find #"\(typed-cap-call" source)))
    (is (= 1 (count calls)))
    (is (= 4 (second (first calls))))
    (is (= [:ref :commitledger.http/request] (nth (first calls) 2)))
    (is (= [:ref :commitledger.http/result] (nth (first calls) 3)))))

(deftest source-packages-with-http-only-and-no-ambient-wasi
  (let [artifact
        (compiler/compile-component
         source {:allow #{[:cap/call 4]}}
         {:component-abilities
          {4 {:target "http://127.0.0.1:18920/api/loan/intake"
              :operation :http/post
              :max-bytes 65536
              :max-items 1
              :deadline-ms 10000
              :audit-id "commitment-isic6492-intake-v1"}}})]
    (is (= :wasm-component/v1 (:format artifact)))
    (is (= #{:aiueos.component/aiueos-http-post}
           (:capabilities artifact)))
    (is (= [:http/post] (get-in artifact [:wit :imports])))
    (testing "the guest receives no ambient provider authority"
      (is (empty? (filter #(re-find #"^wasi:" (name %))
                          (get-in artifact [:wit :imports])))))))
