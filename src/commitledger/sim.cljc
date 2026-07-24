(ns commitledger.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean application
  (`app-clean`) through intake -> commitment/record (matched
  institutional lender + suggested terms, always escalates) -> human
  approval -> commit -> commitment/tranche-release (tranche 0, milestone
  evidence present, always escalates) -> human approval -> commit, then
  demonstrates the double-release guard (tranche 0 again -> HARD hold)
  and a missing-milestone-evidence hold (tranche 1, no evidence), then
  walks all TEN `:commitment/record` HARD-hold checks (a jurisdiction
  with no spec-basis, an unverified institutional license, a rate above
  the 利息制限法 ceiling, an interest-bearing individual loan, an
  individual loan above the principal cap, three clean individual loans
  from the SAME lender followed by a fourth that trips the loan-count
  threshold, a capacity-ratio breach, equity-language detection, an
  incomplete personal pledge, and a malformed borrower self-registration
  ref) -- none of which ever reach a human -- and prints the audit
  ledger + the draft commitment/tranche-release records."
  (:require [langgraph.graph :as g]
            [commitledger.store :as store]
            [commitledger.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :platform-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- record-clean! [actor subject tid]
  (exec! actor tid {:op :commitment/record :subject subject} operator)
  (approve! actor tid))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== application/intake app-clean (clean; auto-commits, no capital risk) ==")
    (println (exec! actor "t1" {:op :application/intake :subject "app-clean"
                                :patch {:id "app-clean" :borrower-org-repo "acme/ramen-cart"}} operator))

    (println "== commitment/record app-clean (matched institutional lender + suggested terms; always escalates) ==")
    (println (exec! actor "t2" {:op :commitment/record :subject "app-clean"} operator))
    (println (approve! actor "t2"))

    (println "== commitment/tranche-release app-clean tranche 0 (milestone evidence present; always escalates) ==")
    (let [ev {:milestone-met? true :evidence "month-1 sales report + mentor sign-off"}]
      (println (exec! actor "t3" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 0 :milestone-evidence ev} operator))
      (println (approve! actor "t3")))

    (println "== commitment/tranche-release app-clean tranche 0 AGAIN (double-release -> HARD hold, never reaches a human) ==")
    (let [ev {:milestone-met? true :evidence "resubmission attempt"}]
      (println (exec! actor "t4" {:op :commitment/tranche-release :subject "app-clean"
                                  :tranche-index 0 :milestone-evidence ev} operator)))

    (println "== commitment/tranche-release app-clean tranche 1 (no milestone evidence -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t5" {:op :commitment/tranche-release :subject "app-clean"
                                :tranche-index 1} operator))

    (println "== [check 1] commitment/record app-no-spec (unregistered jurisdiction -> HARD hold) ==")
    (println (exec! actor "t6" {:op :commitment/record :subject "app-no-spec"} operator))

    (println "== [check 2] commitment/record app-unlicensed (institutional lender license not verified -> HARD hold) ==")
    (println (exec! actor "t7" {:op :commitment/record :subject "app-unlicensed"} operator))

    (println "== [check 3] commitment/record app-rate-exceeded (rate above 利息制限法 ceiling -> HARD hold) ==")
    (println (exec! actor "t8" {:op :commitment/record :subject "app-rate-exceeded"} operator))

    (println "== [check 4] commitment/record app-individual-interest (individual lender, rate > 0 -> HARD hold) ==")
    (println (exec! actor "t9" {:op :commitment/record :subject "app-individual-interest"} operator))

    (println "== [check 5] commitment/record app-individual-cap (individual lender, principal above cap -> HARD hold) ==")
    (println (exec! actor "t10" {:op :commitment/record :subject "app-individual-cap"} operator))

    (println "== [check 6 setup] three clean individual-lender commitments from the SAME lender ==")
    (record-clean! actor "app-individual-repeat-1" "t11a")
    (record-clean! actor "app-individual-repeat-2" "t11b")
    (record-clean! actor "app-individual-repeat-3" "t11c")
    (println "== [check 6] a FOURTH commitment from the same individual lender (loan-count threshold -> HARD hold) ==")
    (println (exec! actor "t11" {:op :commitment/record :subject "app-individual-repeat-4"} operator))

    (println "== [check 7] commitment/record app-capacity-exceeded (capacity ratio above ceiling -> HARD hold) ==")
    (println (exec! actor "t12" {:op :commitment/record :subject "app-capacity-exceeded"} operator))

    (println "== [check 8] commitment/record app-equity-language (equity language in purpose -> HARD hold) ==")
    (println (exec! actor "t13" {:op :commitment/record :subject "app-equity-language"} operator))

    (println "== [check 9] commitment/record app-pledge-incomplete (personal-pledge incomplete -> HARD hold, this actor's distinctive check) ==")
    (println (exec! actor "t14" {:op :commitment/record :subject "app-pledge-incomplete"} operator))

    (println "== [check 10] commitment/record app-borrower-missing (borrower self-registration ref malformed -> HARD hold) ==")
    (println (exec! actor "t15" {:op :commitment/record :subject "app-borrower-missing"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft commitment records ==")
    (doseq [r (store/commitment-history db)] (println r))

    (println "== draft tranche-release records ==")
    (doseq [r (store/tranche-release-history db)] (println r))))
