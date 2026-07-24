(ns commitledger.store-numeric-identity-test
  "Regression test for the kotobase-persistence-migration numeric-
  identity-attr fix (docs/adr/0004-kotobase-persistence-migration.md).

  kotobase-server's `do-transact` collides EVERY `:db.unique/identity`
  attribute whose value is a NUMBER (not a string) into entity id \"\"
  (empty string) -- confirmed against production, ADR-2607184000's
  known-issues section. `commitledger.store`'s schema marks THREE such
  attrs identity: `:ledger/seq`, `:commitment/seq`, `:tranche/seq`
  (their values come from `(count ...)`, i.e. plain integers, at every
  call site that used to write them before this migration's fix).

  This test proves the fix by inspecting the REAL persisted datoms
  after driving the actor's actual `commit-record!`/`append-ledger!`
  code paths (unmodified) through a real `store/datomic-store` --
  `append-ledger!`/`commit-record!` transact directly onto `conn` (they
  are documented as assuming a synchronous db-api and are never
  invoked against the remote/async db-api in this actor's design --
  see `DatomicStore`'s own docstring), so reading `conn`'s own datoms
  after the fact is the direct, ground-truth way to see exactly what
  value landed for each identity attr, independent of any db-api
  wrapper.

  This is deliberately independent of `commitledger.edge.kotobase-
  store`'s own mock tests (kotobase-store-test): THIS test never mints
  a CACAO or talks to kotobase.net wire format at all, it only proves
  the LOCAL schema/tx-builder discipline the wire-format layer then
  relies on."
  (:require [clojure.test :refer [deftest is testing]]
            [commitledger.store :as store]
            [langchain.db :as d]))

(def ^:private identity-seq-attrs #{:ledger/seq :commitment/seq :tranche/seq})

(defn- identity-seq-datom-values
  "Every persisted [attr value] pair in `s`'s conn whose attr is one of
  `identity-seq-attrs` -- the ground truth of what actually landed in
  the store after running real ops, read directly off the conn rather
  than intercepted at any db-api boundary (see ns docstring for why)."
  [s]
  (->> (d/datoms (d/db (:conn s)) :eavt)
       (filter (fn [[_e a _v]] (contains? identity-seq-attrs a)))
       (mapv (fn [[_e a v]] [a v]))))

(defn- assert-no-numeric-identity! [s]
  (let [pairs (identity-seq-datom-values s)]
    (is (seq pairs) "sanity: at least one identity-seq datom should exist by this point")
    (doseq [[attr v] pairs]
      (is (string? v)
          (str attr " must be a STRING (kotobase-server collides numeric "
               "identity-attr values to entity id \"\") -- got " (pr-str v)
               " (" (type v) ")")))))

(deftest append-ledger!-never-sends-a-numeric-ledger-seq
  (let [s (store/datomic-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (store/append-ledger! s {:op :c :disposition :commit})
    (assert-no-numeric-identity! s)
    (testing "and the ledger itself still reads back correctly, in order, despite the string encoding"
      (is (= [:commit :hold :commit] (mapv :disposition (store/ledger s)))))))

(def ^:private app-a
  {:id "app-a" :borrower-org-repo "acme/a" :borrower-did "did:key:z6MkA01"
   :requested-principal 100000 :purpose "n" :existing-debt 0 :annual-income 1000000
   :proposed-term-months 6
   :personal-pledge {:milestone-report-cadence "monthly" :mentor-checkin-commitment "biweekly"
                      :progress-report-obligation "quarterly"}
   :lender {:lender/type :institutional :lender/id "did:key:z6MkLenderA" :lender/license-verified true}
   :proposed-rate 0.1 :jurisdiction "JPN" :status :intake :tranche-schedule [100000]})

(def ^:private app-b
  {:id "app-b" :borrower-org-repo "acme/b" :borrower-did "did:key:z6MkB01"
   :requested-principal 200000 :purpose "n" :existing-debt 0 :annual-income 2000000
   :proposed-term-months 6
   :personal-pledge {:milestone-report-cadence "monthly" :mentor-checkin-commitment "biweekly"
                      :progress-report-obligation "quarterly"}
   :lender {:lender/type :institutional :lender/id "did:key:z6MkLenderB" :lender/license-verified true}
   :proposed-rate 0.1 :jurisdiction "ATL" :status :intake :tranche-schedule [200000]})

(deftest commitment-mark-recorded-never-sends-a-numeric-commitment-seq
  (let [s (store/datomic-store)]
    (store/with-applications s {"app-a" app-a})
    (store/commit-record! s {:effect :commitment/mark-recorded :path ["app-a"]})
    (assert-no-numeric-identity! s)))

(deftest tranche-release-never-sends-a-numeric-tranche-seq
  (let [s (store/datomic-store)]
    (store/with-applications s {"app-a" app-a})
    (store/commit-record! s {:effect :commitment/mark-recorded :path ["app-a"]})
    (store/commit-record! s {:effect :commitment/mark-tranche-released :path ["app-a"] :value {:tranche-index 0}})
    (assert-no-numeric-identity! s)))

;; Direct proof the fix actually prevents the collision, not just that
;; the value LOOKS like a string: two commitment records (different
;; applications) each get a DISTINCT, independently-readable
;; `:commitment/seq` entity -- if the numeric-identity bug were still
;; present, kotobase-server would have collapsed both to entity id ""
;; and the second write would have silently overwritten/merged with the
;; first. This test runs entirely against the in-process langchain.db
;; engine (no live kotobase.net call), so it does not reproduce
;; kotobase-server's own collision bug directly (that engine has no such
;; bug) -- what it DOES prove is that this store's tx-builders emit two
;; independently distinguishable string identity values ("0" and "1"),
;; which is the necessary (and, combined with kotobase-server's own
;; string-identity behavior confirmed working in ADR-2607184000, jointly
;; sufficient) condition for the two records to land as distinct
;; entities once actually sent over the wire.
(deftest a-second-commitment-record-in-a-different-jurisdiction-does-not-collide
  (let [s (store/datomic-store)]
    (store/with-applications s {"app-a" app-a "app-b" app-b})
    (store/commit-record! s {:effect :commitment/mark-recorded :path ["app-a"]})
    (store/commit-record! s {:effect :commitment/mark-recorded :path ["app-b"]})
    (assert-no-numeric-identity! s)
    (let [history (store/commitment-history s)
          rec-a (first history)
          rec-b (second history)]
      (is (= 2 (count history)) "both commitment records are distinct entities, not collided into one")
      (is (not= (get rec-a "record_id") (get rec-b "record_id"))))))
