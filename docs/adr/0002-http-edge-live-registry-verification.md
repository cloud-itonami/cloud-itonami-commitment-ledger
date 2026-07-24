# ADR-0002: V2 -- HTTP edge exposure + live borrower-registration verification

## Status

Accepted. Additive to ADR-0001 (unchanged): the twelve original checks,
`commitledger.phase`'s no-auto-commit invariant, and the MemStore/
DatomicStore `Store` contract are all unmodified. This ADR covers only
what V2 adds on top.

## Context

ADR-0001 registered `cloud-itonami-commitment-ledger` as a
fully-tested, pure `.cljc` library -- Governor + StateGraph, no HTTP
exposure, no live borrower-registry verification (check 10,
`borrower-not-self-registered-violations`, verified reference-field
well-formedness only), no deployment. V2's scope, decided up front and
unchanged by this ADR: turn this into a reachable HTTP service with a
REAL (not merely well-formed-looking) borrower verification, with the
minimum necessary additive changes to the existing tested Governor/
StateGraph core.

## Decision

### Decision 1: check 13 is additive, not a replacement for check 10

`borrower-registration-not-verified-violations` (check 13,
`src/commitledger/governor.cljc`) is a NEW, thirteenth HARD check --
appended, never renumbering the original twelve. It is independent of
check 10 (`borrower-not-self-registered-violations`, still present,
unchanged): check 10 verifies the `:borrower-org-repo`/`:borrower-did`
REFERENCE FIELDS are well-formed; check 13 verifies that reference was
actually confirmed against `gftdcojp/cloud-itonami`'s live, public
`GET /api/open-business` registry (ADR-0009 there) AT INTAKE TIME. A
well-formed-but-never-claimed reference (a typo'd org/repo, or one
whose owner never actually self-registered under ADR-0013) now fails
check 13 even though it would have passed check 10 alone.

### Decision 2: the Governor stays pure and synchronous -- ground truth is written at intake, not read live by the Governor

`commitledger.governor/check` is UNCHANGED in kind: still a pure,
synchronous function that only reads permanent ground-truth fields off
the stored application (`commitledger.governor`'s own ns docstring,
unchanged). Check 13 is no exception -- it reads ONE more field,
`:borrower-registration-verified?`, exactly the way check 9 reads
`:personal-pledge` or check 7 reads `:existing-debt`. The live HTTP
call itself (`commitledger.edge.registrylookup/check-registration`)
happens ONCE, at INTAKE time, in `commitledger.edge.commitment-
endpoints/intake-core!` -- an edge handler, not the Governor -- and its
boolean result is stored as that ground-truth field alongside every
other stored field. This was the explicit, non-negotiable design
constraint for V2 (make the Governor async would break its own
`clojure -M:dev:test` contract and the actor's core "the Governor
independently re-verifies, never trusts a live call mid-decision"
posture) and is why `commitledger.edge.registrylookup` is an
INJECTION-SEAM port (`Lookup` protocol, `LiveLookup` | `MockLookup`)
mirroring `commitledger.store`'s own `Store` protocol philosophy,
rather than a function the Governor calls directly.

**Point-in-time caveat, stated honestly**: this is a check at INTAKE,
not re-verified before `:commitment/record` itself runs. If a
borrower's self-registration is later revoked/changed between intake
and record, check 13 would not catch it -- the same class of staleness
every point-in-time verification carries, and out of scope to solve
here (would need either a live re-check inside `:commitment/record`
itself, breaking the Governor's purity, or a separate revocation-
webhook mechanism `gftdcojp/cloud-itonami` does not yet expose).

### Decision 3: ported, not required, wire-format code -- `equity-blocklist` precedent

