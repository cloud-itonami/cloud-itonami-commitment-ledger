(ns commitledger.edge.kotobase-http
  "A `js/fetch`-based `:http-fn` for `langchain.kotoba-db/kotoba-api-
  async` (kotobase-persistence-migration, docs/adr/0004) -- the shape
  that ns's `kotoba-api-async` requires: `(fn [{:keys [url method
  headers body]}] => a real js/Promise of {:status n :body s})`.

  Deliberately NOT the same contract `langchain.kotoba-db/kotoba-api`
  (the ORIGINAL, synchronous variant `crm.kotobase/jvm-http-fn`
  satisfies with a BLOCKING JDK HttpClient, cloud-itonami-isic-5820,
  ADR-2607184000) requires -- a Cloudflare Pages Function has no
  synchronous I/O primitive at all (`js/fetch` is the ONLY HTTP client
  available and it is unconditionally async), so this ns targets
  `kotoba-api-async` instead (see that fn's own docstring in
  `langchain.kotoba-db.cljc` for the full reasoning this migration added
  it for).

  Uses this repo's existing `commitledger.edge.pcompat` async
  conventions (`pc/resolved`) exactly like every other edge file --
  `:cljs` `then`/`resolved` in `pcompat` are thin wrappers over real
  `js/Promise`, so a caller chaining this ns's `fetch-http-fn` result
  with `pc/then` (or raw `.then`) gets the same portable shape every
  other edge handler already relies on.

  CLJS-only (`js/fetch`) -- like `commitledger.edge.isic6492-client`'s
  own `LiveIsic6492Client`, there is no synchronous JVM equivalent, and
  none is needed: `commitledger.edge.kotobase-store`'s own tests inject
  a plain (non-fetch) mock `:http-fn` instead (that ns's docstring)."
  (:require [clojure.string :as str]
            [commitledger.edge.pcompat :as pc]))

#?(:cljs
   (defn fetch-http-fn
     "`{:keys [url method headers body]}` -> promise-like of `{:status n
     :body s}`, via `js/fetch`. `headers` is a plain Clojure map (string
     keys/values, matching `langchain.kotoba-db`'s own `req-headers`
     output) -- converted to a JS headers object; `method` is a keyword
     (`:post`) -- upper-cased for the wire, matching `commitledger.edge.
     isic6492-client`'s own convention."
     [{:keys [url method headers body]}]
     (-> (js/fetch url
                   #js {:method (str/upper-case (name (or method :post)))
                        :headers (clj->js (or headers {}))
                        :body body})
         (.then (fn [resp]
                  (.then (.text resp)
                         (fn [text] {:status (.-status resp) :body text}))))
         (.catch (fn [e]
                   ;; A network-level failure (DNS/TLS/connect-refused --
                   ;; NOT an HTTP error status, which is a normal resolved
                   ;; response above) -- surface it as a synthetic 599 so
                   ;; `langchain.kotoba-db/post!-async`'s own status check
                   ;; turns it into a normal ex-info the caller's `.catch`
                   ;; already handles, instead of an unhandled rejection
                   ;; shape callers don't expect.
                   {:status 599 :body (str "kotobase_http fetch failed: " (ex-message e))})))))

(defn resolved-mock-http-fn
  "A NON-fetch `:http-fn` for tests/tooling -- `respond-fn` is `(fn [req]
  => {:status n :body s})`, called synchronously; wrapped in
  `pc/resolved` so it satisfies `kotoba-api-async`'s contract on EITHER
  platform (a real Promise under `:cljs`, the plain value itself under
  `:clj` -- exactly `commitledger.edge.pcompat`'s own documented
  duality). Lets `commitledger.edge.kotobase-store`'s own tests run on
  the JVM (`clojure -M:dev:test`) without any real network or crypto."
  [respond-fn]
  (fn [req] (pc/resolved (respond-fn req))))
