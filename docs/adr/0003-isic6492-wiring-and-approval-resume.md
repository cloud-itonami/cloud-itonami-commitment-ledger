# ADR-0003: isic-6492 disbursement wiring + human-approval resume endpoint

## Status

Accepted. Additive to ADR-0001/ADR-0002 (unchanged): all 13 existing
Governor checks, `commitledger.phase`'s no-auto-commit invariant, and
the `MemStore`/`DatomicStore` `Store` contract are all unmodified. This
ADR covers only what V3 adds on top.

## Context

This actor's own README already names the design boundary: "This actor
does NOT move money itself... Actual disbursement is delegated to
`cloud-itonami-isic-6492`/`cloud-itonami-isic-6419`/external
disbursement rails." Until this ADR, that delegation was prose only --
no code ever called out to `cloud-itonami-isic-6492`. This ADR wires it
for real, plus fixes the one missing piece (a human-approval resume
endpoint) needed to actually exercise that wiring from the live
deployed service rather than only from unit tests.

### Two discovered prerequisites, not in the original 2-option framing

1. **isic-6492's real loan lifecycle is 5 stages**
   (`:application/intake -> :jurisdiction/assess ->
   :creditworthiness/screen -> :loan/approve -> :loan/disburse`); per
   `credit.phase` (that repo), only `:application/intake` is ever
   auto-eligible. The other 4 always require isic-6492's OWN human
   approval via its OWN Governor. **commitment-ledger must NEVER
   auto-drive isic-6492 past intake** -- that would bypass isic-6492's
   independent governance, violating this fleet's core "no actor trusts
   another's self-report / independent governance" invariant. So
   isic-6492's new edge API (`cloud-itonami-isic-6492`'s own
   `docs/adr/0002-http-edge-loan-intake.md`) exposes ONLY
   `:application/intake` (create) + a read endpoint -- never
   `:loan/approve`/`:loan/disburse` over HTTP.
2. **`:commitment/record`, when driven through the existing
   `on-request-post-record` edge handler, ALWAYS escalates to
   `:request-approval` and never reaches `:commit`** (this actor's own
   permanent structural invariant, `commitledger.phase`) -- but V2
   shipped with NO endpoint to resume/approve that interrupted run
   (ADR-0002 Decision 6, explicitly deferred). Without fixing this, any
   isic-6492-calling code in `operation.cljc`'s `:commit` node would be
   unreachable from the live HTTP service, only reachable in unit tests
   that construct an already-approved context directly. This ADR
   therefore ALSO adds the human-approval resume endpoint -- not
   optional scope, a genuine prerequisite for the isic-6492 wiring to
   be exercised by anything other than tests.

## Decision

### Decision 1: who may approve -- the same lender-identity check as `-record`/`-tranche-release`

`on-request-post-approve` reuses `commitledger.edge.auth/require-lender`
(the caller's verified CACAO `iss` must equal the stored application's
own `:lender/id`) rather than introducing a distinct operator/reviewer
capability string. Reasoning: the lender is ALREADY the party this
actor's own model treats as the human authorizing an actuation (`:by`
on the `:approval` channel is already lender-shaped in `commitledger.
operation`'s `:request-approval` node, unchanged); introducing a SEPARATE
"platform operator" identity/capability would be new surface this task's
scope did not ask for and this actor's data model does not yet carry
(no operator/reviewer role concept exists anywhere in `commitledger.*`).
Reusing the lender-identity check is the most consistent option with
the file's own existing patterns, and matches the domain reality: the
lender who was asked to record a commitment is the one who then decides
whether to actually commit to it.

### Decision 2: the isic-6492 call is fire-and-forget with respect to commit success

