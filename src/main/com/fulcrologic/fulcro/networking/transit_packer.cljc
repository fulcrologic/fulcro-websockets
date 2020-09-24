(ns com.fulcrologic.fulcro.networking.transit-packer
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ot]
    [taoensso.timbre :as log]
    [taoensso.sente.packers.transit :as st])
  #?(:clj
     (:import [com.cognitect.transit ReadHandler]
              [com.fulcrologic.fulcro.algorithms.tempid TempId])))

(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  (log/info "Building websocket packer with support for the following custom types: " (keys (ot/read-handlers)))
  (st/->TransitPacker :json
    {:handlers (cond-> (ot/write-handlers)
                 write (merge write))}
    {:handlers (cond-> (ot/read-handlers)
                 read (merge read))}))
