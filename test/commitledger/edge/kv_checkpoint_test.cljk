(ns commitledger.edge.kv-checkpoint-test
  "`commitledger.edge.kv-checkpoint`'s `MemCheckpointStore` +
  `load-checkpointer`/`save-checkpoint!` round trip -- the seam
  `commitledger.edge.commitment-endpoints/on-request-post-approve`
  depends on to resume an `:request-approval`-interrupted run across
  separate, stateless requests. The real `CloudflareCheckpointStore` is
  `:cljs`-only KV-binding interop, exercised at deploy time, not here."
  (:require [clojure.test :refer [deftest is]]
            [commitledger.edge.kv-checkpoint :as kvc]
            [langgraph.checkpoint :as cp]))

(deftest text-round-trip-preserves-nested-keyword-shape
  (let [ckpt {:step 2 :state {:disposition :escalate :request {:op :commitment/record}
                              :verdict {:violations [{:rule :spec-basis-missing}]}}
              :frontier [:request-approval] :status :interrupted}]
    (is (= ckpt (kvc/text->checkpoint (kvc/checkpoint->text ckpt))))))

(deftest load-checkpointer-with-nothing-persisted-is-a-fresh-checkpointer
  (let [cs (kvc/mem-checkpoint-store)
        checkpointer (kvc/load-checkpointer cs "tid-1")]
    (is (nil? (cp/get-latest checkpointer "tid-1")))))

(deftest save-then-load-round-trips-through-a-fresh-checkpointer
  (let [cs (kvc/mem-checkpoint-store)
        ckpt {:step 3 :state {:disposition :escalate} :frontier [:request-approval] :status :interrupted}
        checkpointer1 (kvc/load-checkpointer cs "tid-2")]
    (cp/put! checkpointer1 "tid-2" ckpt)
    (kvc/save-checkpoint! cs checkpointer1 "tid-2")
    (let [checkpointer2 (kvc/load-checkpointer cs "tid-2")]
      (is (= ckpt (cp/get-latest checkpointer2 "tid-2"))
          "a SECOND, independently-constructed checkpointer for the SAME thread-id
           sees the persisted checkpoint -- simulating a later, separate HTTP request"))))

(deftest save-checkpoint-with-nothing-to-persist-is-a-noop
  (let [cs (kvc/mem-checkpoint-store)
        checkpointer (kvc/load-checkpointer cs "tid-3")]
    (kvc/save-checkpoint! cs checkpointer "tid-3")
    (is (nil? (kvc/cs-get cs "tid-3")))))
