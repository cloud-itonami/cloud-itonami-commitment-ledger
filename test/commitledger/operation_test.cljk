(ns commitledger.operation-test
  "Smoke tests for the compiled OperationActor graph itself (build +
  advisor/checkpointer injection + audit trace shape). The governor's
  full rule contract (all 13 HARD holds, escalation, phase gating,
  double-actuation guard) is exercised in `commitledger.governor-
  contract-test`; the Store contract in `commitledger.store-contract-
  test`.

  V3 addendum (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`):
  the `:commit` node's new isic-6492 wiring, exercised here directly
  against an already-approved `:request-approval` context (the ONLY way
  to reach `:commit` for `:commitment/record` without a real HTTP
  request/response round trip -- see `commitledger.edge.commitment-
  endpoints-test` for the FULL HTTP-path coverage, including the new
  `approve-core!` resume endpoint)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [commitledger.advisor :as advisor]
            [commitledger.edge.isic6492-client :as isic6492]
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

;; ----------------------------- V3: isic-6492 wiring in :commit -----------------------------
;; docs/adr/0003-isic6492-wiring-and-approval-resume.md. `:commitment/
;; record` always escalates (see ns docstring), so reaching :commit
;; requires the real interrupt -> resume-approved sequence, exactly what
;; commitledger.edge.commitment-endpoints/approve-core! does over HTTP.

(defn- record-and-approve!
  "Drives app-clean's :commitment/record through the REAL interrupt ->
  resume-approved sequence against `actor` (whatever checkpointer it was
  built with), thread-id `tid` -- the only way to reach :commit for
  :commitment/record."
  [actor tid]
  (exec-op actor tid {:op :commitment/record :subject "app-clean"} operator)
  (g/run* actor {:approval {:status :approved :by "did:key:zLender"}} {:thread-id tid :resume? true}))

(defn- isic6492-facts [s]
  (filter #(= :isic6492-intake-attempted (:t %)) (store/ledger s)))

(deftest commit-fires-isic6492-intake-on-commitment-record-ok-case
  (let [s (store/seed-db)
        checkpointer (cp/mem-checkpointer)
        client (isic6492/always-ok-client "isic6492-app-1")
        actor (op/build s {:checkpointer checkpointer :isic6492-client client})
        res (record-and-approve! actor "op-record-ok")]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :committed (:status (store/application s "app-clean"))))
    (let [facts (isic6492-facts s)]
      (is (= 1 (count facts)))
      (is (= :commitment/record (:op (first facts))))
      (is (= :ok (:outcome (first facts))))
      (is (= "isic6492-app-1" (:detail (first facts)))))))

(deftest failed-isic6492-call-does-not-roll-back-commit-but-records-the-failure
  (testing "fire-and-forget: a failed isic-6492 call never rolls back or fails commitment-ledger's own commit"
    (let [s (store/seed-db)
          checkpointer (cp/mem-checkpointer)
          client (isic6492/always-failing-client "isic-6492 intake failed: HTTP 500")
          actor (op/build s {:checkpointer checkpointer :isic6492-client client})
          res (record-and-approve! actor "op-record-fail")]
      (is (= :commit (get-in res [:state :disposition])) "commit itself succeeds regardless")
      (is (= :committed (:status (store/application s "app-clean"))))
      (let [facts (isic6492-facts s)]
        (is (= 1 (count facts)))
        (is (= :failed (:outcome (first facts))))
        (is (= "isic-6492 intake failed: HTTP 500" (:detail (first facts))))))))

(deftest no-isic6492-client-configured-means-no-call-and-no-fact
  (testing "default nil isic6492-client (every pre-V3 caller of op/build) -- unchanged behavior"
    (let [s (store/seed-db)
          checkpointer (cp/mem-checkpointer)
          actor (op/build s {:checkpointer checkpointer})
          res (record-and-approve! actor "op-record-noclient")]
      (is (= :commit (get-in res [:state :disposition])))
      (is (empty? (isic6492-facts s))))))

(deftest tranche-release-never-triggers-isic6492-intake
  (testing "only a NEW :commitment/record triggers an isic-6492 intake -- :commitment/tranche-release never does"
    (let [s (store/seed-db)
          client (isic6492/always-ok-client "isic6492-app-2")
          checkpointer1 (cp/mem-checkpointer)
          actor1 (op/build s {:checkpointer checkpointer1 :isic6492-client client})
          _ (record-and-approve! actor1 "op-tr-record")
          _ (is (= 1 (count (isic6492-facts s))) "sanity: the record itself DID fire one")
          checkpointer2 (cp/mem-checkpointer)
          actor2 (op/build s {:checkpointer checkpointer2 :isic6492-client client})
          tr-req {:op :commitment/tranche-release :subject "app-clean" :tranche-index 0
                  :milestone-evidence {:milestone-met? true :evidence "milestone 1 evidence doc"}}
          _ (exec-op actor2 "op-tr-release" tr-req operator)
          res2 (g/run* actor2 {:approval {:status :approved :by "did:key:zLender"}}
                       {:thread-id "op-tr-release" :resume? true})]
      (is (= :commit (get-in res2 [:state :disposition])))
      (is (= 1 (count (isic6492-facts s)))
          "still just the ONE fact from the earlier :commitment/record -- tranche-release added none"))))
