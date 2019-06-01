(ns com.fulcrologic.fulcro.networking.transit-packer
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ot]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid #?@(:cljs [:refer [TempId]])]
    [taoensso.sente.packers.transit :as st])
  #?(:clj
     (:import [com.cognitect.transit ReadHandler]
              [com.fulcrologic.fulcro.algorithms.tempid TempId])))

(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  #?(:clj  (st/->TransitPacker :json
             {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                          write (merge write))}
             {:handlers (cond-> {tempid/tag (reify
                                              ReadHandler
                                              (fromRep [_ id] (TempId. id)))}
                          read (merge read))})
     :cljs (st/->TransitPacker :json
             {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                          write (merge write))}
             {:handlers (cond-> {tempid/tag (fn [id] (tempid/tempid id))}
                          read (merge read))})))