`src/commitledger/edge/{cacao,base58,cbor}.cljc` are direct, faithful
PORTS of `cloud_itonami.edge.{cacao,base58,cbor}` (`orgs/gftdcojp/
cloud-itonami`) -- copied into this repo, not cross-repo required. This
mirrors this repo's OWN pre-existing convention: `commitledger.
registry/equity-blocklist` is already a token-for-token LOCAL
re-implementation of `cloud_itonami.funding/equity-blocklist` (ADR-0001
Decision 1), not an import. Both licenses are AGPL-3.0-or-later (the
same license this repo already carries -- confirmed by reading `orgs/
gftdcojp/cloud-itonami/LICENSE` directly, GNU AFFERO GENERAL PUBLIC
LICENSE Version 3), so porting verification-only, deterministic,
never-minting crypto/wire-format code is license-compatible. The
algorithm is UNCHANGED from upstream in all three files -- this ADR
does not reinvent Ed25519/CBOR/base58btc; it copies a working
implementation.

### Decision 4: no CACAO self-mint identity for this actor's own outbound calls

The registry lookup (`GET /api/open-business`) is a PUBLIC,
unauthenticated endpoint (ADR-0009 there: "No auth gate -- unlike
`state`/`metrics`, this is static public registry data"). This actor
therefore needs NO CACAO self-mint identity for its own outbound calls
in V2 -- only INBOUND verification of CALLERS' CACAOs (borrowers
submitting intake, lenders authorizing record/tranche-release) is
needed, which is what `commitledger.edge.cacao/verify` (ported) plus
`commitledger.edge.auth`'s gating logic provide. This is a real, in-
scope simplification (not a deferred gap): a future capability needing
an AUTHENTICATED outbound call from this actor (none currently exists)
would need the `build-actor` skill's CACAO self-mint convention, not
attempted here.

### Decision 5: KV, not kotobase.net/Datomic-peer, for edge persistence

Cloudflare Pages Functions are stateless per request. `commitledger.
edge.kv-store` persists to a Cloudflare KV namespace
(`COMMITMENT_LEDGER_KV`, this project's own, separate from `gftdcojp/
cloud-itonami`'s production `ITONAMI_DATA`), NOT kotobase.net/a Datomic
peer -- explicitly deferred, since that would need this actor to hold
its own CACAO self-mint identity (Decision 4 above: currently
unnecessary) and a live kotobase.net endpoint contract this V2 does not
build. This KV layer is SEPARATE FROM, and does not replace,
`commitledger.store`'s `MemStore`/`DatomicStore` `Store` protocol the
Governor/StateGraph tests exercise -- it is purely the HTTP-request-
scoped rehydration boundary (`kv-store/load-store`/`save-store!`
reconstruct a real, fresh `commitledger.store/empty-store` per request,
run the UNMODIFIED actor graph against it, persist the result). Two KV
entry kinds: `application:{id}` (one `commitment-application` map,
JSON, per id -- the literal shape asked for) plus a small
`ledger-state` companion blob (the Store's own cross-application
aggregate: ledger/commitment-history/tranche-release-history/released-
tranches/sequences) so check 6 (`individual-lender-loan-count-exceeds-
threshold-violations`) and check 12's double-release guard stay correct
across separate, stateless requests -- a per-id-only blob cannot
satisfy those two checks' cross-application ground truth by itself.

### Decision 6: neither actuation ever auto-commits through this HTTP surface either -- V2 does not build the approval-resume endpoint

`commitledger.phase`'s structural invariant (neither `:commitment/
record` nor `:commitment/tranche-release` is ever in any phase's
`:auto` set) holds through the HTTP layer unchanged: `on-request-post-
record`/`-tranche-release` run the actor graph, which -- exactly like
every existing test already proves -- pauses at `:request-approval`
(`g/run*` returns `:status :interrupted`) whenever the Governor is
clean. This V2 surface reports that honestly as HTTP disposition
`"request-approval"` (202) and stops there; it does NOT implement the
human-approval RESUME step (`g/run* ... {:resume? true}` against a
LATER request), which would need a durable, authenticated operator
identity and UI/API this task's explicit scope does not include. A
real deployment's next milestone is that resume/approve endpoint,
explicitly deferred here, not silently skipped.

### Decision 7: injection seams throughout, mirroring `commitledger.store`'s own philosophy

Every new edge boundary (`commitledger.edge.registrylookup`'s `Lookup`,
`commitledger.edge.kv-store`'s `KVStore`, `commitledger.edge.auth`'s
`CacaoVerifier`) is a protocol with a real `:cljs`-only implementation
and a portable Mock/Mem implementation, exactly matching
`commitledger.store`'s own `Store` protocol precedent (`MemStore` |
`DatomicStore`). `commitledger.edge.pcompat` (`resolved`/`then`) is the
one new piece of plumbing this requires -- a minimal portable async
seam so the SAME core-logic function bodies (`commitledger.edge.auth/
verify-cacao-header`, `commitledger.edge.commitment-endpoints/*-core!`)
run unchanged on both JVM (tests, synchronous) and `:cljs` (the real
edge runtime, real Promises), without reinventing a Promise.

### Decision 8: real CACAO signature crypto is not re-tested by this repo's own JVM suite

`commitledger.edge.cacao/verify` needs `js/crypto.subtle` (Ed25519) --
no JVM equivalent exists to port to without reimplementing signature
verification wholesale, which Decision 3 above already ruled out
("don't reinvent the wire format"). `gftdcojp/cloud-itonami`'s own
`test/cloud_itonami/edge/cacao_test.cljc` (+ `base58_test.cljc`/
`cbor_test.cljc`) already carries a genuine, currently-passing 20-case
signature-crypto test suite against this EXACT, unmodified algorithm --
but it needs a dedicated CLJS Node test runner
(`cloud_itonami.edge.cljs-test-runner`, invoked via a bespoke `clojure
-Sdeps ... -M:cljs -m cljs.main --target node` incantation, a SEPARATE
toolchain from that repo's own JVM `clojure -M:test`/`cloud-itonami.
test-runner`). Per this task's own explicit instruction ("this
workspace's runtime priority ... is portable .cljc first; don't
introduce a whole new CLJS-only test toolchain if you can keep this
portable and testable under the existing `clojure -M:dev:test`"), this
repo does NOT stand up that second toolchain to re-run an identical,
unmodified algorithm a second time. Instead: `commitledger.edge.auth`
decouples the AUTH-GATING LOGIC (CACAO header parse, resource-scope
matching, lender-identity matching, response shaping -- everything this
repo actually adds) from the crypto itself via the `CacaoVerifier`
injection seam (Decision 7), and `test/commitledger/edge/auth_test.cljc`
exercises that gating logic exhaustively with `mock-verifier`. The
crypto path itself is exercised where it already has a real, passing
test suite -- upstream -- and ported here byte-for-byte, unmodified.

## Consequences

- (+) A borrower's self-registration claim is now REALLY verified
  against `gftdcojp/cloud-itonami`'s live registry at intake, not just
  checked for well-formedness -- check 13 closes ADR-0001's own
  documented gap (its Consequences: "No live HTTP integration into
  `gftdcojp/cloud-itonami`'s self-registration registry").
- (+) The actor is now a reachable HTTP service (4 Cloudflare Pages
  Functions handlers) while every existing Governor/Phase/Store
  guarantee (61 original tests, all 12 original checks, the no-auto-
  commit invariant) is unmodified and still passes unmodified.
- (+) `commitledger.edge.*`'s core logic (registrylookup/kv-store/auth/
  commitment-endpoints' `*-core!` fns) is portable `.cljc`, directly
  testable under the SAME `clojure -M:dev:test` JVM runner as the rest
  of this repo -- no second toolchain introduced.
- (-) Check 13 is a point-in-time check at intake, not re-verified
  before `:commitment/record` runs (Decision 2's caveat) -- a real
  staleness gap, documented, not solved here.
- (-) V2 does not implement the human-approval resume/approve HTTP
  endpoint (Decision 6) -- `on-request-post-record`/`-tranche-release`
  can report `"request-approval"` but never actually commit a record
  via this HTTP surface alone. A real deployment needs that endpoint
  next.
- (-) No kotobase.net/Datomic-peer-backed persistence (Decision 5) --
  Cloudflare KV is a simpler, HTTP-request-scoped substitute with the
  same read-modify-write / lost-update-under-concurrent-writes
  limitation `cloud_itonami.edge.workspace_store`'s own docstring
  already names for the identical pattern there (a Durable Object would
  be the real fix, not attempted here either).
- (-) The real CACAO signature-crypto path (`commitledger.edge.cacao`)
  is not independently re-tested by THIS repo's own test suite
  (Decision 8) -- it relies on the ported source's own upstream test
  coverage staying accurate for an unmodified algorithm.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Make `commitledger.governor/check` async, call the live registry directly from the Governor | ❌ | Breaks the Governor's pure/synchronous contract and its "independently re-derive from stored ground truth, never trust a live call mid-decision" posture every other check already establishes; explicitly ruled out by the task |
| `require` `cloud_itonami.edge.cacao`/`base58`/`cbor` across the repo boundary instead of porting | ❌ | This workspace's established convention for actor repos is local mirror, not cross-repo require (this repo's own `equity-blocklist` precedent, ADR-0001 Decision 1) |
| Stand up a CLJS Node test runner (mirroring `cloud_itonami.edge.cljs-test-runner`) to re-test the ported crypto | ❌ | Explicit task instruction against introducing a second CLJS-only toolchain when the logic can stay portable; the crypto is unmodified and already covered upstream |
| kotobase.net/Datomic-peer-backed edge persistence | ❌ (deferred) | Needs this actor's own CACAO self-mint identity (explicitly out of scope, Decision 4) and a live kotobase.net contract this V2 does not build |
| Implement the human-approval resume/approve HTTP endpoint in V2 | ❌ (deferred) | Needs a durable, authenticated operator identity/UI this task's explicit scope does not include; the actor's own invariant (never auto-commit past request-approval) is upheld either way |
| Per-application-only KV storage (no `ledger-state` companion) | ❌ | Cannot satisfy check 6 (cross-application lender-loan-count) or check 12 (double-release guard) correctly across separate stateless requests |

## References

- `docs/adr/0001-architecture.md` (this repo's own original architecture
  ADR -- V1 scope, the twelve original checks, unchanged)
- `gftdcojp/cloud-itonami` ADR-0009 (`docs/adr/0009-open-business-
  registry-endpoint.md`) -- `GET /api/open-business`, this check's live
  ground truth
- `gftdcojp/cloud-itonami` ADR-0013 (`docs/adr/0013-open-business-self-
  registration.md`) -- the self-registration flow a borrower's
  `:borrower-org-repo`/`:borrower-did` reference (checks 10 + 13
  together) attests to
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/edge/{cacao,base58,
  cbor,tenant_auth,register,ma_endpoints,workspace_store}.cljc` -- the
  handler-boilerplate/wire-format templates this ADR's Decisions 3/5/7
  port/mirror
- `90-docs/adr/2607241800-cloud-itonami-commitment-ledger-actor-
  blueprint.edn` (superproject) -- the original fleet-registration ADR