`commitledger.operation`'s `:commit` node, AFTER `store/commit-record!`
succeeds, calls the injected `Isic6492Client` ONLY when `(:op request)`
is `:commitment/record` (never `:commitment/tranche-release` -- only
the initial record triggers a NEW isic-6492 intake). The call NEVER
rolls back or fails the commit -- `commitledger.edge.isic6492-client/
Isic6492Client`'s `-intake-loan-application` never throws/rejects,
mirroring `Lookup`/`CacaoVerifier`'s own exception-safety contract
exactly, always resolving to `{:ok? bool :id str|nil :error str|nil}`.
The outcome is recorded as a NEW audit-ledger fact
(`{:t :isic6492-intake-attempted :outcome :ok|:failed :detail ...}`),
never silently swallowed. Rationale: isic-6492 is an independently
governed actor; a network hiccup or an isic-6492-side hold on ITS OWN
intake must never invalidate commitment-ledger's own, already-approved
commitment record.

### Decision 3: `isic6492-opts` must be threaded into `approve-core!`'s `op/build`, not (only) `record-core!`'s

A real bug, found and fixed during the live end-to-end proof (not
merely a design decision written down in advance): because
`:commitment/record` ALWAYS escalates on its FIRST run (Decision-2's
premise), the graph NEVER reaches `:commit` during `record-core!`'s
initial call -- `:commit` is reached only on `approve-core!`'s RESUME.
Each `op/build` call constructs a fresh graph with fresh node closures,
so the `:commit` node that actually executes on resume is the one
`approve-core!`'s OWN `op/build` call creates. An `isic6492-client`
threaded only into `record-core!`'s (never-reaches-:commit) call would
never fire at all -- confirmed empirically live (see Verification).
`record-core!` still threads `isic6492-opts` too, defensively, in case
a future phase change ever made `:commitment/record` auto-eligible, but
`approve-core!` is the call site that matters today.
`test/commitledger/edge/commitment_endpoints_test.cljc`'s
`approve-true-fires-the-isic6492-client-because-commit-only-happens-on-
resume` regression-guards this.

### Decision 4: self-mint identity, generated once offline, stored as a Cloudflare secret

