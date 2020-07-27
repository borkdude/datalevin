(ns ^:no-doc datalevin.storage
  "storage layer of datalevin"
  (:require [datalevin.lmdb :as lmdb]
            [datalevin.util :as u]
            [datalevin.bits :as b]
            [datalevin.constants :as c]
            [datalevin.datom :as d]
            [taoensso.nippy :as nippy])
  (:import [java.util UUID]
           [datalevin.lmdb LMDB]
           [datalevin.datom Datom]
           [datalevin.bits Indexable Retrieved]
           [org.lmdbjava PutFlags]))

(defn- transact-schema
  [lmdb schema]
  (lmdb/transact lmdb (for [[attr props] schema]
                        [:put c/schema attr props :attr :data])))

(defn- load-schema
  [lmdb]
  (into {} (lmdb/get-range lmdb c/schema [:all] :attr :data)))

(defn- init-max-aid
  [lmdb]
  (lmdb/entries lmdb c/schema))

;; TODO schema migration
(defn- update-schema
  [lmdb old schema]
  (let [^long init-aid (init-max-aid lmdb)
        i              (atom 0)]
    (into {}
          (map (fn [[attr props]]
                 (if-let [old-props (old attr)]
                   [attr (merge old-props props)]
                   (let [res [attr (assoc props :db/aid (+ init-aid @i))]]
                     (swap! i inc)
                     res))))
          schema)))

(defn- init-schema
  [lmdb schema]
  (when (empty? (load-schema lmdb))
    (transact-schema lmdb c/implicit-schema))
  (when schema
    (let [now (load-schema lmdb)]
      (transact-schema lmdb (update-schema lmdb now schema))))
  (load-schema lmdb))

(defn- init-max-gt
  [lmdb]
  (or (when-let [gt (-> (lmdb/get-first lmdb c/giants [:all-back] :long :ignore)
                        first)]
        (inc ^long gt))
      c/gt0))

(defn- migrate-cardinality
  [lmdb attr old new]
  (when (and (= old :db.cardinality/many) (= new :db.cardinality/one))
    ;; TODO figure out if this is consistent with data
    ;; raise exception if not
    ))

(defn- handle-value-type
  [lmdb attr old new]
  (when (not= old new)
    ;; TODO raise if datom already exist for this attr
    ))

(defn- migrate-unique
  [lmdb attr old new]
  (when (and (not old) new)
    ;; TODO figure out if the attr values are unique for each entity,
    ;; raise if not
    ;; also check if ave and vae entries exist for this attr, create if not
    ))

