(ns commitledger.operation
  "OperationActor -- one commitment-ledger operation = one supervised
  actor run, expressed as a langgraph-clj StateGraph. The advisor
  (Commitment-LLM) is sealed into a single node (:advise); its proposal
  is ALWAYS routed through the CommitmentLedgerGovernor (:govern) and
  the rollout phase gate (:decide) before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                                       - :advisor opt
    - the Phase    (0->3 rollout)                                          - :phase in ctx

  One graph run = one commitment-ledger operation (intake -> advise ->
  govern -> decide -> commit | hold | approval). No unbounded inner
  loop -- each operation is auditable and checkpointed. The SAME graph
  shape drives BOTH actuations (`:commitment/record` and `:commitment/
  tranche-release`) -- each is just a different `:request :op`, routed
  identically through advise -> govern -> decide, matching `credit.
  operation`'s/`factoring.operation`'s own single-graph-many-ops shape.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands the
  decision to a human operator (the platform operator / licensed
  lender). The approver resumes with `{:approval {:status :approved}}`
  (or :rejected). `:commitment/record`/`:commitment/tranche-release`
  ALWAYS reach this node when the governor is clean -- see
  `commitledger.phase`.

  V3 addendum (`docs/adr/0003-isic6492-wiring-and-approval-resume.md`):
  the `:commit` node ALSO, when `(:op request)` is `:commitment/record`
  (never `:commitment/tranche-release` -- only the initial record
  triggers a NEW isic-6492 intake), calls an injected `Isic6492Client`
  (`commitledger.edge.isic6492-client`, default nil = skip, matching
  every EXISTING test's `(op/build store)` call with no opts) to create
  the corresponding disbursement-side application on `cloud-itonami-
  isic-6492` -- FIRE-AND-FORGET with respect to commit success (a
  failed/slow isic-6492 call never rolls back or fails THIS actor's own
  commit; see `Isic6492Client`'s own docstring for why), with the
  outcome recorded as a NEW audit-ledger fact
  (`:t :isic6492-intake-attempted`), never silently swallowed. This is
  the ONLY exception to this ns's usual layering (core `commitledger.*`
  namespaces do not otherwise depend on `commitledger.edge.*`) -- an
  intentional, task-scoped exception, not a precedent to generalize;
  see the ADR for why the `:commit` node is the correct and only hook
  point (it is the only node that calls `store/commit-record!`, so it is
  the only place that can read back the NOW-COMMITTED application via
  `store/application` before calling out). `commitledger.edge.
  isic6492-client`/`commitledger.edge.pcompat` are both portable
  `.cljc` (no unconditional `js/*`, only inside `#?(:cljs ...)`
  branches) -- requiring them here does NOT make this ns itself require
  `js/*`, and the default `nil` client / identity `wait-until` keep
  every JVM test path exactly as synchronous as before."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [commitledger.advisor :as advisor]
            [commitledger.edge.isic6492-client :as isic6492]
            [commitledger.edge.pcompat :as pc]
            [commitledger.governor :as governor]
            [commitledger.phase :as phase]
            [commitledger.store :as store]))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `commitledger.store/Store`).
  opts:
    :advisor         -- a `commitledger.advisor/Advisor` (default: mock-advisor)
    :checkpointer    -- langgraph checkpointer (default: in-mem)
    :isic6492-client -- a `commitledger.edge.isic6492-client/Isic6492Client`
                        (default: nil = skip the call entirely -- every
                        pre-V3 caller of `build` with no opts, including
                        every existing test, keeps behaving exactly as
                        before)
    :wait-until      -- a fn of promise-like -> promise-like, called
                        around the (fire-and-forget) isic-6492 call
                        (default: identity passthrough). The
                        `:cljs`-only edge entry points wrap this around
                        the Cloudflare Pages Functions `context.
                        waitUntil` (see `commitledger.edge.commitment-
                        endpoints`) so the async call survives after the
                        HTTP response returns; the JVM test path's
                        default is already synchronous (`commitledger.
                        edge.pcompat`'s `:clj` branch), so wrapping it
                        in identity is a genuine no-op there, not a
                        stub"
  [store & [{:keys [advisor checkpointer isic6492-client wait-until]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)
                    isic6492-client nil
                    wait-until   identity}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; Commitment-LLM inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; CommitmentLedgerGovernor -- independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human operator
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      ;; V3: also fire-and-forget an isic-6492 intake for a NEW
      ;; :commitment/record (never :commitment/tranche-release) -- see
      ;; ns docstring + docs/adr/0003-isic6492-wiring-and-approval-
      ;; resume.md.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            (when (and isic6492-client (= :commitment/record (:op request)))
              (let [application (store/application store (:subject request))]
                (wait-until
                 (pc/then
                  (isic6492/-intake-loan-application isic6492-client application)
                  (fn [{:keys [ok? id error]}]
                    (store/append-ledger!
                     store
                     (if ok?
                       {:t :isic6492-intake-attempted :op (:op request)
                        :subject (:subject request) :outcome :ok :detail id}
                       {:t :isic6492-intake-attempted :op (:op request)
                        :subject (:subject request) :outcome :failed :detail error})))))))
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