`src/commitledger/edge/cacao_mint.cljc` is a direct, faithful PORT of
`gftdcojp/cloud-itonami`'s own `cloud_itonami.edge.cacao-mint` (same
license-compatible local-mirror convention this repo's `cacao.cljc`/
`base58.cljc`/`cbor.cljc` already established, ADR-0002 Decision 3).
`scripts/generate-actor-identity.cljs` (an **nbb** script, per this
workspace's runtime-priority rule) generates the Ed25519 keypair ONCE,
offline, printing the did:key and the exportable private-key material
to stdout -- NEVER written into any committed file. The private key
material is stored as the `COMMITMENT_LEDGER_ACTOR_SEED` Cloudflare
Pages SECRET; the did (public information -- it IS the public key,
base58-encoded) is stored as the plain `COMMITMENT_LEDGER_ACTOR_DID`
Pages var. `commitledger.edge.isic6492-client/live-client-from-env`
imports the seed at request time and signs via `js/crypto.subtle.sign`,
closing a `sign-fn` over the imported `CryptoKey` for `cacao-mint/mint`.

**Empirical key-format finding** (confirmed via `scripts/verify-cacao-
mint-roundtrip.cljs` and a dedicated isolated smoke test, both run for
real against Node's WebCrypto, 2026-07-24, Node v26.3.0): Ed25519
PRIVATE key raw-format EXPORT is unsupported on this runtime family
(`Unable to export Ed25519 private key using raw format`), and raw-
format PRIVATE key IMPORT is likewise rejected
(`Unsupported key usage for a Ed25519 key`) -- only the PUBLIC key
supports `"raw"` format (unaffected: `commitledger.edge.cacao/verify`
already relies on this for signature verification). JWK import DOES
work for a private key when both `d` (the private seed) and `x` (the
public component, recoverable from the did alone -- base58-decode, drop
the 2-byte `0xed01` multicodec prefix) are present.
`isic6492-client/import-signing-key` reconstructs this JWK at request
time from just the stored seed + did, avoiding any need to store the
public component separately.

### Decision 5: checkpoint persistence via the SAME load-before/persist-after seam as the Store

`commitledger.edge.kv-checkpoint` mirrors `commitledger.edge.kv-store`'s
own `load-store`/`save-store!` pattern, applied to `langgraph.
checkpoint/Checkpointer` instead of `commitledger.store/Store`:
`Checkpointer`'s `-put!`/`-get-latest` are SYNCHRONOUS (called directly
inside `langgraph.graph/run-loop`'s superstep reduce), so a Checkpointer
cannot proxy real (async) Cloudflare KV calls directly.
`load-checkpointer` asynchronously fetches the ONE persisted checkpoint
for a thread-id (if any) from KV, returns a real synchronous in-memory
`Checkpointer` (a `reify` over a fresh atom) for `g/run*` to use for
THIS request; `save-checkpoint!` asynchronously persists whatever that
checkpointer ends up holding, once the graph run finishes. Serialization
uses `pr-str`/`edn/read-string` of the WHOLE checkpoint map, mirroring
`langgraph.checkpoint/datomic-checkpointer`'s own precedent exactly (not
a bespoke field-by-field JSON codec -- `:state` carries rich, keyword-
heavy nested StateGraph data with no natural camelCase mapping worth
hand-writing).

### Decision 6: four pre-existing V2 bugs discovered and fixed while proving the E2E flow live

These are NOT part of this ADR's original design scope -- they were
discovered because the required "prove it end-to-end for real" step
(this task's own explicit instruction) surfaced them as HARD blockers.
Each is a narrow, additive plumbing fix; none touches, reorders, or
renumbers any of the 13 Governor checks, and none changes the CACAO
authorization/security semantics.

1. **`commitledger.edge.registrylookup/check-registration` passed a
   bare keyword as a `.then` callback**
   (`(pc/then (-check-registration ...) :self-registered?)`). Confirmed
   empirically (against the REAL shadow-cljs `:release`-compiled,
   deployed artifact, via a live `POST /api/commitment/intake` call)
   that this does NOT reliably unwrap the map on this compiled
   artifact -- the whole `{:self-registered? bool}` map leaked through
   as `:borrower-registration-verified?`, which meant check 13
   (`(true? map)` is always false) HELD every single `:commitment/
   record` unconditionally, live, on every application. Fixed by
   wrapping in an explicit `(fn [m] (:self-registered? m))`.
2. **`intake-core!`'s registry lookup queried the URL path's `org`/
   `repo`, which are structurally ALWAYS nil on this repo's actual
   deployed routing** (`functions/api/commitment/*.js` has NO
   `[org]`/`[repo]` dynamic path segments anywhere -- confirmed by
   listing the deployed function tree). This meant check 13 could NEVER
   match a real self-registered tenant regardless of what the
   application's own `:borrower-org-repo` field claimed. Fixed by
   deriving the lookup's org/repo from the APPLICATION's own claimed
   `:borrower-org-repo` field instead (`borrower-org-repo->lookup-args`)
   -- exactly what check 13's own docstring already said it verifies.
   The CACAO resource-scope check (`auth/verify-cacao-header`) is
   UNCHANGED -- still gated on the URL-derived org/repo exactly as
   before; only the registry lookup's target changed.
3. **`parse-intake-body` never converted the wire JSON's nested
   `lender`/`personalPledge` objects into the namespaced-keyword/
   kebab-case shape `commitledger.store`/`commitledger.governor`/
   `commitledger.edge.auth/require-lender` all expect** -- it only
   selected the top-level key (`:lender` vs `"lender"`), never recursed
   into the nested map. A REAL wire JSON body has no way to natively
   express `:lender/id` (JSON has no keyword type), so `:lender/id` was
   ALWAYS nil for any real HTTP caller, making `require-lender`
   unconditionally reject every live `:commitment/record` caller.
   Confirmed empirically (a live `POST /api/commitment/record` call
   returned 403 even for the actual named lender). Fixed by reusing
   `commitledger.edge.kv-codec/json->lender`/`json->pledge` (already
   written for the KV-persistence codec path -- `parse-intake-body`
   simply never called them).
4. **A path-segment `id` param containing an encoded `/` (`%2F`) is not
   decoded by Cloudflare Pages' router** (unlike a query-string value,
   which `URLSearchParams` decodes correctly). Since `gen-id` prefixes
   every id with `{org}/{repo}-`, and org/repo are always empty (bug 2's
   root cause), EVERY application id in practice starts with a literal
   `/` -- meaning `GET /api/commitment/{id}` and the new `POST /api/
   commitment/{id}/approve` could never resolve ANY real id via their
   PATH param. Fixed by `js/decodeURIComponent`-decoding the path `id`
   param in both handlers (`path-id-of`).

## Consequences

- (+) A committed loan-based commitment now creates a REAL,
  independently-governed application on isic-6492's own books --
  proven live, not just in tests (see Verification).
- (+) The human-approval resume step ADR-0002 explicitly deferred is
  now built, closing that named gap.
- (+) Four real, previously-undiscovered production bugs in the
  already-live V2 service are fixed -- discovered specifically BECAUSE
  this task required a genuine, not simulated, end-to-end proof rather
  than trusting the existing (JVM-only, test-fixture-shaped, never
  wire-round-tripped) test suite's passing status as sufficient
  evidence the live service actually worked for a real caller.
- (+) isic-6492's own independent governance is structurally
  preserved -- commitment-ledger's new edge layer has no code path that
  can reach `:jurisdiction/assess`/`:creditworthiness/screen`/
  `:loan/approve`/`:loan/disburse`.
- (-) The isic-6492 call remains fire-and-forget; a REAL failure (e.g.
  isic-6492 itself down, or holding the intake) is recorded in
  commitment-ledger's own audit ledger but not surfaced synchronously
  to the HTTP caller of `/approve` -- a deliberate trade-off (Decision
  2), not an oversight.
- (-) `commitledger.edge.kv-checkpoint/load-checkpointer` is NOT a
  `ClaimableCheckpointer` -- a real cross-request concurrent-resume race
  (two simultaneous `/approve` calls for the same id) is not closed
  here, the same class of gap `commitledger.edge.kv-store`'s own
  docstring already names for the read-modify-write Store round-trip.
  A known, documented gap, not silently papered over; a future
  KV-backed compare-and-swap would close it.
- (-) `operation.cljc` now depends on `commitledger.edge.isic6492-
  client`/`commitledger.edge.pcompat` -- the ONLY exception to this
  repo's usual core/edge layering (core namespaces do not otherwise
  depend on `commitledger.edge.*`). An intentional, task-scoped
  exception (the `:commit` node is the only node that calls `store/
  commit-record!`, so it is the only place that can read back the
  NOW-COMMITTED application before calling out), not a precedent to
  generalize.

