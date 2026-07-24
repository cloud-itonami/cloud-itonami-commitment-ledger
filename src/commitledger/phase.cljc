(ns commitledger.phase
  "Phase 0->3 staged rollout -- the commitment-ledger analog of
  `credit.phase` (`cloud-itonami-isic-6492`) and `factoring.phase`
  (`cloud-itonami-isic-6493`).

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- application intake allowed, every
                                 write needs human approval.
    Phase 2  assisted-intake  -- same write set as phase 1 (this
                                 domain has no separate no-capital-risk
                                 'assess' lifecycle distinct from
                                 intake -- see `commitledger.store`'s
                                 own docstring: a single entity, no
                                 sub-record).
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:application/intake` (no capital risk
                                 yet) may auto-commit. `:commitment/
                                 record`/`:commitment/tranche-release`
                                 NEVER auto-commit, at any phase.

  `:commitment/record` and `:commitment/tranche-release` are
  deliberately ABSENT from every phase's `:auto` set, including phase
  3 -- a permanent structural fact, not a rollout milestone still to
  come. Matching a real lender (institutional or individual) to a
  borrower and authorizing a tranche release are the TWO real-world
  actuation events this actor performs; both are always a human's call.
  `commitledger.governor`'s `:actuation/commitment-record`/`:actuation/
  tranche-release` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this."
  )

(def read-ops  #{})
(def write-ops #{:application/intake :commitment/record :commitment/tranche-release})

;; NOTE the invariant: `:commitment/record` and `:commitment/tranche-
;; release` are members of `write-ops` (governor-gated like any write)
;; but are NEVER members of any phase's `:auto` set below. Do not add
;; them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:application/intake}                                 :auto #{}}
   2 {:label "assisted-intake" :writes #{:application/intake}                                 :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:application/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:commitment/record`/`:commitment/tranche-release` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        {:keys [writes auto]} (get phases p {:writes #{} :auto #{}})]
    (cond
      (= governor-disposition :hold)
      {:disposition :hold :reason nil}

      (not (contains? writes op))
      {:disposition :hold :reason :phase-disabled}

      (and (= governor-disposition :commit) (not (contains? auto op)))
      {:disposition :escalate :reason :phase-approval}

      :else
      {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a CommitmentLedgerGovernor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
