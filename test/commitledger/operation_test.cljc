(ns commitledger.operation-test
  "Smoke tests for the compiled OperationActor graph itself (build +
  advisor/checkpointer injection + audit trace shape). The governor's
  full rule contract (all 12 HARD holds, escalation, phase gating,
  double-actuation guard) is exercised in `commitledger.governor-
  contract-test`; the Store contract in `commitledger.store-contract-
  test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [commitledger.advisor :as advisor]
            [commitledger.operation :as op]
            [commitledger.store :as store]))

(def operator {:actor-id "op-1" :actor-role :platform-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest actor-builds-with-defaults
  (testing "OperationActor can be built with just a store (mock-advisor + in-mem checkpointer defaults)"
    (let [s (store/seed-db)
          actor (op/build s)]
      (is (some? actor)))))

(deftest actor-builds-with-injected-advisor-and-checkpointer
  (testing "advisor and checkpointer are swaps, not a rewrite"
    (let [s (store/seed-db)
          actor (op/build s {:advisor (advisor/mock-advisor)
                             :checkpointer (cp/mem-checkpointer)})]
      (is (some? actor)))))

(deftest clean-intake-commits-through-the-full-graph
  (let [s (store/seed-db)
        actor (op/build s)
        res (exec-op actor "op-t1" {:op :application/intake :subject "app-clean"
                                    :patch {:id "app-clean" :requested-principal 305000}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 2 (count (:audit (:state res)))) "advisor trace + the committed fact")
    (is (= :commitllm-proposal (:t (first (:audit (:state res))))))))

(deftest audit-trail-carries-the-advisor-trace-then-the-committed-fact
  (let [s (store/seed-db)
        actor (op/build s)
        res (exec-op actor "op-t2" {:op :application/intake :subject "app-clean"
                                    :patch {:id "app-clean" :requested-principal 305000}} operator)
        audit (:audit (:state res))]
    (is (= [:commitllm-proposal :committed] (mapv :t audit))
        "advisor proposal trace, then the committed fact -- both appended, order preserved")))

(deftest a-different-store-instance-is-a-fully-independent-swap
  (testing "two OperationActor instances over two different Store instances never see each other's writes"
    (let [s1 (store/seed-db)
          s2 (store/seed-db)
          a1 (op/build s1)
          _ (exec-op a1 "op-t3" {:op :application/intake :subject "app-clean"
                                 :patch {:id "app-clean" :requested-principal 999999}} operator)]
      (is (= 999999 (:requested-principal (store/application s1 "app-clean"))))
      (is (= 300000 (:requested-principal (store/application s2 "app-clean")))
          "s2 untouched"))))
