(ns commitledger.edge.registrylookup
  "V2's live borrower-registration verification -- an INJECTION-SEAM port
  (same swap-not-rewrite philosophy `commitledger.store`'s `Store`
  protocol already uses for the SSoT, here applied to a read-only,
  unauthenticated external check instead of storage): `LiveLookup` calls
  the REAL, public, unauthenticated `GET /api/open-business` endpoint
  `gftdcojp/cloud-itonami` already runs (ADR-0009, `src/cloud_itonami/
  edge/open_business.cljc` there), and `MockLookup` is a canned map for
  tests -- no network, ever, in this repo's own test suite.

  CRITICAL scope boundary (see `docs/adr/0002-live-borrower-registration-
  verification.md`): this is called ONLY at INTAKE time by
  `commitledger.edge.commitment-endpoints/on-request-post-intake`, which
  stores the resulting boolean as a new permanent ground-truth field
  (`:borrower-registration-verified?`) on the stored `commitment-
  application` -- `commitledger.governor/check` (the pure, synchronous
  Governor) NEVER calls this ns itself, and NEVER becomes async. Every
  other Governor check already only reads permanent ground-truth fields
  off the stored application (see `commitledger.governor`'s ns
  docstring); check 13 (`borrower-registration-not-verified-violations`)
  is no exception -- it just reads this ONE more field the same way.

  `GET /api/open-business`'s response shape (ADR-0009,
  `cloud-itonami.edge.open-business/blueprint->registry-json`):
    {\"ok\": true, \"registry\": {\"count\": N},
     \"blueprints\": [{\"id\" \"isicRev5\" \"name\" \"domain\" \"repo\"
                       \"path\" \"selfRegistered\" ...} ...]}
  A THIRD-PARTY self-registered tenant's `:id` is literally `\"{org}/
  {repo}\"` (`cloud-itonami.registry/->registry-entry`) and always
  carries `\"selfRegistered\": true` -- gftdcojp's OWN ISIC-coded
  blueprints use a DIFFERENT `:id` shape (`\"cloud-itonami-6492\"` etc,
  no slash) and never set `selfRegistered` (defaults false via
  `(boolean self-registered)`). Matching BOTH `id = \"{org}/{repo}\"` AND
  `selfRegistered = true` is what distinguishes a real, claimed ADR-0013
  tenant from a same-named but unrelated static blueprint entry."
  (:require [commitledger.edge.pcompat :as pc]))

(defprotocol Lookup
  (-check-registration [lookup org repo]
    "org, repo -> promise-like (via commitledger.edge.pcompat) of
    {:self-registered? bool}. Never throws -- any lookup failure (network,
    parse, missing entry) resolves to {:self-registered? false}, the same
    fail-closed posture `commitledger.registry/borrower-ref-well-formed?`
    already has for a malformed reference."))

(defrecord MockLookup [answers]
  Lookup
  (-check-registration [_ org repo]
    (pc/resolved {:self-registered? (boolean (get answers (str org "/" repo) false))})))

(defn mock-lookup
  "A deterministic, canned Lookup for tests -- `answers` is a map of
  \"{org}/{repo}\" -> bool. Any id absent from `answers` resolves to
  false (fail-closed), matching `MockLookup`'s own docstring."
  ([] (mock-lookup {}))
  ([answers] (->MockLookup answers)))

(def default-base-url
  "gftdcojp/cloud-itonami's live, public, unauthenticated registry --
  `GET /api/open-business` (ADR-0009). Configurable so a fork or a
  staging deploy can point elsewhere without editing code."
  "https://itonami.cloud")

#?(:cljs
   (defn- entry-matches? [org repo entry]
     (and (= (str org "/" repo) (aget entry "id"))
          (true? (aget entry "selfRegistered")))))

#?(:cljs
   (defrecord LiveLookup [base-url]
     Lookup
     (-check-registration [_ org repo]
       (-> (js/fetch (str base-url "/api/open-business"))
           (.then (fn [resp]
                    (if (.-ok resp)
                      (.json resp)
                      (throw (js/Error. (str "open-business lookup failed: HTTP " (.-status resp)))))))
           (.then (fn [data]
                    (let [blueprints (or (aget data "blueprints") #js [])
                          hit (some #(when (entry-matches? org repo %) %) (array-seq blueprints))]
                      {:self-registered? (boolean hit)})))
           ;; Fail-closed: a transient network/parse failure must never be
           ;; mistaken for "verified" -- an unreachable registry is exactly
           ;; the situation where trusting an unverified claim is riskiest.
           (.catch (fn [_] {:self-registered? false}))))))

#?(:cljs
   (defn live-lookup
     "The real Lookup, against gftdcojp/cloud-itonami's public
     GET /api/open-business (no auth needed -- see ns docstring)."
     ([] (live-lookup default-base-url))
     ([base-url] (->LiveLookup base-url))))

(defn check-registration
  "org, repo, lookup -> promise-like of bool (:self-registered?
  unwrapped) -- the one fn `commitledger.edge.commitment-endpoints`
  actually calls at intake time."
  [lookup org repo]
  (pc/then (-check-registration lookup org repo) :self-registered?))
