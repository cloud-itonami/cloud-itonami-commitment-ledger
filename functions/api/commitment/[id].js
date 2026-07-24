// Thin routing shim — logic in
// src/commitledger/edge/commitment_endpoints.cljc, compiled by
// shadow-cljs :edge-api into functions/edge/commitment-edge-core.js.
// Regenerate with: npx shadow-cljs release edge-api
//
// GET /api/commitment/{id} — public, read-only (see
// commitment_endpoints.cljc's on-request-get-application docstring).

export { applicationOnRequestGet as onRequestGet } from "../../edge/commitment-edge-core.js";
