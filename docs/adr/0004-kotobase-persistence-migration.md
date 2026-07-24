# ADR-0004: kotobase.net persistence migration (KV -> langchain.kotoba-db)

- Status: Accepted (2026-07-24)
- Related: `0002-http-edge-live-registry-verification.md`, `0003-isic6492-wiring-and-approval-resume.md`, `cloud-itonami-isic-5820/docs adr equivalent (superproject 90-docs/adr/2607184000)`, superproject `90-docs/adr/2607242200-cloud-itonami-isic6492-commitledger-cross-actor-wiring.edn`

## Context

Since V2/V3 this actor's edge layer (`commitledger.edge.commitment-endpoints`) has persisted every application, the audit ledger, and the commitment/tranche-release history in a hand-rolled Cloudflare KV JSON blob (`commitledger.edge.kv-store`). This ADR replaces that with real kotobase.net (net-kotobase) persistence, following the pattern `cloud-itonami-isic-5820` (`crm.store`/`crm.kotobase`, ADR-2607184000) already proved once for a sibling actor.

## Decision

### 1. `commitledger.store/DatomicStore` becomes `:db-api`-injectable

Mirrors `crm.store/store-with-api` precisely: `DatomicStore` gained a `db-api` field; `store-with-api` builds one from any `{:q :transact! :db :pull :entid}`-shaped map (the existing in-process default, `datomic-store`, is unchanged -- it still passes `langchain.db/api`). `application`/`all-applications`/`with-applications`/`ledger-state`/`with-ledger-state` (2 NEW protocol methods, additive) are `chain`-aware (a small helper that transparently handles either a plain synchronous value or, under `:cljs`, a real `js/Promise`) -- these are the ONLY methods ever called against a remote/async `db-api`; every other method (`commit-record!`/`append-ledger!`/etc) still assumes a synchronous backend and is only ever invoked against the in-process snapshot store the StateGraph runs against per request (unchanged from V2/V3 -- `langgraph.graph/run*` still runs fully synchronously; a Cloudflare Pages Function has no synchronous I/O primitive at all, so nothing mid-graph can be a real network call).

### 2. `langchain.kotoba-db/kotoba-api-async` (new, in `kotoba-lang/langchain`, additive)

`kotoba-api`'s existing synchronous `:http-fn` contract is unsatisfiable by `js/fetch` (unconditionally async, no blocking variant exists in a V8 isolate). Added a parallel `kotoba-api-async` reusing every private wire-format helper (`normalize-query`/`decode-pull-value`/`normalize-wildcard-pull`/`entity-wire-value`) unchanged, sequenced through a portable `then*` (real `js/Promise` under `:cljs`, plain value under `:clj`).

### 3. Two upstream `langchain.kotoba-db` bugs found and fixed (library-level, not previously known)

Beyond the four wire-format fixes ADR-2607184000 already made, this migration found and fixed two more in `:pull`'s decode path (both `kotoba-api` and the new `kotoba-api-async`):

- **`decode-pull-value` over-eagerly auto-decoded already-EDN-encoded compound BLOB values** (e.g. `:lender`/`:personal-pledge`, stored as `pr-str`'d strings by every consuming store's own `enc`/`ls/enc` convention) into live maps/vectors, corrupting callers (`pull->application` etc) that expect to decode the blob themselves exactly once. Fixed: the existing "reject a bare symbol" guard was extended to also reject any parse that comes back as a `coll?` (map/vector/set/list) -- collections are never a legitimate scalar attribute value on this wire, only ever a pre-encoded blob string.
- **The lookup-ref identity-attribute backfill applied unconditionally**, making a genuinely non-existent entity's wildcard pull (which legitimately comes back completely empty) indistinguishable from an entity that exists with every other field nil -- `(store/application s "nope")` returned a fully-populated-looking nil-fields map instead of `nil`. Fixed: the backfill now only fires when the pull already returned other attrs (`(seq full)`).

Both are regression-tested in `langchain/test/langchain/kotoba_db_test.cljc`.

### 4. Numeric `:db.unique/identity` attribute fix (REQUIRED correctness fix)

