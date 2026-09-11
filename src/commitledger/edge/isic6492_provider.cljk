(ns commitledger.edge.isic6492-provider
  "Loopback-only provider sidecar for the HTTP Component capability.

  The .kotoba guest supplies a bounded JSON domain payload. This process owns
  the actor seed, mints CACAO, terminates the outbound TLS connection and calls
  the one configured isic-6492 endpoint."
  (:require [commitledger.edge.isic6492-client :as isic]))

(def ^:private max-request-bytes (* 64 1024))

(defn- reply! [response status body]
  (let [encoded (js/JSON.stringify (clj->js body))]
    (.call (aget response "writeHead") response status
           #js {"content-type" "application/json"
                "content-length" (.byteLength js/Buffer encoded)})
    (.call (aget response "end") response encoded)))

(defn- request-body [request]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks (array)
           size (atom 0)]
       (.call (aget request "on") request "data"
              (fn [chunk]
                (swap! size + (.-length chunk))
                (if (> @size max-request-bytes)
                  (reject (js/Error. "request exceeds admitted byte bound"))
                  (.push chunks chunk))))
       (.call (aget request "on") request "end"
              (fn []
                (try
                  (resolve (js->clj
                            (js/JSON.parse
                             (.toString (.concat js/Buffer chunks)))
                            :keywordize-keys true))
                  (catch :default error (reject error)))))
       (.call (aget request "on") request "error" reject)))))

(defn- serve! [client]
  (let [http (js/require "node:http")
        host "127.0.0.1"
        port (js/Number (or (aget js/process.env "ISIC6492_PROVIDER_PORT") "18920"))
        server
        (.createServer
         http
         (fn [request response]
           (if (and (= "POST" (.-method request))
                    (= "/api/loan/intake" (.-url request)))
             (-> (request-body request)
                 (.then (fn [application]
                          (isic/-intake-loan-application client application)))
                 (.then (fn [result]
                          (reply! response (if (:ok? result) 200 502) result)))
                 (.catch (fn [_]
                           (reply! response 400
                                   {:ok? false :error "provider request rejected"}))))
             (reply! response 404 {:ok? false :error "not found"}))))]
    (.listen server port host
             (fn []
               (js/console.log
                (str "commitment isic6492 provider listening on " host ":" port))))))

(defn main []
  (let [base-url (or (aget js/process.env "ISIC6492_BASE_URL")
                     "https://cloud-itonami-isic-6492.pages.dev")]
    (-> (isic/live-client-from-env js/process.env base-url)
        (.then (fn [client]
                 (if client
                   (serve! client)
                   (throw (js/Error. "provider actor identity is not configured")))))
        (.catch (fn [error]
                  (js/console.error "isic6492 provider startup failed")
                  (js/console.error (.-message error))
                  (.exit js/process 78))))))

(set! *main-cli-fn* main)
