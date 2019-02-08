(ns crux.bootstrap.standalone
  (:require [clojure.spec.alpha :as s]
            [crux.bootstrap :as b]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.io :as cio]
            [crux.index :as idx]
            [crux.kv :as kv]
            [crux.lru :as lru]
            [crux.query :as q]
            [crux.status :as status]
            [crux.tx :as tx])
  (:import java.io.Closeable
           crux.api.ICruxSystem
           crux.bootstrap.CruxNode))

;; TODO: The direct dependency on LocalNode here will go.

(defrecord StandaloneSystem [kv-store event-log-kv-store event-log-consumer tx-log options]
  ICruxSystem
  (db [this]
    (.db ^CruxNode (b/map->CruxNode this)))

  (db [this business-time]
    (.db ^CruxNode (b/map->CruxNode this) business-time))

  (db [this business-time transact-time]
    (.db ^CruxNode (b/map->CruxNode this) business-time transact-time))

  (document [this content-hash]
    (.document ^CruxNode (b/map->CruxNode this) content-hash))

  (history [this eid]
    (.history ^CruxNode (b/map->CruxNode this) eid))

  (status [this]
    (.status ^CruxNode (b/map->CruxNode this)))

  (submitTx [this tx-ops]
    (.submitTx ^CruxNode (b/map->CruxNode this) tx-ops))

  (hasSubmittedTxUpdatedEntity [this submitted-tx eid]
    (.hasSubmittedTxUpdatedEntity ^CruxNode (b/map->CruxNode this) submitted-tx eid))

  (hasSubmittedTxCorrectedEntity [this submitted-tx business-time eid]
    (.hasSubmittedTxCorrectedEntity ^CruxNode (b/map->CruxNode this) submitted-tx business-time eid))

  (newTxLogContext [this]
    (.newTxLogContext ^CruxNode (b/map->CruxNode this)))

  (txLog [this tx-log-context from-tx-id with-documents?]
    (.txLog ^CruxNode (b/map->CruxNode this) tx-log-context from-tx-id with-documents?))

  (sync [this timeout]
    (.sync ^CruxNode (b/map->CruxNode this) timeout))

  Closeable
  (close [_]
    (doseq [c [event-log-consumer event-log-kv-store kv-store]]
      (cio/try-close c))))

(s/def ::standalone-options (s/keys :req-un [:crux.kv/db-dir :crux.kv/kv-backend]
                                    :opt-un [:crux.kv/sync? :crux.tx/event-log-dir]
                                    :opt [:crux.tx/event-log-sync-interval-ms
                                          :crux.tx/event-log-kv-backend]))

(defn start-standalone-system ^ICruxSystem [{:keys [db-dir sync? kv-backend event-log-dir
                                                    crux.tx/event-log-kv-backend crux.tx/event-log-sync-interval-ms] :as options}]
  (s/assert ::standalone-options options)
  (let [kv-store (b/start-kv-store
                  (merge (when-not event-log-dir
                           {:sync? true})
                         options))
        event-log-sync? (boolean (or sync? (not event-log-sync-interval-ms)))
        event-log-kv-store (when event-log-dir
                             (b/start-kv-store
                              {:db-dir event-log-dir
                               :kv-backend (or event-log-kv-backend kv-backend)
                               :sync? event-log-sync?
                               :crux.index/check-and-store-index-version false}))
        tx-log (if event-log-kv-store
                 (tx/->EventTxLog event-log-kv-store)
                 (tx/->KvTxLog kv-store))
        object-store (lru/new-cached-object-store kv-store)
        indexer (tx/->KvIndexer kv-store tx-log object-store)
        event-log-consumer (when event-log-kv-store
                             (tx/start-event-log-consumer event-log-kv-store indexer (when-not sync?
                                                                                       event-log-sync-interval-ms)))]
    (map->StandaloneSystem {:kv-store kv-store
                            :event-log-kv-store event-log-kv-store
                            :tx-log tx-log
                            :object-store object-store
                            :indexer indexer
                            :event-log-consumer event-log-consumer
                            :options options})))