kotobase-server's `do-transact` collides EVERY `:db.unique/identity` attribute whose value is a NUMBER (not a string) into entity id `""`, silently merging every such entry into ONE entity (confirmed, ADR-2607184000's own known-issues section). `commitledger.store`'s schema marks THREE such attrs identity, all previously written as `(count ...)` (plain ints): `:ledger/seq`, `:commitment/seq`, `:tranche/seq`. Fixed by wrapping every write site in `str` and adding `parse-seq-num` at every numeric-sort read site (`sort-by (comp parse-seq-num first)`, since `sort-by first` on now-string values would sort lexicographically once counts reach double digits). Regression-tested in `test/commitledger/store_numeric_identity_test.cljc` -- which was verified to actually FAIL before the fix (temporarily reverted, reran, confirmed 3 failures, re-applied the fix).

### 5. `commitledger.edge.kotobase-store` (new) -- `KVStore` protocol implementation, not a new boundary

Rather than replacing `commitledger.edge.kv-store`'s role with a differently-shaped boundary, `KotobaseKVStore` implements `commitledger.edge.kv-store`'s OWN `KVStore` protocol (`kv-get-application`/`kv-put-application!`/`kv-list-ids`/`kv-get-ledger-state`/`kv-put-ledger-state!`) against kotobase.net. This means `commitledger.edge.kv-store/load-store`/`save-store!` -- and every `*-core!` fn in `commitledger.edge.commitment-endpoints`, and EVERY existing test in `commitment_endpoints_test.cljc` (which drives them via `kv/mem-kv-store`) -- needed ZERO changes. Only the bottom-of-file `on-request-*` Cloudflare Pages Function entry points swap `(kv/cloudflare-kv-store env)` for `(kotobase-kv-store-from-env! env)` (now async, threaded through the existing `.then` chains via `js/Promise.all`). `kv_store.cljc` and its Cloudflare KV namespace binding are left in place, unused in production, per this workspace's "don't delete until certain it's unneeded" convention.

Fail-closed: `kotobase-kv-store-from-env!` REJECTS (never silently falls back to KV) if the identity is unconfigured or CACAO minting fails; the edge handler's own `.catch` turns that into a clear 500.

### 6. Self-mint identity: reused, not reissued

`commitledger.edge.kotobase-identity` reuses THIS actor's existing self-mint identity (`$COMMITMENT_LEDGER_ACTOR_SEED`/`_DID`, already provisioned for isic-6492 outbound calls, ADR-0003) -- one actor identity, multiple resource scopes, no new key.

### 7. THREE confirmed-live CACAO wire-format bugs found and fixed (this repo's own code, discovered during live verification)

Not previously known, and not present in the ADR-2607184000 precedent (which ran on the JVM against `cacao.core`/`ed25519.core`, a different, already-canonical CBOR/CACAO library). This actor's pre-existing self-mint machinery (`commitledger.edge.cacao-mint`/`commitledger.edge.cbor`, a hand-rolled port built for `cloud_itonami.edge.cacao`'s verifier, confirmed working for isic-6492 calls) turned out to be wire-incompatible with kotobase.net's OWN edge auth gate (`kotobase-cf-wasm.auth/verify-cacao`, read directly, read-only, from `gftdcojp/net-kotobase` -- not modified):

