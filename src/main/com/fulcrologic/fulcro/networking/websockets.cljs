(ns com.fulcrologic.fulcro.networking.websockets
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.networking.transit-packer :as tp]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [taoensso.sente :as sente :refer [cb-success?]]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]))

(defn- make-event-handler
  "Probably need to make it possible for extension from outside."
  [push-handler]
  (fn [{:keys [id ?data]}]
    (case id
      :api/server-push (when push-handler (push-handler ?data))
      nil)))

(defn fulcro-websocket-remote
  "Creates a websocket-based Fulcro remote. Requires you add the corresponding Fulrcro websocket middleware to your
  server.

   Params map can contain:

   - `websockets-uri` - (optional) The uri to handle websocket traffic on. (ex. \"/chsk\", which is the default value)
   - `push-handler` - (optional) A function (fn [{:keys [topic msg]}] ...) that can handle a push message.
                      The topic is the server push verb, and the message will be the EDN sent.
   - `host` - (optional) Host option to send to sente
   - `req-params` - (optional) Params for sente socket creation
   - `transit-handlers` - (optional) A map with optional :read and :write keys that given added sente packer.
   - `state-callback` (optional) - Callback that runs when the websocket state of the websocket changes.
                                   The function takes an old state parameter and a new state parameter (arity 2 function).
                                   `state-callback` can be either a function, or an atom containing a function.
   - `global-error-callback` - (optional) A function (fn [resp] ...) that is called when returned status code from the server is not 200.
   - `auto-retry?` - A boolean (default false). If set to true any network disconnects will lead to infinite retries until
                     the network returns. All remote mutations should be idempotent.
   - `sente-options` - (optional) A map of options that is passed directly to the sente websocket channel construction (see sente docs).
   - `csrf-token` - (optional) The CSRF token provided by the server (embedded in HTML. See Dev Guide).
   "
  [{:keys [websockets-uri global-error-callback push-handler host req-params
           state-callback transit-handlers auto-retry? sente-options
           csrf-token] :as options}]
  (let [csrf-token (or csrf-token "NO CSRF TOKEN SUPPLIED")
        queue (async/chan)
        send! (fn send* [edn result-handler] (async/go (async/>! queue {:edn edn :handler result-handler})))
        transmit! (fn transmit*! [_ {::txn/keys [ast result-handler] :as req}]
                    (log/info "Transmit " req)
                    (let [edn (eql/ast->query ast)]
                      (send! edn result-handler)))
        websockets-uri (or websockets-uri "/chsk")
        fwstate (atom
                  (merge options
                    {:channel-socket nil
                     :queue          queue
                     :ready?         false
                     :auto-retry?    auto-retry?}))
        remote {::fwstate  fwstate
                :transmit! transmit!}]
    ;; START IT ALL
    (let [{:keys [ch-recv state send-fn] :as cs} (sente/make-channel-socket-client!
                                                   websockets-uri ; path on server
                                                   csrf-token
                                                   (merge {:packer         (tp/make-packer transit-handlers)
                                                           :host           host
                                                           :type           :auto ; e/o #{:auto :ajax :ws}
                                                           :backoff-ms-fn  (fn [attempt] (min (* attempt 1000) 4000))
                                                           :params         req-params
                                                           :wrap-recv-evs? false}
                                                     sente-options))
          message-received (make-event-handler push-handler)]
      (add-watch state ::ready (fn [_ _ _ n]
                                 (if auto-retry?
                                   (do
                                     ;; prevent send attempts until open again.
                                     (log/debug "Recording status change on websocket" n)
                                     (swap! fwstate assoc :ready? (:open? n)))
                                   ;; not auto-retry: so just bring it up the first time and essentially ignore updates.
                                   (when (:open? n)
                                     (swap! fwstate assoc :ready? true)))))
      (cond
        (fn? state-callback) (add-watch state ::state-callback (fn [_ _ o n] (state-callback o n)))
        (instance? Atom state-callback) (add-watch state ::state-callback (fn [_ _ o n] (@state-callback o n))))
      (swap! fwstate assoc :channel-socket cs)
      (sente/start-chsk-router! ch-recv message-received)

      (async/go-loop []
        (if (some-> fwstate deref :ready?)
          (let [{:keys [edn handler]} (async/<! queue)]
            (try
              (send-fn [:fulcro.client/API edn] 30000
                (fn process-response [resp]
                  (log/info "processing response" resp)
                  (if (cb-success? resp)
                    (let [{:keys [status body]} resp]
                      (handler {:status-code status
                                :transaction edn
                                :body        body})
                      (when (and (not= 200 status) global-error-callback)
                        (global-error-callback resp)))
                    (if auto-retry?
                      (do
                        ; retry...sente already does connection back-off, so probably don't need back-off here
                        (js/setTimeout #(send! edn handler) 1000))
                      (let [body {:fulcro.server/error :network-disconnect}]
                        (handler {:status-code 408 :body body})
                        (when global-error-callback
                          (global-error-callback {:status 408 :body body})))))))
              (catch :default e
                (log/error "Sente send failure!" e))))
          (do
            (log/info "Send attempted before channel ready...waiting")
            (async/<! (async/timeout 1000))))
        (recur))
      remote)))

(defn reconnect!
  "Request that the given websockets networking component disconnect/reconnect.  Useful
  after login to ensure updated cookies are present on the request that re-establishes the websocket."
  [websockets]
  (let [chsk (some-> websockets ::fwstate deref :channel-socket :chsk)]
    (sente/chsk-reconnect! chsk)))