## Verification

- `clojure -M:dev:test` -- 131 tests / 399 assertions, 0 failures, 0
  errors (up from the pre-existing 107 tests / 338 assertions; ALL
  prior tests pass unmodified).
- `clojure -M:lint` -- 0 errors (17 warnings, the same class of
  CLJS/JVM host-conditional false positive `.clj-kondo/config.edn`
  already documents and downgrades to warning).
- `npx shadow-cljs release edge-api` compiles cleanly.
- `scripts/generate-actor-identity.cljs` run for real via nbb
  (confirmed Node's WebCrypto Ed25519 keygen/export works under nbb).
- `scripts/verify-cacao-mint-roundtrip.cljs` run for real via nbb:
  `ROUNDTRIP OK: mint and verify agree on wire format` -- proves this
  actor's own mint and its (ported) verify actually agree on wire
  format, the single most important correctness property for the new
  self-mint identity.
- Deployed live: `https://cloud-itonami-commitment-ledger.pages.dev`
  (existing project, redeployed 6 times across this ADR's fixes) and
  `https://cloud-itonami-isic-6492.pages.dev` (new project, see that
  repo's own ADR-0002).
- **Full live end-to-end proof** (curl, real CACAOs minted with
  throwaway test identities via `commitledger.edge.cacao-mint`,
  real self-registration via `gftdcojp/cloud-itonami`'s own ADR-0013
  `POST /api/{org}/{repo}/register` flow for a genuine self-registered
  test tenant, `e2eproof/commitledgertest`):
  1. `POST /api/commitment/intake` -> 201,
     `{"ok":true,"id":"/-acz5xti3","borrowerRegistrationVerified":true}`.
  2. `POST /api/commitment/record?id=%2F-acz5xti3` -> 202,
     `{"ok":true,"id":"/-acz5xti3","disposition":"request-approval"}`.
  3. `POST /api/commitment/%2F-acz5xti3/approve` -> 200,
     `{"ok":true,"id":"/-acz5xti3","disposition":"commit"}`.
  4. `GET /api/commitment/%2F-acz5xti3` -> 200,
     `"status":"committed"`.
  5. `wrangler pages deployment tail` on the live production
     deployment, captured during step 3, showed:
     `"isic6492-client intake result:", {"ok?": true, "id":
     "loan-8c78r09n"}` -- the fire-and-forget isic-6492 call succeeded.
  6. Independently confirmed via `GET https://cloud-itonami-isic-6492.
     pages.dev/api/loan/loan-8c78r09n` (CACAO minted with this actor's
     OWN real deployed identity, `did:key:
     z6MksdBBMCXDAZs4PQRyLTzRJpr88FjiayxPrNKfhGwshPLx`) -> 200,
     `{"ok":true,"application":{"id":"loan-8c78r09n","jurisdiction":
     "JPN","status":"intake","purpose":"working capital for the
     definitive isic6492-wiring E2E proof run"}}` -- the `purpose`
     field matches the ORIGINAL commitment-ledger intake body exactly,
     proving the real data flowed through the real wiring end to end.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| A distinct operator/reviewer capability for `/approve` | ❌ | No operator/reviewer role concept exists anywhere in `commitledger.*` yet; reusing the lender-identity check is the most consistent option with this file's existing patterns and matches domain reality (Decision 1) |
| Await the isic-6492 call synchronously before responding to `/approve` | ❌ | Would make commitment-ledger's own commit latency/availability depend on isic-6492's; violates the "no actor trusts another's self-report" AND "one actor's outage must not block another's already-approved decision" postures both (Decision 2) |
| Thread `isic6492-client` only into `record-core!` | ❌ (bug, fixed) | `:commitment/record` never reaches `:commit` on its first run; `approve-core!`'s own `op/build` is the call site that matters (Decision 3) |
| Store the raw Ed25519 private key via `js/crypto.subtle.importKey "raw"` | ❌ (does not work) | Confirmed empirically: raw-format PRIVATE key export/import is unsupported on this runtime family; JWK reconstruction from seed + did-derived public component works (Decision 4) |
| A bespoke field-by-field JSON checkpoint codec (mirroring `kv-codec`'s application codec) | ❌ | `:state` carries rich, keyword-heavy nested StateGraph data with no natural camelCase mapping worth hand-writing; `pr-str`/`edn/read-string` mirrors `langgraph.checkpoint/datomic-checkpointer`'s own existing precedent (Decision 5) |
| Restructure V2's Cloudflare Pages Functions routing to add real `[org]`/`[repo]` path segments | ❌ (out of scope) | Would be a genuine redesign of already-shipped, already-approved V2 URL scheme; the narrower, targeted fixes (Decision 6, items 2/4) close the actual blocking gaps without touching the routing shape |
| Leave the 4 discovered bugs unfixed and report a broken E2E proof | ❌ | Each bug is a narrow, obviously-correct, additive plumbing fix that does not touch any Governor check; fixing them was necessary to honestly deliver the task's own explicit "prove it end-to-end for real" requirement |

## References

- `docs/adr/0001-architecture.md` / `docs/adr/0002-http-edge-live-
  registry-verification.md` (this repo, both unchanged by this ADR).
- `cloud-itonami-isic-6492` `docs/adr/0002-http-edge-loan-intake.md` --
  the other half of this cross-actor wiring.
- `gftdcojp/cloud-itonami` `docs/adr/0013-open-business-self-
  registration.md` -- the self-registration flow used to create the
  real test tenant this ADR's E2E proof depends on.
- `90-docs/adr/<superproject-adr-id>-cloud-itonami-isic6492-
  commitledger-cross-actor-wiring.edn` (superproject) -- the fleet-level
  ADR recording this wiring's independent-governance rationale and the
  two discovered prerequisites.