1. **Missing `:exp`.** `mint-kotobase-cacao!`'s first draft omitted it; kotobase-server's verifier requires it even though this repo's own, more lenient `commitledger.edge.cacao/verify` (used for borrower/lender CACAOs) tolerates its absence. Fixed: always set `:exp` (`ttl-sec` 3600), matching `crm.kotobase`/`dossier.kotobase-identity`'s own precedent.
2. **Missing envelope `s.t: "EdDSA"` field, and non-canonical CBOR map key order.** `kotobase-cf-wasm.auth/verify-cacao` requires the signature sub-map to carry `"t": "EdDSA"` -- `commitledger.edge.cbor/encode-cacao-envelope` never included it (correct for, and unchanged for, this repo's own verifier, which never reads `s.t`). A CACAO minted via the existing pair verified TRUE against both this repo's own verify AND the canonical `cacao.core/verify` (kotoba-lang/org-chainagnostic-cacao) -- ruling out a broken signature -- yet kotobase.net rejected it with a generic 401 every time. Fixed: a NEW, kotobase.net-specific envelope encoder (`commitledger.edge.kotobase-identity`'s own `encode-kotobase-cacao-envelope`) building `{"h":{"t":"caip122"}, "p":<canonically-key-sorted>, "s":{"t":"EdDSA","s":sig}}`, reusing `commitledger.edge.cbor/header`+`encode-text` (widened from private to public, non-breaking) + `encode-map`. `commitledger.edge.cacao-mint/mint` + `encode-cacao-envelope` are UNCHANGED, still used for isic-6492 calls.
3. **Wrong `:graph` resource scope.** Confirmed via reading `kotoba-lang/kotobase-client`'s `kotobase.cacao.cljs` (its own docstring: for the "apex"/kotobase.net profile, "`:graph` = the issuer did:key", live-probed 2026-07-09) that the CACAO's `kotoba://graph/...` resource must be THIS ACTOR'S OWN did:key, not a computed graph CID (`crm.kotobase/canonical-graph`'s ADR-2607184000 derivation, which this repo's first draft followed). A write scoped to a computed CID consistently 401'd; the identical write scoped to `(str "kotoba://graph/" did)` succeeded (HTTP 200). Reads work identically via `:db_name` alone (no precomputed `:graph` needed for either direction) -- confirmed live, both write and read. This eliminated the graph-CID-derivation code (`canonical-graph`/SHA-256/base32) entirely.

## Consequences

- (+) Real, live-verified kotobase.net persistence for this actor's application/ledger/commitment/tranche-release data.
- (+) 2 genuine upstream `langchain.kotoba-db` bugs fixed, benefiting every other consumer (including `crm.store`/isic-6492).
- (+) The numeric-identity-attr collision bug is fixed AND regression-tested with a test proven to catch it.
- (+) Zero changes to any of the 13 Governor checks, the StateGraph, or any existing test.
- (-) kotobase.net has an eventual-consistency window of roughly 1-2 minutes between a write and that same data being visible to a freshly-constructed, independently-authenticated read connection (confirmed empirically during live verification: an immediate post-write read via a NEW conn/CACAO 404'd for up to ~90s, then succeeded). This does not affect correctness of what's eventually written, but a caller chaining record/approve immediately after intake on a brand-new application may see a transient 404/409 until the write propagates. Not a defect in this migration's own code -- a kotobase.net backend characteristic, newly discovered.
- (-) `kv_store.cljc` remains in the codebase, unused in production, per the "don't delete" convention.

## Verification

- `clojure -M:dev:test`: 140 tests / 435 assertions (up from 131/399), 0 failures.
- `clojure -M:lint`: 0 errors (22 pre-existing-shaped warnings, all `js/`-interop-invisible-to-:clj-lint, consistent with this repo's existing tolerated pattern).
- `npx shadow-cljs release edge-api`: compiles cleanly (0 errors, 2 pre-existing `:redef` warnings unrelated to this change).
- Live: deployed to Cloudflare Pages Production (branch `main`, NOT the default `HEAD`/Preview environment `wrangler pages deploy` uses for a detached-HEAD checkout -- `--branch=main` required). `POST /api/commitment/intake` against `https://cloud-itonami-commitment-ledger.pages.dev` -> 201, `borrowerRegistrationVerified: true` (against a real self-registered ADR-0013 tenant, `e2eproof/commitledgertest`). A second, independently-minted intake produced a genuinely distinct application id. `GET /api/commitment/{id}` (a fresh, separate HTTP request) confirmed the data persisted and is independently retrievable via kotobase.net.

## References

- `orgs/cloud-itonami/cloud-itonami-isic-5820/src/crm/store.cljc`, `src/crm/kotobase.clj` (the JVM precedent this migration ported to CLJS)
- `orgs/kotoba-lang/langchain/src/langchain/kotoba_db.cljc`
- `orgs/gftdcojp/net-kotobase/kotobase-cf-wasm/src/kotobase_cf_wasm/auth.cljs` (read-only reference, not modified)
- `orgs/kotoba-lang/kotobase-client/src/kotobase/cacao.cljs` (read-only reference, not modified)
