(ns commitledger.edge.kotobase-provider
  "Loopback-only storage-v1 provider for the resident commitment Component.

  The guest can address exactly one aggregate key. This sidecar owns the
  actor identity, CACAO minting, Kotobase TLS origin, lookup pulls, and the
  single-writer queue used for conditional versions."
  (:require [commitledger.edge.kotobase-http :as khttp]
            [commitledger.edge.kotobase-identity :as identity]
            [commitledger.edge.kotobase-store :as ks]
            [langchain.kotoba-db :as kdb]))

(def ^:private max-request-bytes (* 64 1024))
(def ^:private pull-pattern
  [:kotoba.storage/key :kotoba.storage/value :kotoba.storage/version])

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
                  (resolve
                   (js->clj
                    (js/JSON.parse (.toString (.concat js/Buffer chunks)))
                    :keywordize-keys true))
                  (catch :default error (reject error)))))
       (.call (aget request "on") request "error" reject)))))

(defn- entry [m]
  (when (and (string? (:kotoba.storage/value m))
             (integer? (:kotoba.storage/version m))
             (pos? (:kotoba.storage/version m)))
    {:key (:kotoba.storage/key m)
     :value (:kotoba.storage/value m)
     :version (:kotoba.storage/version m)}))

(defn- read-entry! [{:keys [api read-conn]} key]
  (.then ((:pull api) read-conn pull-pattern [:kotoba.storage/key key])
         entry))

(defn- write-entry! [{:keys [api signing-key url db-name]} key value version]
  (.then (identity/mint-kotobase-cacao! signing-key "datom:transact")
         (fn [cacao]
           (let [conn (kdb/kotoba-conn*
                       url db-name
                       {:cacao cacao :did (:did signing-key)})]
             ((:transact! api)
              conn
              [{:kotoba.storage/key key
                :kotoba.storage/value value
                :kotoba.storage/version version}])))))

(defn- handle-operation! [{:keys [aggregate-key] :as provider}
                          {:keys [operation key value expected-version]}]
  (when-not (= key aggregate-key)
    (throw (js/Error. "storage key is outside the admitted namespace")))
  (when-not (and (string? key) (<= (count key) 4096))
    (throw (js/Error. "storage key is invalid")))
  (case operation
    "get"
    (.then (read-entry! provider key)
           (fn [current]
             (if current
               (assoc current :tag "found")
               {:tag "missing" :value true})))

    "put-new"
    (do
      (when-not (and (string? value) (<= (count value) max-request-bytes))
        (throw (js/Error. "storage value is invalid")))
      (.then (read-entry! provider key)
             (fn [current]
               (if current
                 {:tag "conflict-current"
                  :key key
                  :current-version (:version current)}
                 (.then (write-entry! provider key value 1)
                        (fn [_]
                          {:tag "written" :key key :value value :version 1}))))))

    "put-existing"
    (do
      (when-not (and (string? value)
                     (<= (count value) max-request-bytes)
                     (integer? expected-version)
                     (pos? expected-version))
        (throw (js/Error. "conditional storage request is invalid")))
      (.then (read-entry! provider key)
             (fn [current]
               (cond
                 (nil? current)
                 {:tag "conflict-missing" :value true}

                 (not= expected-version (:version current))
                 {:tag "conflict-current"
                  :key key
                  :current-version (:version current)}

                 :else
                 (let [next-version (inc expected-version)]
                   (.then (write-entry! provider key value next-version)
                          (fn [_]
                            {:tag "written" :key key :value value
                             :version next-version})))))))

    (throw (js/Error. "storage operation is outside the admitted subset"))))

(defn- serve! [provider]
  (let [http (js/require "node:http")
        host "127.0.0.1"
        port (js/Number
              (or (aget js/process.env "KOTOBASE_PROVIDER_PORT") "18921"))
        tail (atom (js/Promise.resolve nil))
        server
        (.createServer
         http
         (fn [request response]
           (if (and (= "POST" (.-method request))
                    (= "/v1/storage" (.-url request)))
             (-> (request-body request)
                 (.then
                  (fn [body]
                    (let [next (.then @tail
                                      (fn [_] (handle-operation! provider body))
                                      (fn [_] (handle-operation! provider body)))]
                      (reset! tail (.catch next (fn [_] nil)))
                      next)))
                 (.then (fn [result] (reply! response 200 result)))
                 (.catch
                  (fn [_]
                    (reply! response 200
                            {:tag "error"
                             :code "storage/provider-failed"
                             :message "admitted Kotobase provider failed"
                             :retryable true}))))
             (reply! response 404
                     {:tag "error" :code "storage/not-found"
                      :message "not found" :retryable false}))))]
    (.listen server port host
             (fn []
               (js/console.log
                (str "commitment Kotobase provider listening on "
                     host ":" port))))))

(defn main []
  (let [url "https://kotobase.net"
        db-name identity/default-db-name
        aggregate-key "commitment/aggregate"
        api (ks/db-api-for khttp/fetch-http-fn)]
    (-> (identity/signing-key-from-env js/process.env)
        (.then
         (fn [signing-key]
           (when-not signing-key
             (throw (js/Error. "provider actor identity is not configured")))
           (.then
            (identity/mint-kotobase-cacao! signing-key "datom:read")
            (fn [read-cacao]
              (serve!
               {:aggregate-key aggregate-key
                :api api
                :db-name db-name
                :read-conn
                (kdb/kotoba-conn*
                 url db-name
                 {:cacao read-cacao :did (:did signing-key)})
                :signing-key signing-key
                :url url})))))
        (.catch
         (fn [error]
           (js/console.error "Kotobase provider startup failed")
           (js/console.error (.-message error))
           (.exit js/process 78))))))

(set! *main-cli-fn* main)
