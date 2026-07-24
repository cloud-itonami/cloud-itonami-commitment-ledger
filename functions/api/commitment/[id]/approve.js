// Thin routing shim — logic in
// src/commitledger/edge/commitment_endpoints.cljc, compiled by
// shadow-cljs :edge-api into functions/edge/commitment-edge-core.js.
// Regenerate with: npx shadow-cljs release edge-api
//
// POST /api/commitment/{id}/approve — V3
// (docs/adr/0003-isic6492-wiring-and-approval-resume.md). Resumes an
// interrupted :request-approval run for {id}.

export { approveOnRequestPost as onRequestPost } from "../../../edge/commitment-edge-core.js";
