# cloud-itonami-commitment-ledger

Open Business Blueprint for a **commitment ledger**: a governed
matchmaking layer that connects an individually self-registered young
entrepreneur's business (via `gftdcojp/cloud-itonami`'s existing
self-registration flow, [ADR-0013](https://github.com/gftdcojp/cloud-itonami/blob/main/docs/adr/0013-open-business-self-registration.md))
to LOAN-BASED (not equity) financial support from lenders -- banks and
other licensed institutions, AND individuals, with strict safeguards
for the individual-lender case.

**V1 scope is lending only.** No investment, no equity, no cap table --
per explicit owner instruction. Any free-text field that smuggles in
equity/dividend/cap-table language is a HARD, un-overridable governor
violation (`equity-language-detected-violations`).

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet (closest templates:
[`cloud-itonami-isic-6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492)
for the single-application-entity Store shape and ground-truth
affordability recompute;
[`cloud-itonami-isic-6493`](https://github.com/cloud-itonami/cloud-itonami-isic-6493)
for the two-actuation shape and the idiomatic-`.cljc`-governor, no
safety-kernel posture). Here it is **Commitment-LLM ⊣
CommitmentLedgerGovernor**.

> **Why an actor layer at all?** An LLM is great at drafting a matched-
> lender summary and normalizing application intake -- but it has **no
> notion of whether an institutional lender's license is actually
> verified, whether a proposed rate exceeds 利息制限法's tiered ceiling,
> whether an individual lender is quietly acting 「業として」across many
> small loans, whether an application's own capacity ratio exceeds a
> responsible-lending ceiling, whether a "loan" is secretly an equity
> pitch, whether a personal pledge is actually complete, or whether a
> borrower is actually self-registered**. Letting it record a
> commitment or authorize a tranche release directly invites
> irresponsible/predatory lending a borrower would actually be bound
> to, unlicensed lending, and liability for whoever runs it. This
> project seals the Commitment-LLM into a single node and wraps it with
> an independent **CommitmentLedgerGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers matching a self-registered borrower to a lender and
recording a loan-based commitment (terms + a non-financial personal
pledge), then authorizing staged tranche releases against milestone
evidence. It does **not**, by itself, hold a license to lend in any
jurisdiction, and it does not claim to. It does **not** move money
itself, ever.

### Actuation -- and the auxiliary, non-balance-sheet design boundary

**This actor does NOT move money itself.** It only records the
authorization to disburse -- actual disbursement is delegated to
`cloud-itonami-isic-6492` (credit-granting)/`cloud-itonami-isic-6419`
(banking)/external disbursement rails. This is a deliberate, explicit
design boundary: `commitledger.*` is an AUXILIARY matching/authorization
layer, not a balance-sheet lender. No account/ledger balance, no fund
custody, no payment-gateway/processor integration exists anywhere in
this codebase.

**Two actuation events, neither ever autonomous, at any phase, by
construction:**
1. `:commitment/record` (`:actuation/commitment-record`) -- the FIRST
   commit: records the matched lender + terms + personal pledge.
2. `:commitment/tranche-release` (`:actuation/tranche-release`) -- a
   LATER, independent run: authorizes releasing the next tranche,
   gated on milestone evidence and a dedicated double-release guard.

Two independent layers enforce this (`commitledger.governor`'s
`high-stakes` set and `commitledger.phase`'s phase table, which never
puts either op in any phase's `:auto` set) -- see `commitledger.phase`'s
docstring and `test/commitledger/phase_test.cljc`. The actor may draft,
check and recommend; a human platform operator is always the one who
actually authorizes.

## The ten `:commitment/record` HARD checks

1. `spec-basis-missing?` -- jurisdiction spec-basis citation absent.
2. `institutional-lender-license-not-verified?` -- `:lender/type
   :institutional` and `:lender/license-verified` not true.
3. `rate-ceiling-exceeded?` -- `:lender/type :institutional` and the
   proposed rate exceeds Japan's 利息制限法 (Interest Rate Restriction
   Act) tiered ceiling for the principal (< ¥100,000 -> 20%; ¥100,000
   <= p < ¥1,000,000 -> 18%; >= ¥1,000,000 -> 15%). See
   `commitledger.registry`'s docstring for the honest V1 scoping note:
   these JPN tiers are applied universally, not per-jurisdiction.
4. `individual-lender-not-interest-free?` -- `:lender/type :individual`
   and rate > 0.
5. `individual-lender-principal-exceeds-cap?` -- `:lender/type
   :individual` and principal > ¥100,000 (the same 利息制限法 first-tier
   threshold, reused as a conservative individual-lender cap).
6. `individual-lender-loan-count-exceeds-threshold?` -- an individual
   lender's did:key already backs 3 or more PRIOR COMMITTED records --
   a **conservative proxy** for 「業として」(acting "as a business")
   repeated-lending risk under 貸金業法's licensing requirement, **NOT a
   legal bright line**.
7. `capacity-ratio-exceeded?` -- `(existing-debt + requested-principal)
   / (annual-income | declared-repayment-capacity)` > 0.43 (reusing
   `credit.registry`'s exact formula/constant, re-implemented locally --
   see `commitledger.registry`'s docstring on the "reused, not shared
   code" convention).
8. `equity-language-detected?` -- the application's `:purpose` free-text
   field matches an equity/dividend/cap-table blocklist token-for-token
   equivalent to `cloud_itonami.funding/equity-blocklist`
   (`orgs/gftdcojp/cloud-itonami`), re-implemented locally, never
   required across the repo boundary.
9. `personal-pledge-incomplete?` -- any required `:personal-pledge`
   field (`:milestone-report-cadence`/`:mentor-checkin-commitment`/
   `:progress-report-obligation`) missing/blank. **This actor's own
   DISTINCTIVE check** -- see `docs/adr/0001-architecture.md`.
10. `borrower-not-self-registered?` -- the borrower `org/repo` + did:key
    reference fields are missing/malformed. V1: reference-field
    well-formedness only -- **no live HTTP integration** into
    `gftdcojp/cloud-itonami`'s registry (see "What this actor does NOT
    do" below).

Plus TWO more HARD checks gate `:commitment/tranche-release`:
`tranche-release-precondition-violations` (application must be
`:committed`, tranche-index in range, complete milestone evidence) and
`tranche-already-released-violations` (a DEDICATED boolean/set-
membership double-release guard, never inferred from `:status`).

## What this actor does NOT do

- Does NOT move money itself -- no account/ledger balance, no fund
  custody, no payment-gateway/processor integration anywhere in this
  codebase. Actual disbursement is delegated to `cloud-itonami-isic-
  6492`/`cloud-itonami-isic-6419`/external rails.
- Does NOT call out to `gftdcojp/cloud-itonami`'s live self-
  registration registry (ADR-0013) over HTTP -- `borrower-not-self-
  registered-violations` only verifies reference-field well-formedness
  in V1.
- Does NOT model investment/equity in any form (V1 scope: lending
  only, per explicit owner instruction) -- any equity/dividend/cap-
  table language in a free-text field is a HARD violation.
- Does NOT provide legal advice. The individual-lender safeguards
  (interest-free, principal cap, loan-count threshold) are
  **conservative proxies for regulatory risk, not a substitute for
  jurisdiction-specific legal review** -- see `commitledger.registry`'s
  own docstring, which says so explicitly.
- Is NOT a WASM-compiled safety kernel -- unlike `cloud-itonami-isic-
  6492`'s `credit.kernels.gate`, this governor decides directly in
  idiomatic `.cljc` (matching `cloud-itonami-isic-6493`'s own posture;
  a WASM twin is explicitly out of scope for V1).

## The core contract

```
self-registered application + jurisdiction facts (commitledger.facts, spec-cited)
        |
        v
   ┌───────────────┐   proposal      ┌──────────────────────────┐
   │ Commitment-LLM │ ─────────────▶ │ CommitmentLedgerGovernor  │  (independent system)
   │  (sealed)      │  + citations    │ 10 :commitment/record      │
   └───────────────┘                 │ checks + 2 :tranche-release  │
                             commit ◀────┼──────────▶ hold │ checks (spec-basis ·      │
                                 │             │           │ license · rate-ceiling ·    │
                           record + ledger  escalate ─▶ human   individual-lender safe-   │
                                             (ALWAYS for         guards · capacity-ratio ·  │
                                              both actuations)   equity-language · personal-│
                                                                  pledge · self-registration·│
                                                                  milestone-evidence ·        │
                                                                  double-release)              │
```

**The Commitment-LLM never records a commitment or releases a tranche
the CommitmentLedgerGovernor would reject, and never does so without a
human sign-off.** Every HARD violation forces **hold** and *cannot* be
approved past; a clean proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean intake -> record -> tranche-release lifecycle
                        # + the double-release guard + all ten :commitment/record HARD-hold checks
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · advisor/operation smoke
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Why NOT an ISIC-coded actor

Unlike most `cloud-itonami-isic-*` actors, this repo is deliberately
**not** registered under an ISIC industry code. It is a cross-cutting
matching/auxiliary capability layered on top of `gftdcojp/cloud-
itonami`'s self-registration flow, not an industry vertical -- and the
financial-services ISIC codes this actor's function is closest to
(`6491` financial leasing, `6492`/`6495` credit granting, `6493`
factoring, `6499` other financial services n.e.c., `6419` other
monetary intermediation, `6619` other activities auxiliary to financial
services, `6611`/`6612` fund/security management) are already claimed by
sibling actors in this fleet. See `docs/adr/0001-architecture.md` for
the full reasoning.

## Layout

| File | Role |
|---|---|
| `src/commitledger/store.cljc` | **Store** protocol -- `MemStore` \| `DatomicStore` (`langchain.db`) + append-only audit ledger + commitment/tranche-release draft histories + the dedicated `tranche-already-released?` double-actuation guard |
| `src/commitledger/registry.cljc` | Draft-record construction, `rate-ceiling-for-principal` (利息制限法 tiers), `compute-capacity-ratio`, `equity-blocklist`, `personal-pledge-required-fields`, `borrower-ref-well-formed?` -- pure ground-truth calculations |
| `src/commitledger/facts.cljc` | Per-jurisdiction lending spec-basis catalog with an official citation per entry, honest coverage reporting |
| `src/commitledger/advisor.cljc` | **Commitment-LLM Advisor** -- `mock-advisor` \| `llm-advisor`; intake/matched-commitment/tranche-release proposals |
| `src/commitledger/governor.cljc` | **CommitmentLedgerGovernor** -- 10 `:commitment/record` HARD checks + 2 `:commitment/tranche-release` HARD checks + 1 soft (confidence/actuation gate) |
| `src/commitledger/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → supervised (both actuations always human; application intake is the ONLY auto-eligible op, no capital risk) |
| `src/commitledger/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph, one graph drives both actuations |
| `src/commitledger/sim.cljc` | demo driver |
| `test/commitledger/*_test.cljc` | governor contract (all 12 checks, one test each) · phase invariants · store parity · registry conformance · advisor/operation smoke |

## Jurisdiction coverage (honest)

`commitledger.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `commitledger.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `commitledger.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Commitment-LLM` + `CommitmentLedgerGovernor` run as
real, tested code (see `Run` above), modeled closely on the two nearest
prior actors' architecture. See `docs/adr/0001-architecture.md` for the
full design record.

## License

Code and implementation templates are AGPL-3.0-or-later.
