# ADR-0001: Commitment-LLM ⊣ CommitmentLedgerGovernor architecture

## Status

Accepted. `cloud-itonami-commitment-ledger` registered directly at
`:implemented` in this repository, following the "built and tested
end-to-end before registration" protocol several recent actors in the
`cloud-itonami` fleet have used (most directly `cloud-itonami-isic-6493`).

This ADR is this repository's own internal architecture decision
record. A LATER, separate superproject-side ADR (in
`com-junkawasaki/root`, `90-docs/adr/2607241800-cloud-itonami-commitment-ledger-actor-blueprint.edn`)
records the decision to register this repo into the west manifest and
fleet; that ADR references this one, not the reverse.

## Context

`gftdcojp/cloud-itonami`'s ADR-0013 (`docs/adr/0013-open-business-self-registration.md`)
lets a third party self-register an independent `{org}/{repo}` tenant
into the open business registry -- a `POST /api/{org}/{repo}/register`
CACAO-authenticated flow that claims a tenant and lists it publicly.
This gives a young entrepreneur's business a real, verifiable identity
(an `org/repo` tenant + a `did:key`), but ADR-0013 stops at
*registration* -- it does not connect that registered business to any
form of capital.

The owner asked for a "commitment ledger" that bridges self-registered
businesses to LOAN-BASED (explicitly NOT equity/investment) financial
support from two distinct kinds of lender:
- **Institutional lenders** (banks, licensed lending institutions) --
  gated on license verification and Japan's 利息制限法 (Interest Rate
  Restriction Act) tiered interest-rate ceiling.