(defn- migrate [lmdb attr old new]
  (doseq [[k v] new
          :let  [v' (old k)]]
    (case k
      :db/cardinality (migrate-cardinality lmdb attr v' v)
      :db/valueType   (handle-value-type lmdb attr v' v)
      :db/unique      (migrate-unique lmdb attr v' v)
      :pass-through)))

(defn- low-datom->indexable
  [schema ^Datom d]
  (let [e (.-e d)]
    (if-let [a (.-a d)]
      (if-let [p (schema a)]
        (if-let [v (.-v d)]
          (b/indexable e (:db/aid p) v (:db/valueType p))
          (b/indexable e (:db/aid p) c/v0 (:db/valueType p)))
        (b/indexable e c/a0 c/v0 nil))
      (if-let [v (.-v d)]
        (if (integer? v)
          (b/indexable e c/a0 v :db.type/ref)
          (u/raise "When v is known but a is unknown, v must be a :db.type/ref"
                   {:v v}))
        (b/indexable e c/a0 c/v0 :db.type/sysMin)))))

(defn- high-datom->indexable
  [schema ^Datom d]
  (let [e (.-e d)]
    (if-let [a (.-a d)]
      (if-let [p (schema a)]
        (if-let [v (.-v d)]
          (b/indexable e (:db/aid p) v (:db/valueType p))
          (b/indexable e (:db/aid p) c/vmax (:db/valueType p)))
        ;; same as low-datom-indexable to get [] fast
        (b/indexable e c/a0 c/v0 nil))
      (if-let [v (.-v d)]
        (if (integer? v)
          (b/indexable e c/amax v :db.type/ref)
          (u/raise "When v is known but a is unknown, v must be a :db.type/ref"
                   {:v v}))
        (b/indexable e c/amax c/vmax :db.type/sysMax)))))

(defn- index->dbi
  [index]
  (case index
    :eavt c/eav
    :eav  c/eav
    :aevt c/aev
    :aev  c/aev
    :avet c/ave
    :ave  c/ave
    :vaet c/vae
    :vae  c/vae))

(defn- retrieved->datom
  [lmdb attrs [^Retrieved k ^long v :as kv]]
  (when kv
    (if (= v c/normal)
      (d/datom (.-e k) (attrs (.-a k)) (.-v k))
      (lmdb/get-value lmdb c/giants v :long))))

(defn- datom-pred->kv-pred
  [lmdb attrs index pred]
  (fn [kv]
    (let [^Retrieved k (b/read-buffer (key kv) index)
          ^long v      (b/read-buffer (val kv) :long)
          ^Datom d     (retrieved->datom lmdb attrs [k v])]
      (pred d))))

(defprotocol IStore
  (close [this] "Close storage")
  (max-gt [this])
  (advance-max-gt [this])
  (max-aid [this])
  (schema [this] "Return the schema map")
  (attrs [this] "Return the aid -> attr map")
  (init-max-eid [this] "Initialize and return the max entity id")
  (datom-count [this index] "Return the number of datoms in the index")
  (swap-attr [this attr f] [this attr f x] [this attr f x y]
    "Update an attribute, f is similar to that of swap!")
  (load-datoms [this datoms] "Load datams into storage")
  (fetch [this datom] "Return [datom] if it exists in store, otherwise '()")
  (populated? [this index low-datom high-datom]
    "Return true if there exists at least one datom in the given boundary (inclusive)")
  (size [this index low-datom high-datom]
    "Return the number of datoms within the given range (inclusive)")
  (head [this index low-datom high-datom]
    "Return the first datom within the given range (inclusive)")
  (slice [this index low-datom high-datom]
    "Return a range of datoms within the given range (inclusive).")
  (rslice [this index high-datom low-datom]
    "Return a range of datoms in reverse within the given range (inclusive)")
  (size-filter [this index pred low-datom high-datom]
    "Return the number of datoms within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (head-filter [this index pred low-datom high-datom]
    "Return the first datom within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (slice-filter [this index pred low-datom high-datom]
    "Return a range of datoms within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (rslice-filter [this index pred high-datom low-datom]
    "Return a range of datoms in reverse for the given range (inclusive)
    that return true for (pred x), where x is the datom"))

(declare insert-data delete-data)

(deftype Store [^LMDB lmdb
                ^:volatile-mutable schema
                ^:volatile-mutable attrs
                ^:volatile-mutable ^long max-aid
                ^:volatile-mutable ^long max-gt]
  IStore
  (close [_]
    (lmdb/close lmdb))

  (max-gt [_]
    max-gt)

  (advance-max-gt [_]
    (set! max-gt (inc max-gt)))

  (max-aid [_]
    max-aid)

  (schema [_]
    schema)

  (attrs [_]
    attrs)

  (init-max-eid [_]
    (or (when-let [[r _] (lmdb/get-first lmdb c/eav [:all-back] :eav :ignore)]
          (.-e ^Retrieved r))
        c/e0))

  (swap-attr [this attr f]
    (swap-attr this attr f nil nil))
  (swap-attr [this attr f x]
    (swap-attr this attr f x nil))
  (swap-attr [_ attr f x y]
    (let [o (or (schema attr)
                (let [m {:db/aid max-aid}]
                  (set! max-aid (inc max-aid))
                  m))
          p (cond
              (and x y) (f o x y)
              x         (f o x)
              :else     (f o))]
      (migrate lmdb attr o p)
      (transact-schema lmdb {attr p})
      (set! schema (assoc schema attr p))
      (set! attrs (assoc attrs (:db/aid p) attr))
      p))

  (datom-count [_ index]
    (lmdb/entries lmdb (if (string? index) index (index->dbi index))))

  (load-datoms [this datoms]
    (locking this
      (let [add-fn (fn [holder datom]
                     (let [conj-fn (fn [h d] (conj! h d))]
                       (if (d/datom-added datom)
                         (let [[data giant?] (insert-data this datom)]
                           (when giant? (advance-max-gt this))
                           (reduce conj-fn holder data))
                         (reduce conj-fn holder (delete-data this datom)))))
            data   (persistent! (reduce add-fn (transient []) datoms))]
        (lmdb/transact lmdb data))))

  (fetch [this datom]
    (mapv (partial retrieved->datom lmdb attrs)
          (when-let [kv (lmdb/get-value lmdb
                                        c/eav
                                        (low-datom->indexable schema datom)
                                        :eav
                                        :long
                                        false)]
            [kv])))

  (populated? [_ index low-datom high-datom]
    (lmdb/get-first lmdb
                    (index->dbi index)
                    [:closed
                     (low-datom->indexable schema low-datom)
                     (high-datom->indexable schema high-datom)]
                    index
                    :ignore
                    true))

  (size [_ index low-datom high-datom]
    (lmdb/range-count lmdb
                      (index->dbi index)
                      [:closed
                       (low-datom->indexable schema low-datom)
                       (high-datom->indexable schema high-datom)]
                      index))

  (head [_ index low-datom high-datom]
    (retrieved->datom
     lmdb attrs (lmdb/get-first lmdb
                                (index->dbi index)
                                [:closed
                                 (low-datom->indexable schema low-datom)
                                 (high-datom->indexable schema high-datom)]
                                index
                                :long)))

  (slice [_ index low-datom high-datom]
    (mapv (partial retrieved->datom lmdb attrs)
          (lmdb/get-range
           lmdb
           (index->dbi index)
           [:closed
            (low-datom->indexable schema low-datom)
            (high-datom->indexable schema high-datom)]
           index
           :long)))

  (rslice [_ index high-datom low-datom]
    (mapv (partial retrieved->datom lmdb attrs)
          (lmdb/get-range
           lmdb
           (index->dbi index)
           [:closed-back
            (high-datom->indexable schema high-datom)
            (low-datom->indexable schema low-datom)]
           index
           :long)))

  (size-filter [_ index pred low-datom high-datom]
    (lmdb/range-filter-count lmdb
                             (index->dbi index)
                             (datom-pred->kv-pred lmdb attrs index pred)
                             [:closed
                              (low-datom->indexable schema low-datom)
                              (high-datom->indexable schema high-datom)]
                             index))

  (head-filter [_ index pred low-datom high-datom]
    (retrieved->datom
     lmdb attrs (lmdb/get-some lmdb
                                (index->dbi index)
                                (datom-pred->kv-pred lmdb attrs index pred)
                                [:closed
                                 (low-datom->indexable schema low-datom)
                                 (high-datom->indexable schema high-datom)]
                                index
                                :long)))

  (slice-filter [_ index pred low-datom high-datom]
    (mapv
     (partial retrieved->datom lmdb attrs)
     (lmdb/range-filter
      lmdb
      (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed
       (low-datom->indexable schema low-datom)
       (high-datom->indexable schema high-datom)]
      index
      :long)))

  (rslice-filter [_ index pred high-datom low-datom]
    (mapv
     (partial retrieved->datom lmdb attrs)
     (lmdb/range-filter
      lmdb
      (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed
       (high-datom->indexable schema high-datom)
       (low-datom->indexable schema low-datom)]
      index
      :long))))

(defn- insert-data
  [^Store store ^Datom d]
  (let [attr   (.-a d)
        props  (or ((schema store) attr)
                   (swap-attr store attr identity))
        ref?   (= :db.type/ref (:db/valueType props))
        i      (b/indexable (.-e d) (:db/aid props) (.-v d)
                            (:db/valueType props))
        max-gt (max-gt store)]
      (if (b/giant? i)
        [(cond-> [[:put c/eav i max-gt :eav :long]
                  [:put c/aev i max-gt :aev :long]
                  [:put c/ave i max-gt :ave :long]
                  [:put c/giants max-gt d :long :datom [PutFlags/MDB_APPEND]]]
           ref? (conj [:put c/vae i max-gt :vae :long]))
         true]
        [(cond-> [[:put c/eav i c/normal :eav :long]
                  [:put c/aev i c/normal :aev :long]
                  [:put c/ave i c/normal :ave :long]]
           ref? (conj [:put c/vae i c/normal :vae :long]))
         false])))

(defn- delete-data
  [^Store store ^Datom d]
  (let [props  ((schema store) (.-a d))
        ref?   (= :db.type/ref (:db/valueType props))
        i      (b/indexable (.-e d)
                            (:db/aid props)
                            (.-v d)
                            (:db/valueType props))
        giant? (b/giant? i)]
    (cond-> [[:del c/eav i :eav]
             [:del c/aev i :aev]
             [:del c/ave i :ave]]
      ref?   (conj [:del c/vae i :vae])
      giant? (conj [:del c/giants
                    (lmdb/get-value (.-lmdb store) c/eav i :eav :long)
                    :long]))))

(defn- init-attrs [schema]
  (into {} (map (fn [[k v]] [(:db/aid v) k])) schema))

(defn open
  "Open and return the storage."
  ([]
   (open nil nil))
  ([schema]
   (open schema nil))
  ([schema dir]
   (let [dir  (or dir (str "/tmp/datalevin-" (UUID/randomUUID)))
         lmdb (lmdb/open-lmdb dir)]
     (lmdb/open-dbi lmdb c/eav c/+max-key-size+ Long/BYTES)
     (lmdb/open-dbi lmdb c/aev c/+max-key-size+ Long/BYTES)
     (lmdb/open-dbi lmdb c/ave c/+max-key-size+ Long/BYTES)
     (lmdb/open-dbi lmdb c/vae c/+max-key-size+ Long/BYTES)
     (lmdb/open-dbi lmdb c/giants Long/BYTES)
     (lmdb/open-dbi lmdb c/schema c/+max-key-size+)
     (let [schema' (init-schema lmdb schema)]
       (->Store lmdb
                schema'
                (init-attrs schema')
                (init-max-aid lmdb)
                (init-max-gt lmdb))))))