- **Individual lenders** -- a person, not a licensed institution,
  lending directly to a self-registered business. This carries
  materially different regulatory risk (貸金業法's licensing
  requirement attaches once someone is acting 「業として」, i.e. "as a
  business", even without being a bank) and needs its own, stricter
  safeguards: interest-free only, a low principal cap, and a
  loan-count threshold that treats repeated lending from the same
  individual as a conservative risk signal.

The closest domain analogs already in this fleet are `cloud-itonami-
isic-6492` (`credit.*` -- Credit-LLM ⊣ Credit Governor, the closest
operational template for a single-application-entity Store, the
ground-truth affordability-ratio recompute pattern, and the "no
override, ever" HARD-check discipline) and `cloud-itonami-isic-6493`
(`factoring.*` -- Factoring-LLM ⊣ Factoring Governor, the closest
template for a TWO-actuation shape decided directly in idiomatic
`.cljc` with no WASM safety-kernel extraction, matching this build's
own explicit "no WASM twin" scope boundary).

## Decision

### Decision 1: Loan-based only, V1 scope (owner instruction)

This actor models LOANS only. No equity, no investment, no cap table.
`equity-language-detected-violations` mirrors `cloud_itonami.funding/
guard-non-equity!`'s concept (token-for-token blocklist reuse of
`cloud_itonami.kernels.substance/equity-blocklist`, `orgs/gftdcojp/
cloud-itonami`) -- re-implemented LOCALLY in `commitledger.registry`,
not required across the repo boundary. This matches this fleet's
established "shared data contract, no shared code" convention already
used between the VC-fund trio of actors (`cloud-itonami-isic-6499`
`vcfund.*` and its siblings, `90-docs/adr/2607061700-cloud-itonami-vc-fund-blueprint.edn`):
concepts are documented and mirrored, never imported, across repo
boundaries in this fleet.

### Decision 2: Why NOT ISIC-coded

Every prior actor in this fleet with a financial-services flavor
carries an ISIC Rev.5 code: `6491` (financial leasing, `cloud-itonami-
isic-6491`), `6492`/`6495` (credit granting), `6493` (factoring
activities), `6499` (other financial service activities n.e.c., the VC
fund), `6419` (other monetary intermediation, banking associations),
`6619` (activities auxiliary to financial service activities), `6611`/
`6612` (fund management/security & commodity contracts). This actor is
deliberately **not** registered under any ISIC code, because it is not
an industry vertical -- it is a **cross-cutting matching/auxiliary
capability** layered on top of `gftdcojp/cloud-itonami`'s own
self-registration flow (ADR-0013). It does not itself grant credit
(that is `6492`'s/`6495`'s domain), does not itself factor receivables
(`6493`), and does not itself hold deposits or move money (`6419`). It
matches a self-registered business to a lender and records the
authorization; the actual financial act is always delegated downstream.
Modeling it as an auxiliary layer rather than shoehorning it into an
existing ISIC code (or claiming a new one it doesn't actually fit)
keeps the fleet's ISIC citations honest.

### Decision 3: Auxiliary, non-balance-sheet design boundary

**This actor does NOT move money itself.** `commitledger.registry/
register-commitment`/`register-tranche-release` build DRAFT records
(unsigned certificates, `"issued_by_registry" false`, `"status"
"draft-unsigned"` -- the exact same discipline `credit.registry/
register-loan-disbursement` and `factoring.registry/register-*`
already establish). No account/ledger balance, no fund custody, no
payment-gateway/processor integration exists anywhere in this
codebase. Actual disbursement is explicitly delegated to `cloud-
itonami-isic-6492`/`cloud-itonami-isic-6419`/external disbursement
rails -- this actor's own commit is an AUTHORIZATION record, not a
transfer.

### Decision 4: Individual-lender safeguards -- conservative proxies, not legal advice

Three of the ten `:commitment/record` HARD checks exist specifically
because an individual (non-institutional) lender carries materially
different regulatory risk than a bank:
- `individual-lender-not-interest-free-violations` -- an individual
  lender's rate must be exactly 0. A friends-and-family/community loan
  posture, not a disguised informal lending business.
- `individual-lender-principal-exceeds-cap-violations` -- capped at
  ¥100,000, reusing 利息制限法's own first-tier threshold as a
  conservative individual-lender exposure ceiling (not a separate
  invented number).
- `individual-lender-loan-count-exceeds-threshold-violations` -- if the
  SAME individual lender's did:key already backs 3 or more PRIOR
  COMMITTED records, a further commitment is refused. **This is
  explicitly documented, in both `commitledger.registry`'s and
  `commitledger.governor`'s own docstrings, as a CONSERVATIVE PROXY for
  「業として」(acting "as a business") repeated-lending risk under
  貸金業法's licensing requirement -- NOT a legal bright line.** Three
  is not a number Japanese law itself draws a hard boundary at; it is
  this actor's own conservative default, chosen to force a human
  review well before real 「業として」 risk could plausibly attach. A
  real deployment's operator should treat this threshold as
  configurable and consult actual legal counsel for the jurisdiction in
  question -- this actor supplies the governed, auditable
  execution scaffold, not a legal opinion.

This actor provides NO legal advice anywhere in its checks or its
documentation. Every individual-lender safeguard is a deliberately
conservative software proxy, stated as such.

### Decision 5: The distinctive check -- `personal-pledge-incomplete-violations`

Every actor in this fleet names its own distinctive/novel contribution
in its ADR (e.g. `cloud-itonami-isic-6493`'s `aggregate-exposure`
aggregation-over-multiple-records pattern). This actor's own
distinctive check is `personal-pledge-incomplete-violations`: every
application must carry a complete, non-financial **personal pledge**
(`:milestone-report-cadence`, `:mentor-checkin-commitment`,
`:progress-report-obligation`) before a commitment can ever be
recorded. This is not a financial covenant -- it is a HUMAN
accountability commitment (how often the borrower reports progress,
what mentor check-in cadence they've agreed to, what obligation they
carry to report). No prior actor in this fleet gates a financial
actuation on a non-financial, purely human-accountability field like
this; it is this build's own novel contribution to the fleet's
check-family taxonomy.

### Decision 6: Two-actuation shape, no WASM safety kernel

Following `cloud-itonami-isic-6493`'s (`factoring.*`) precedent rather
than `cloud-itonami-isic-6492`'s (`credit.*`): `commitledger.governor`
decides directly in idiomatic `.cljc`, with NO `kernels/gate.cljc`
WASM-compiled safety-kernel extraction. This is an explicit, in-scope
decision for V1 (owner instruction: no WASM twin of any check), not an
oversight -- `credit.kernels.gate`/`vcfund.kernels.gate`/`factoring`'s
own deferred-kernel note are the templates this decision follows.

Two actuation events, both permanently excluded from every phase's
`:auto` set by construction (verified by `test/commitledger/
phase_test.cljc`'s dedicated structural-invariant tests, one per
actuation): `:commitment/record` (the FIRST commit -- records the
matched lender + terms + personal pledge) and `:commitment/tranche-
release` (a LATER, independent StateGraph run -- authorizes releasing
the next tranche, gated on milestone evidence and a DEDICATED
double-release guard, `tranche-already-released-violations`, never
inferred from `:status` -- mirroring this fleet's own hard-won lesson
from `credit.governor`'s ADR-0001 Decision 5 / `factoring.governor`'s
`receivable-status-precondition-violations`, generalized here to a
per-tranche set-membership check rather than a single status value).

### Decision 7: No live self-registration integration (V1 scope)

`borrower-not-self-registered-violations` does NOT call out to
`gftdcojp/cloud-itonami`'s live registry over HTTP in V1. It only
verifies the borrower's own `:borrower-org-repo`/`:borrower-did`
reference fields are present and well-formed
(`commitledger.registry/borrower-ref-well-formed?`). A full
integration would call ADR-0013's registry to confirm the reference
actually resolves to a claimed, public tenant; that live HTTP
integration is explicitly out of scope for V1 (see README "What this
actor does NOT do").

## Consequences

(+) A self-registered young entrepreneur's business (ADR-0013) now has
a concrete, governed path to loan-based capital, with strict,
documented, load-bearing individual-lender safeguards -- not README
disclaimers, real HARD governor checks with dedicated tests.

(+) The fleet gains a genuinely new check-family contribution
(`personal-pledge-incomplete-violations`, a non-financial human-
accountability gate on a financial actuation) and a documented,
honestly-scoped "conservative proxy, not legal bright line" pattern
(`individual-lender-loan-count-exceeds-threshold-violations`) other
actors facing similar ambiguous-legal-line domains can reuse.

(+) The auxiliary, non-balance-sheet design boundary is explicit in
code: no account/ledger balance, no fund custody, no payment-gateway
integration anywhere in this codebase; every commit is a DRAFT
authorization record, never a real transfer.

(-) Still a proposal/authorization layer, not a real bank, not a real
licensed lender, and not real disbursement infrastructure -- actual
fund movement is entirely delegated to `cloud-itonami-isic-6492`/
`cloud-itonami-isic-6419`/external rails, none of which this repo
integrates with over a live network call.

(-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
official spec-basis, out of ~194 worldwide; `commitledger.facts/
coverage` reports this honestly. The rate-ceiling check applies
Japan's 利息制限法 tiers universally rather than per-jurisdiction -- an
honest V1 simplification, documented in `commitledger.registry`'s own
docstring, not a claim that every jurisdiction's usury law matches
these exact numbers.

(-) No WASM/`.kotoba` safety-kernel twin of any check (explicit V1
scope boundary, owner instruction) -- `commitledger.governor`/
`commitledger.phase` decide directly in idiomatic `.cljc`, matching
`factoring.governor`'s own posture.

(-) No live HTTP integration into `gftdcojp/cloud-itonami`'s
self-registration registry -- `borrower-not-self-registered-
violations` checks reference-field well-formedness only.

## Verification

- `cloud-itonami-commitment-ledger`: `clojure -M:dev:test` -- 61 tests /
  242 assertions, 0 failures, 0 errors. `clojure -M:lint` clean (0
  errors, 0 warnings). `clojure -M:dev:run` demo verified end-to-end:
  one clean lifecycle through both actuations (application intake ->
  matched-lender commitment record -> tranche-release, each escalating
  to human approval) plus the double-release guard and a missing-
  milestone-evidence hold, plus ALL TEN `:commitment/record` HARD-hold
  scenarios (a jurisdiction with no spec-basis, an unverified
  institutional license, a rate above the 利息制限法 ceiling, an
  interest-bearing individual loan, an individual loan above the
  principal cap, three clean individual loans from the SAME lender
  followed by a fourth that trips the loan-count threshold, a
  capacity-ratio breach, equity-language detection, an incomplete
  personal pledge, and a malformed borrower self-registration ref) --
  none of which ever reach a human -- every scenario's actual
  disposition and violation `:basis` inspected directly against the
  design before landing.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.com-junkawasaki/langgraph-clj` and
  `io.github.kotoba-lang/langchain-store` via `:local/root` directly in
  the top-level `:deps` (not only under a `:dev` alias), matching
  `cloud-itonami-isic-6493`'s own posture so a bare `clojure -M:test`
  resolves offline inside the monorepo checkout.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Model equity/investment alongside lending | ❌ | Explicit owner instruction: V1 is lending only. `equity-language-detected-violations` structurally blocks any attempt to smuggle equity language in |
| Register under an existing/new ISIC financial-services code | ❌ | This actor is a cross-cutting matching/auxiliary layer, not an industry vertical; the relevant financial-services codes are already claimed by sibling actors and this actor does not itself grant credit, factor receivables, or hold deposits |
| A single uniform lender safeguard set (no institutional/individual split) | ❌ | Individual lenders carry materially different regulatory risk (貸金業法's 「業として」 threshold) than licensed institutions; a single check set would either be too lax for individuals or too strict for licensed banks |
| Treat the individual-lender loan-count threshold as a hard legal line | ❌ | No such bright line exists in the statute this actor cites; documenting it honestly as a conservative proxy avoids implying unearned legal certainty |
| Live HTTP integration into `gftdcojp/cloud-itonami`'s self-registration registry | ❌ (deferred) | Out of scope for V1 per the task's explicit boundary; reference-field well-formedness is the V1 substitute, documented as such |
| A `kernels/gate.cljc` WASM safety-kernel twin (following `credit.governor`'s pattern) | ❌ | Explicit V1 scope boundary (owner instruction: no WASM twin); `factoring.governor`'s idiomatic-`.cljc`, no-kernel posture is the followed precedent instead |
| A single actuation (record only, no separate tranche-release) | ❌ | The task specifies staged/tranche-based capital release gated on milestone evidence -- a single actuation cannot express "release only after evidence of progress," the core mechanism connecting the personal pledge to the disbursement schedule |

## References

- `gftdcojp/cloud-itonami` ADR-0013 (`docs/adr/0013-open-business-self-registration.md`) --
  third-party self-registration, the borrower identity this actor's
  `:borrower-org-repo`/`:borrower-did` fields reference.
- `gftdcojp/cloud-itonami` ADR-0028 (`docs/adr/0028-ma-matching-deal-flow.md`) --
  another cross-cutting matching capability on the same platform,
  precedent for a matching layer that is not itself an ISIC vertical.
- `90-docs/adr/2607061700-cloud-itonami-vc-fund-blueprint.edn`
  (superproject) -- the `:capital-call/issue`-style staged-release
  actuation pattern (`InvestmentCommitteeGovernor`) this actor's own
  `:commitment/record` -> `:commitment/tranche-release` two-stage
  actuation is analogous to.
- `90-docs/adr/2607141700-cloud-itonami-isic-6493-factoring-actor.edn`
  (superproject) -- the two-actuation, no-WASM-kernel template this
  build follows most directly.
- `orgs/cloud-itonami/cloud-itonami-isic-6492` (`credit.*`) -- the
  closest single-application-entity Store/ground-truth-affordability-
  recompute template.
- `90-docs/adr/2607241800-cloud-itonami-commitment-ledger-actor-blueprint.edn`
  (superproject) -- the fleet-level registration ADR referencing this
  one.
