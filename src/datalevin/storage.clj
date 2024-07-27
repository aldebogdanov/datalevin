(ns ^:no-doc datalevin.storage
  "Storage layer of Datalog store"
  (:refer-clojure :exclude [update assoc])
  (:require
   [datalevin.lmdb :as lmdb :refer [IWriting]]
   [datalevin.inline :refer [update assoc]]
   [datalevin.util :as u :refer [conjs]]
   [datalevin.relation :as r]
   [datalevin.bits :as b]
   [datalevin.search :as s]
   [datalevin.constants :as c]
   [datalevin.datom :as d]
   [clojure.string :as str])
  (:import
   [java.util UUID List Random Comparator Collection]
   [java.util.concurrent ScheduledExecutorService Executors TimeUnit Callable
    LinkedBlockingQueue]
   [java.nio ByteBuffer]
   [org.eclipse.collections.impl.list.mutable FastList]
   [org.eclipse.collections.impl.list.mutable.primitive LongArrayList]
   [org.eclipse.collections.impl.map.mutable UnifiedMap]
   [org.eclipse.collections.impl.map.mutable.primitive LongObjectHashMap]
   [org.eclipse.collections.impl.map.mutable.primitive LongIntHashMap]
   [org.eclipse.collections.impl.map.mutable.primitive IntLongHashMap]
   [org.eclipse.collections.impl.map.mutable.primitive IntObjectHashMap]
   [org.eclipse.collections.impl.set.mutable UnifiedSet]
   [org.eclipse.collections.impl.set.mutable.primitive LongHashSet]
   [datalevin.datom Datom]
   [datalevin.bits Retrieved Indexable]))

(if (u/graal?)
  (require 'datalevin.binding.graal)
  (require 'datalevin.binding.java))

(defn- attr->properties [k v]
  (case v
    :db.unique/identity  [:db/unique :db.unique/identity]
    :db.unique/value     [:db/unique :db.unique/value]
    :db.cardinality/many [:db.cardinality/many]
    (case k
      :db/tupleAttrs [:db.type/tuple :db/tupleAttrs]
      :db/tupleType  [:db.type/tuple :db/tupleType]
      :db/tupleTypes [:db.type/tuple :db/tupleTypes]
      (cond
        (and (identical? :db/valueType k)
             (identical? :db.type/ref v)) [:db.type/ref]
        (and (identical? :db/isComponent k)
             (true? v))                   [:db/isComponent]
        :else                             []))))

(defn attr-tuples
  "e.g. :reg/semester => #{:reg/semester+course+student ...}"
  [schema rschema]
  (reduce
    (fn [m tuple-attr] ;; e.g. :reg/semester+course+student
      (u/reduce-indexed
        (fn [m src-attr idx] ;; e.g. :reg/semester
          (update m src-attr assoc tuple-attr idx))
        m ((schema tuple-attr) :db/tupleAttrs)))
    {} (rschema :db/tupleAttrs)))

(defn schema->rschema
  ":db/unique           => #{attr ...}
   :db.unique/identity  => #{attr ...}
   :db.unique/value     => #{attr ...}
   :db.cardinality/many => #{attr ...}
   :db.type/ref         => #{attr ...}
   :db/isComponent      => #{attr ...}
   :db.type/tuple       => #{attr ...}
   :db/tupleAttr        => #{attr ...}
   :db/tupleType        => #{attr ...}
   :db/tupleTypes       => #{attr ...}
   :db/attrTuples       => {attr => {tuple-attr => idx}}"
  [schema]
  (let [rschema (reduce-kv
                  (fn [rschema attr attr-schema]
                    (reduce-kv
                      (fn [rschema key value]
                        (reduce
                          (fn [rschema prop]
                            (update rschema prop conjs attr))
                          rschema (attr->properties key value)))
                      rschema attr-schema))
                  {} schema)]
    (assoc rschema :db/attrTuples (attr-tuples schema rschema))))

(defn- transact-schema
  [lmdb schema]
  (lmdb/transact-kv
    lmdb
    (conj (for [[attr props] schema]
            (lmdb/kv-tx :put c/schema attr props :attr :data))
          (lmdb/kv-tx :put c/meta :last-modified
                      (System/currentTimeMillis) :attr :long))))

(defn- load-schema
  [lmdb]
  (into {} (lmdb/get-range lmdb c/schema [:all] :attr :data)))

(defn- init-max-aid
  [schema]
  (inc ^long (apply max (map :db/aid (vals schema)))))

(defn- update-schema
  [old schema]
  (let [^long init-aid (init-max-aid old)
        i              (volatile! 0)]
    (into {}
          (map (fn [[attr props]]
                 (if-let [old-props (old attr)]
                   [attr (assoc props :db/aid (old-props :db/aid))]
                   (let [res [attr (assoc props :db/aid (+ init-aid ^long @i))]]
                     (vswap! i u/long-inc)
                     res))))
          schema)))

(defn- init-schema
  [lmdb schema]
  (when (empty? (load-schema lmdb))
    (transact-schema lmdb c/implicit-schema))
  (when schema
    (transact-schema lmdb (update-schema (load-schema lmdb) schema)))
  (let [now (load-schema lmdb)]
    (when-not (now :db/created-at)
      (transact-schema lmdb (update-schema now c/entity-time-schema))))
  (load-schema lmdb))

(defn- init-attrs [schema]
  (into {} (map (fn [[k v]] [(v :db/aid) k])) schema))

(defn- init-max-gt
  [lmdb]
  (or (when-let [gt (-> (lmdb/get-first lmdb c/giants [:all-back] :id :ignore)
                        first)]
        (inc ^long gt))
      c/g0))

(defn- init-max-tx
  [lmdb]
  (or (lmdb/get-value lmdb c/meta :max-tx :attr :long)
      c/tx0))

(defn- value-type
  [props]
  (if-let [vt (props :db/valueType)]
    (if (identical? vt :db.type/tuple)
      (if-let [tts (props :db/tupleTypes)]
        tts
        (if-let [tt (props :db/tupleType)] [tt] :data))
      vt)
    :data))

(defn- datom->indexable
  [schema ^Datom d high?]
  (let [e  (if-some [e (.-e d)] e (if high? c/emax c/e0))
        vm (if high? c/vmax c/v0)
        gm (if high? c/gmax c/g0)]
    (if-let [a (.-a d)]
      (if-let [p (schema a)]
        (if-some [v (.-v d)]
          (b/indexable e (p :db/aid) v (value-type p) gm)
          (b/indexable e (p :db/aid) vm (value-type p) gm))
        (b/indexable e c/a0 c/v0 nil gm))
      (let [am (if high? c/amax c/a0)]
        (if-some [v (.-v d)]
          (if (integer? v)
            (if e
              (b/indexable e am v :db.type/ref gm)
              (b/indexable (if high? c/emax c/e0) am v :db.type/ref gm))
            (u/raise
              "When v is known but a is unknown, v must be a :db.type/ref"
              {:v v}))
          (b/indexable e am vm :db.type/sysMin gm))))))

(defn- index->dbi
  [index]
  (case index
    :eav c/eav
    :ave c/ave
    :vae c/vae))

(defn- index->vtype
  [index]
  (case index
    :eav :avg
    :ave :eg
    :vae :ae))

(defn- index->ktype
  [index]
  (case index
    :eav :id
    :vae :id
    :ave :av))

(defn- index->k
  [index schema ^Datom datom high?]
  (case index
    :eav (or (.-e datom) (if high? c/emax c/e0))
    :ave (datom->indexable schema datom high?)
    :vae (or (.-v datom) (if high? c/vmax c/v0))))

(defn gt->datom [lmdb gt] (lmdb/get-value lmdb c/giants gt :id :data))

(defprotocol IStore
  (opts [this] "Return the opts map")
  (assoc-opt [this k v] "Set an option")
  (db-name [this] "Return the db-name")
  (dir [this] "Return the data file directory")
  (close [this] "Close storage")
  (closed? [this] "Return true if the storage is closed")
  (last-modified [this]
    "Return the unix timestamp of when the store is last modified")
  (max-gt [this])
  (advance-max-gt [this])
  (max-tx [this])
  (advance-max-tx [this])
  (max-aid [this])
  (schema [this] "Return the schema map")
  (rschema [this] "Return the reverse schema map")
  (set-schema [this new-schema]
    "Update the schema of open storage, return updated schema")
  (attrs [this] "Return the aid -> attr map")
  (init-max-eid [this] "Initialize and return the max entity id")
  (datom-count [this index] "Return the number of datoms in the index")
  (swap-attr [this attr f] [this attr f x] [this attr f x y]
    "Update the properties of an attribute, f is similar to that of swap!")
  (del-attr [this attr]
    "Delete an attribute, throw if there is still datom related to it")
  (rename-attr [this attr new-attr] "Rename an attribute")
  (load-datoms [this datoms] "Load datams into storage")
  (fetch [this datom] "Return [datom] if it exists in store, otherwise '()")
  (populated? [this index low-datom high-datom]
    "Return true if there exists at least one datom in the given boundary (inclusive)")
  (size [this index low-datom high-datom] [this index low-datom high-datom cap]
    "Return the number of datoms within the given range (inclusive)")
  (e-size [this e]
    "Return the numbers of datoms with the given e value")
  (a-size [this a]
    "Return the number of datoms with the given attribute, an estimate")
  (e-sample [this a]
    "Return a sample of eids sampled from full value range of an attribute")
  (v-size [this v]
    "Return the numbers of ref datoms with the given v value")
  (av-size [this a v]
    "Return the numbers of datoms with the given a and v value")
  (av-range-size [this a lv hv] [this a lv hv cap]
    "Return the numbers of datoms with given a and v range")
  (cardinality [this a]
    "Return the number of distinct values of an attribute")
  (head [this index low-datom high-datom]
    "Return the first datom within the given range (inclusive)")
  (tail [this index high-datom low-datom]
    "Return the last datom within the given range (inclusive)")
  (slice [this index low-datom high-datom]
    "Return a range of datoms within the given range (inclusive).")
  (rslice [this index high-datom low-datom]
    "Return a range of datoms in reverse within the given range (inclusive)")
  (e-datoms [this e] "Return datoms with given e value")
  (av-datoms [this a v] "Return datoms with given a and v value")
  (v-datoms [this v] "Return datoms with given v, for ref attribute only")
  (size-filter
    [this index pred low-datom high-datom]
    [this index pred low-datom high-datom cap]
    "Return the number of datoms within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (head-filter [this index pred low-datom high-datom]
    "Return the first datom within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (tail-filter [this index pred high-datom low-datom]
    "Return the last datom within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (slice-filter [this index pred low-datom high-datom]
    "Return a range of datoms within the given range (inclusive) that
    return true for (pred x), where x is the datom")
  (rslice-filter [this index pred high-datom low-datom]
    "Return a range of datoms in reverse for the given range (inclusive)
    that return true for (pred x), where x is the datom")
  (ave-tuples
    [this out attr val-range]
    [this out attr val-range vpred]
    [this out attr val-range vpred get-v?]
    [this out attr val-range vpred get-v? indices]
    "Return tuples of e or e and v using :ave index")
  (sample-ave-tuples
    [this out attr mcount val-range vpred get-v?]
    "Return sampled tuples of e or e and v using :ave index")
  (eav-scan-v
    [this in out eid-idx attrs-v]
    "Scan values and merge into tuples using :eav index.")
  (val-eq-scan-e [this in out v-idx attr] [this in out v-idx attr bound]
    "Return tuples with eid as the last column for given attribute values")
  (val-eq-filter-e [this in out v-idx attr f-idx]
    "Return tuples filtered by the given attribute values")
  ;; (sample-link-e [this vs attr mcount]
  ;;   "Return tuples with eid as the only column sampled by attribute values")
  )

(defn e-aid-v->datom
  [store e-aid-v]
  (d/datom (nth e-aid-v 0) ((attrs store) (nth e-aid-v 1)) (peek e-aid-v)))

(defn- retrieved->v
  [lmdb ^Retrieved r]
  (let [g (.-g r)]
    (if (= g c/normal)
      (.-v r)
      (d/datom-v (gt->datom lmdb g)))))

(defn- ave-kv->retrieved
  [lmdb ^Retrieved av ^Retrieved eg]
  (let [e     (.-e eg)
        a     (.-a av)
        g     (.-g eg)
        r     (Retrieved. e a (.-v av) g)
        value (retrieved->v lmdb r)]
    (Retrieved. e a value g)))

(defn- retrieved->datom
  [lmdb attrs [k ^Retrieved r :as kv]]
  (when kv
    (if (instance? Retrieved k)
      (let [^Retrieved r (ave-kv->retrieved lmdb k r)]
        (d/datom (.-e r) (attrs (.-a r)) (.-v r)))
      (let [g (.-g r)]
        (if (or (nil? g) (= g c/normal))
          (let [e (.-e r)
                a (.-a r)
                v (.-v r)]
            (cond
              (nil? v) (d/datom e (attrs a) k)
              (nil? e) (d/datom k (attrs a) v)))
          (gt->datom lmdb g))))))

(defn- avg-retrieved->datom
  [lmdb attrs e ^Retrieved r]
  (let [g (.-g r)]
    (if (= g c/normal)
      (d/datom e (attrs (.-a r)) (.-v r))
      (gt->datom lmdb g))))

(defn- ae-retrieved->datom
  [attrs v ^Retrieved r]
  (d/datom (.-e r) (attrs (.-a r)) v))

(defn- datom-pred->kv-pred
  [lmdb attrs index pred]
  (fn [kv]
    (let [k (b/read-buffer (lmdb/k kv) (index->ktype index))
          v (b/read-buffer (lmdb/v kv) (index->vtype index))]
      (pred (retrieved->datom lmdb attrs [k v])))))

(defn- ave-key-range
  [aid vt val-range]
  (let [[[cl lv] [ch hv]] val-range
        op                (cond
                            (and (identical? cl :closed)
                                 (identical? ch :closed)) :closed
                            (identical? ch :closed)       :open-closed
                            (identical? cl :closed)       :closed-open
                            :else                         :open)]
    [op (b/indexable nil aid lv vt nil) (b/indexable nil aid hv vt nil)]))

(defn- retrieve-ave
  [lmdb kv]
  (ave-kv->retrieved
    lmdb (b/read-buffer (lmdb/k kv) :av) (b/read-buffer (lmdb/v kv) :eg)))

(defn- sampling
  [i j ^longs sample-indices work]
  (let [len (alength sample-indices)]
    (fn [kv]
      (let [^long vi @i]
        (if (== vi len)
          :datalevin/terminate-visit
          (do (when (== ^long @j (aget sample-indices vi))
                (work kv)
                (vswap! i u/long-inc))
              (vswap! j u/long-inc)))))))

(defn- ave-tuples-scan-need-v
  [lmdb ^Collection out aid vt val-ranges sample-indices]
  (let [c     (volatile! 0)
        work  (fn [kv]
                (let [^Retrieved r (retrieve-ave lmdb kv)]
                  (.add out (object-array [(.-e r) (.-v r)]))
                  (vswap! c u/long-inc)))
        i     (volatile! 0)
        j     (volatile! 0)
        len   (when sample-indices (alength ^longs sample-indices))
        visit (if sample-indices
                (sampling i j sample-indices work)
                work)]
    (doseq [val-range val-ranges]
      (when (or (nil? len) (< ^long @i ^long len))
        (lmdb/visit-list-range
          lmdb c/ave visit (ave-key-range aid vt val-range) :av [:all] :eg)))
    (.add out :datalevin/end-scan)
    @c))

(defn- ave-tuples-scan-need-v-vpred
  [lmdb ^Collection out vpred aid vt val-ranges sample-indices]
  (let [c (volatile! 0)

        work  (fn [kv]
                (let [^Retrieved r (retrieve-ave lmdb kv)
                      v            (.-v r)]
                  (when (vpred v)
                    (.add out (object-array [(.-e r) v]))
                    (vswap! c u/long-inc))))
        i     (volatile! 0)
        j     (volatile! 0)
        len   (when sample-indices (alength ^longs sample-indices))
        visit (if sample-indices
                (sampling i j sample-indices work)
                work)]
    (doseq [val-range val-ranges]
      (when (or (nil? len) (< ^long @i ^long len))
        (lmdb/visit-list-range
          lmdb c/ave visit (ave-key-range aid vt val-range) :av [:all] :eg)))
    (.add out :datalevin/end-scan )
    @c))

(defn- ave-tuples-scan-no-v
  [lmdb ^Collection out aid vt val-ranges sample-indices]
  (let [c     (volatile! 0)
        work  (fn [kv]
                (.add out (object-array [(b/read-buffer (lmdb/v kv) :id)]))
                (vswap! c u/long-inc))
        i     (volatile! 0)
        j     (volatile! 0)
        len   (when sample-indices (alength ^longs sample-indices))
        visit (if sample-indices
                (sampling i j sample-indices work)
                work)]
    (doseq [val-range val-ranges]
      (when (or (nil? len) (< ^long @i ^long len))
        (lmdb/visit-list-range
          lmdb c/ave visit (ave-key-range aid vt val-range) :av [:all] :eg)))
    (.add out :datalevin/end-scan)
    @c))

(defn- ave-tuples-scan-no-v-vpred
  [lmdb ^Collection out vpred aid vt val-ranges sample-indices]
  (let [c     (volatile! 0)
        work  (fn [kv]
                (let [^Retrieved r (retrieve-ave lmdb kv)
                      v            (.-v r)]
                  (when (vpred v)
                    (.add out (object-array [(.-e r)]))
                    (vswap! c u/long-inc))))
        i     (volatile! 0)
        j     (volatile! 0)
        len   (when sample-indices (alength ^longs sample-indices))
        visit (if sample-indices
                (sampling i j sample-indices work)
                work)]
    (doseq [val-range val-ranges]
      (when (or (nil? len) (< ^long @i ^long len))
        (lmdb/visit-list-range
          lmdb c/ave visit (ave-key-range aid vt val-range) :av [:all] :eg)))
    (.add out :datalevin/end-scan)
    @c))

(defn- sort-tuples-by-eid
  [tuples ^long eid-idx]
  (if (vector? tuples)
    (sort-by #(nth % eid-idx) tuples)
    (doto ^List tuples
      (.sort (reify Comparator
               (compare [_ a b]
                 (- ^long (aget ^objects a eid-idx)
                    ^long (aget ^objects b eid-idx))))))))

(declare insert-datom delete-datom fulltext-index check transact-opts)

(deftype Store [lmdb
                search-engines
                ^IntLongHashMap counts
                ^IntObjectHashMap i-eids
                ^IntObjectHashMap d-eids
                ^ScheduledExecutorService scheduler
                ^:volatile-mutable opts
                ^:volatile-mutable schema
                ^:volatile-mutable rschema
                ^:volatile-mutable attrs    ; aid -> attr
                ^:volatile-mutable max-aid
                ^:volatile-mutable max-gt
                ^:volatile-mutable max-tx
                write-txn]

  IWriting

  (write-txn [_] write-txn)

  IStore

  (opts [_] opts)

  (assoc-opt [_ k v]
    (let [new-opts (assoc opts k v)]
      (set! opts new-opts)
      (transact-opts lmdb new-opts)))

  (db-name [_] (:db-name opts))

  (dir [_] (lmdb/dir lmdb))

  (close [_] (.shutdownNow scheduler) (lmdb/close-kv lmdb))

  (closed? [_] (lmdb/closed-kv? lmdb))

  (last-modified [_] (lmdb/get-value lmdb c/meta :last-modified :attr :long))

  (max-gt [_] max-gt)

  (advance-max-gt [_] (set! max-gt (inc ^long max-gt)))

  (max-tx [_] max-tx)

  (advance-max-tx [_] (set! max-tx (inc ^long max-tx)))

  (max-aid [_] max-aid)

  (schema [_] schema)

  (rschema [_] rschema)

  (set-schema [this new-schema]
    (doseq [[attr new] new-schema
            :let       [old (schema attr)]
            :when      old]
      (check this attr old new))
    (set! schema (init-schema lmdb new-schema))
    (set! rschema (schema->rschema schema))
    (set! attrs (init-attrs schema))
    (set! max-aid (init-max-aid schema))
    schema)

  (attrs [_] attrs)

  (init-max-eid [_]
    (let [e    (volatile! c/e0)
          read (fn [kv]
                 (when-let [res (b/read-buffer (lmdb/k kv) :id)]
                   (vreset! e res)
                   :datalevin/terminate-visit))]
      (lmdb/visit lmdb c/eav read [:all-back])
      @e))

  (swap-attr [this attr f]
    (swap-attr this attr f nil nil))
  (swap-attr [this attr f x]
    (swap-attr this attr f x nil))
  (swap-attr [this attr f x y]
    (let [o (or (schema attr)
                (let [m {:db/aid max-aid}]
                  (set! max-aid (inc ^long max-aid))
                  m))
          p (cond
              (and x y) (f o x y)
              x         (f o x)
              :else     (f o))]
      (check this attr o p)
      (transact-schema lmdb {attr p})
      (set! schema (assoc schema attr p))
      (set! rschema (schema->rschema schema))
      (set! attrs (assoc attrs (p :db/aid) attr))
      p))

  (del-attr [this attr]
    (if (populated?
          this :ave (d/datom c/e0 attr c/v0) (d/datom c/emax attr c/vmax))
      (u/raise "Cannot delete attribute with datoms" {})
      (let [aid ((schema attr) :db/aid)]
        (lmdb/transact-kv
          lmdb [(lmdb/kv-tx :del c/schema attr :attr)
                (lmdb/kv-tx :put c/meta :last-modified
                            (System/currentTimeMillis) :attr :long)])
        (set! schema (dissoc schema attr))
        (set! rschema (schema->rschema schema))
        (set! attrs (dissoc attrs aid))
        attrs)))

  (rename-attr [_ attr new-attr]
    (let [props (schema attr)]
      (lmdb/transact-kv
        lmdb [(lmdb/kv-tx :del c/schema attr :attr)
              (lmdb/kv-tx :put c/schema new-attr props :attr)
              (lmdb/kv-tx :put c/meta :last-modified
                          (System/currentTimeMillis) :attr :long)])
      (set! schema (-> schema (dissoc attr) (assoc new-attr props)))
      (set! rschema (schema->rschema schema))
      (set! attrs (assoc attrs (props :db/aid) new-attr))
      attrs))

  (datom-count [_ index]
    (lmdb/entries lmdb (if (string? index) index (index->dbi index))))

  (load-datoms [this datoms]
    (let [;; fulltext [:a d [e aid v]], [:d d [e aid v]],
          ;; [:g d [gt v]] or [:r d gt]
          ft-ds  (FastList.)
          txs    (FastList. (* 3 (count datoms)))
          giants (UnifiedMap.)]
      (locking (lmdb/write-txn lmdb)
        (doseq [datom datoms]
          (if (d/datom-added datom)
            (insert-datom this datom txs ft-ds giants counts i-eids)
            (delete-datom this datom txs ft-ds giants counts d-eids)))
        (.add txs (lmdb/kv-tx :put c/meta :max-tx
                              (advance-max-tx this) :attr :long))
        (.add txs (lmdb/kv-tx :put c/meta :last-modified
                              (System/currentTimeMillis) :attr :long))
        (fulltext-index search-engines ft-ds)
        (lmdb/transact-kv lmdb txs))))

  (fetch [_ datom]
    (mapv #(retrieved->datom lmdb attrs %)
          (let [lk (index->k :eav schema datom false)
                hk (index->k :eav schema datom true)
                lv (datom->indexable schema datom false)
                hv (datom->indexable schema datom true)]
            (lmdb/list-range lmdb (index->dbi :eav)
                             [:closed lk hk] :id
                             [:closed lv hv] :avg))))

  (populated? [_ index low-datom high-datom]
    (let [lk (index->k index schema low-datom false)
          hk (index->k index schema high-datom true)
          lv (datom->indexable schema low-datom false)
          hv (datom->indexable schema high-datom true)]
      (lmdb/list-range-first
        lmdb (index->dbi index)
        [:closed lk hk] (index->ktype index)
        [:closed lv hv] (index->vtype index))))

  (size [store index low-datom high-datom]
    (.size store index low-datom high-datom nil))
  (size [_ index low-datom high-datom cap]
    (lmdb/list-range-count
      lmdb (index->dbi index)
      [:closed
       (index->k index schema low-datom false)
       (index->k index schema high-datom true)] (index->ktype index)
      [:closed
       (datom->indexable schema low-datom false)
       (datom->indexable schema high-datom true)] (index->vtype index)
      cap))

  (e-size [_ e] (lmdb/list-count lmdb c/eav e :id))

  (a-size [_ a]
    (let [aid ((schema a) :db/aid)]
      (or (lmdb/get-value lmdb c/meta aid :int :id)
          (let [as (lmdb/key-range-list-count
                     lmdb c/ave
                     [:closed
                      (datom->indexable schema (d/datom c/e0 a nil) false)
                      (datom->indexable schema (d/datom c/emax a nil) true)] :av)]
            (lmdb/transact-kv lmdb [[:put c/meta aid as :int :id]])
            as))))

  (e-sample [this a]
    (let [aid ((schema a) :db/aid)]
      (or (when-let [res (not-empty
                           (lmdb/get-range lmdb c/meta
                                           [:closed-open [aid 0]
                                            [aid c/init-exec-size-threshold]]
                                           :int-int :id))]
            (r/vertical-tuples
              (sequence (comp (map peek) (remove #(= -1 %))) res)))
          (let [as  (.a-size this a)
                ts  (FastList.)
                _   (sample-ave-tuples
                      this ts a as [[[:closed c/v0] [:closed c/vmax]]] nil false)
                txs (map-indexed (fn [i ^objects t]
                                   [:put c/meta [aid i] ^long (aget t 0)
                                    :int-int :id])
                                 ts)]
            (lmdb/transact-kv lmdb txs)
            ts))))

  (v-size [_ v] (lmdb/list-count lmdb c/vae v :id))

  (av-size [_ a v]
    (lmdb/list-count
      lmdb c/ave (datom->indexable schema (d/datom c/e0 a v) false) :av))

  (av-range-size ^long [this a lv hv] (.av-range-size this a lv hv nil))
  (av-range-size ^long [_ a lv hv cap]
    (lmdb/key-range-list-count
      lmdb c/ave
      [:closed
       (datom->indexable schema (d/datom c/e0 a lv) false)
       (datom->indexable schema (d/datom c/emax a hv) true)]
      :av cap))

  (cardinality [_ a]
    (lmdb/key-range-count
      lmdb c/ave
      [:closed
       (datom->indexable schema (d/datom c/e0 a nil) false)
       (datom->indexable schema (d/datom c/emax a nil) true)]
      :av))

  (head [this index low-datom high-datom]
    (retrieved->datom lmdb attrs
                      (populated? this index low-datom high-datom)))

  (tail [_ index high-datom low-datom]
    (retrieved->datom
      lmdb attrs
      (lmdb/list-range-first
        lmdb (index->dbi index)
        [:closed-back (index->k index schema high-datom true)
         (index->k index schema low-datom false)] (index->ktype index)
        [:closed-back
         (datom->indexable schema high-datom true)
         (datom->indexable schema low-datom false)]
        (index->vtype index))))

  (slice [_ index low-datom high-datom]
    (mapv #(retrieved->datom lmdb attrs %)
          (lmdb/list-range
            lmdb (index->dbi index)
            [:closed (index->k index schema low-datom false)
             (index->k index schema high-datom true)]
            (index->ktype index)
            [:closed (datom->indexable schema low-datom false)
             (datom->indexable schema high-datom true)]
            (index->vtype index))))

  (rslice [_ index high-datom low-datom]
    (mapv #(retrieved->datom lmdb attrs %)
          (lmdb/list-range
            lmdb (index->dbi index)
            [:closed-back (index->k index schema high-datom true)
             (index->k index schema low-datom false)]
            (index->ktype index)
            [:closed-back
             (datom->indexable schema high-datom true)
             (datom->indexable schema low-datom false)]
            (index->vtype index))))

  (e-datoms [_ e]
    (mapv #(avg-retrieved->datom lmdb attrs e %)
          (lmdb/get-list lmdb c/eav e :id :avg)))

  (av-datoms [_ a v]
    (mapv #(d/datom % a v)
          (lmdb/get-list
            lmdb c/ave (datom->indexable schema (d/datom c/e0 a v) false)
            :av :id)))

  (v-datoms [_ v]
    (mapv #(ae-retrieved->datom attrs v %)
          (lmdb/get-list lmdb c/vae v :id :ae)))

  (size-filter [store index pred low-datom high-datom]
    (.size-filter store index pred low-datom high-datom nil))
  (size-filter [_ index pred low-datom high-datom cap]
    (lmdb/list-range-filter-count
      lmdb (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed (index->k index schema low-datom false)
       (index->k index schema high-datom true)]
      (index->ktype index)
      [:closed (datom->indexable schema low-datom false)
       (datom->indexable schema high-datom true)]
      (index->vtype index)
      true cap))

  (head-filter [_ index pred low-datom high-datom]
    (lmdb/list-range-some
      lmdb (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed (index->k index schema low-datom false)
       (index->k index schema high-datom true)]
      (index->ktype index)
      [:closed
       (datom->indexable schema low-datom false)
       (datom->indexable schema high-datom true)]
      (index->vtype index)))

  (tail-filter [_ index pred high-datom low-datom]
    (lmdb/list-range-some
      lmdb (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed-back (index->k index schema high-datom true)
       (index->k index schema low-datom false)]
      (index->ktype index)
      [:closed-back
       (datom->indexable schema high-datom true)
       (datom->indexable schema low-datom false)]
      (index->vtype index)))

  (slice-filter [_ index pred low-datom high-datom]
    (lmdb/list-range-keep
      lmdb (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed (index->k index schema low-datom false)
       (index->k index schema high-datom true)]
      (index->ktype index)
      [:closed
       (datom->indexable schema low-datom false)
       (datom->indexable schema high-datom true)]
      (index->vtype index)))

  (rslice-filter [_ index pred high-datom low-datom]
    (lmdb/list-range-keep
      lmdb (index->dbi index)
      (datom-pred->kv-pred lmdb attrs index pred)
      [:closed-back (index->k index schema high-datom true)
       (index->k index schema low-datom false)]
      (index->ktype index)
      [:closed-back
       (datom->indexable schema high-datom true)
       (datom->indexable schema low-datom false)]
      (index->vtype index)))

  (ave-tuples [store out attr val-range]
    (.ave-tuples store out attr val-range nil false nil))
  (ave-tuples [store out attr val-range vpred]
    (.ave-tuples store out attr val-range vpred false nil))
  (ave-tuples [store out attr val-range vpred get-v?]
    (.ave-tuples store out attr val-range vpred get-v? nil))
  (ave-tuples [_ out attr val-ranges vpred get-v? indices]
    (when-let [props (schema attr)]
      (let [aid (props :db/aid)
            vt  (value-type props)]
        (cond
          (and get-v? vpred)
          (ave-tuples-scan-need-v-vpred lmdb out vpred aid vt val-ranges
                                        indices)
          vpred
          (ave-tuples-scan-no-v-vpred lmdb out vpred aid vt val-ranges indices)
          get-v?
          (ave-tuples-scan-need-v lmdb out aid vt val-ranges indices)
          :else
          (ave-tuples-scan-no-v lmdb out aid vt val-ranges indices)))))

  (sample-ave-tuples [store out attr mcount val-ranges vpred get-v?]
    (when mcount
      (let [indices (u/reservoir-sampling mcount c/init-exec-size-threshold)]
        (.ave-tuples store out attr val-ranges vpred get-v? indices)
        (u/remove-end-scan out))))

  (eav-scan-v
    [_ in out eid-idx attrs-v]
    (let [attr->aid #((schema %) :db/aid)
          aids      (mapv (comp attr->aid first) attrs-v)]
      (when (and #_(seq tuples) (seq aids) (not-any? nil? aids))
        (let [na       (count aids)
              maps     (mapv peek attrs-v)
              skips    (boolean-array (map :skip? maps))
              preds    (object-array (map :pred maps))
              fidxs    (object-array (map :fidx maps))
              manys    (mapv #(identical?
                                (-> % attrs schema :db/cardinality)
                                :db.cardinality/many) aids)
              no-many? (every? false? manys)
              manys    (boolean-array manys)
              aids     (int-array aids)
              seen     (LongObjectHashMap.)
              c        (volatile! 0)]
          (lmdb/operate-list-val-range
            lmdb c/eav
            (if no-many?
              (fn [iterable]
                (loop [tuple (.take ^LinkedBlockingQueue in)]
                  (if (identical? tuple :datalevin/end-scan)
                    (.add ^Collection out :datalevin/end-scan)
                    (let [te ^long (aget ^objects tuple eid-idx)]
                      (if-let [ts (.get seen te)]
                        (let [new (r/prod-tuples (r/single-tuples tuple) ts)]
                          (.addAll ^Collection out new)
                          (vswap! c #(+ ^long % (.size ^List new))))
                        (let [iter (lmdb/val-iterator iterable)
                              vs   (FastList. na)]
                          (loop [next? (lmdb/seek-key iter te :id)
                                 ai    0]
                            (if next?
                              (let [vb (lmdb/next-val iter)
                                    a  (b/read-buffer vb :int)]
                                (if (== ^int a ^int (aget aids ai))
                                  (let [v    (retrieved->v
                                               lmdb (b/avg->r vb))
                                        pred (aget preds ai)
                                        fidx (aget fidxs ai)]
                                    (when (and (or (nil? pred) (pred v))
                                               (or (nil? fidx)
                                                   (= v
                                                      (aget ^objects tuple
                                                            (int fidx)))))
                                      (when-not (aget skips ai) (.add vs v))
                                      (recur (lmdb/has-next-val iter)
                                             (u/long-inc ai))))
                                  (recur (lmdb/has-next-val iter) ai)))
                              (when (== ai na)
                                (if (.isEmpty vs)
                                  (do (.put seen te (r/single-tuples
                                                      (object-array [])))
                                      (.add ^Collection out tuple)
                                      (vswap! c u/long-inc))
                                  (let [vst (object-array vs)
                                        new (r/join-tuples tuple vst)]
                                    (.put seen te (r/single-tuples vst))
                                    (.add ^Collection out new)
                                    (vswap! c u/long-inc))))))))
                      (recur (.take ^LinkedBlockingQueue in))))))
              (fn [iterable]
                (loop [tuple (.take ^LinkedBlockingQueue in)]
                  (if (identical? tuple :datalevin/end-scan)
                    (.add ^Collection out :datalevin/end-scan)
                    (let [te ^long (aget ^objects tuple eid-idx)]
                      (if-let [ts (.get seen te)]
                        (let [new (r/prod-tuples (r/single-tuples tuple) ts)]
                          (.addAll ^Collection out new)
                          (vswap! c #(+ ^long % (.size ^List new))))
                        (let [iter (lmdb/val-iterator iterable)
                              vs   (FastList. na)]
                          (loop [next? (lmdb/seek-key iter te :id)
                                 ai    0
                                 dups  nil]
                            (if next?
                              (let [vb ^ByteBuffer (lmdb/next-val iter)
                                    a  (b/read-buffer (.rewind vb) :int)]
                                (if (== ^int a ^int (aget aids ai))
                                  (let [v    (retrieved->v
                                               lmdb (b/avg->r vb))
                                        pred (aget preds ai)
                                        fidx (aget fidxs ai)
                                        go?  (and (or (nil? pred) (pred v))
                                                  (or (nil? fidx)
                                                      (= v
                                                         (aget ^objects tuple
                                                               (int fidx)))))]
                                    (if (aget manys ai)
                                      (recur (lmdb/has-next-val iter)
                                             ai
                                             (when go?
                                               (if (aget skips ai)
                                                 true
                                                 ((fnil u/list-add
                                                        (FastList.))
                                                  dups v))))
                                      (when go?
                                        (when-not (aget skips ai) (.add vs v))
                                        (recur (lmdb/has-next-val iter)
                                               (u/long-inc ai) nil))))
                                  (if dups
                                    (do (when-not (true? dups) (.add vs dups))
                                        (recur true (u/long-inc ai) nil))
                                    (recur (lmdb/has-next-val iter)
                                           ai nil))))
                              (let [ai (if dups (u/long-inc ai) ai)]
                                (when (and dups (not (true? dups)))
                                  (.add vs dups))
                                (when (== ^long ai na)
                                  (let [vst (r/many-tuples vs)
                                        new (r/prod-tuples
                                              (r/single-tuples tuple) vst)]
                                    (.put seen te vst)
                                    (.addAll ^Collection out new)
                                    (vswap! c #(+ ^long %
                                                  (.size ^List new))))))))))
                      (recur (.take ^LinkedBlockingQueue in)))))))
            [:closed
             (b/indexable nil (aget aids 0) c/v0 nil c/g0)
             (b/indexable nil (aget aids (dec (alength aids)))
                          c/vmax nil c/gmax)]
            :avg)
          @c))))

  (val-eq-scan-e [_ in out v-idx attr bound]
    (when (and #_(seq tuples) attr)
      (when-let [props (schema attr)]
        (let [vt  (value-type props)
              aid (props :db/aid)
              ;; res (FastList.)
              c   (volatile! 0)
              ]
          (loop [tuple (.take ^LinkedBlockingQueue in)]
            (if (identical? tuple :datalevin/end-scan)
              (.add ^Collection out :datalevin/end-scan)
              (let [v (aget ^objects tuple v-idx)]
                (lmdb/visit-list
                  lmdb c/ave (fn [kv]
                               (let [e (b/read-buffer (lmdb/v kv) :id)]
                                 (when (= ^long e ^long bound)
                                   (.add ^Collection out (r/conj-tuple tuple e))
                                   (vswap! c u/long-inc))))
                  (b/indexable nil aid v vt nil) :av)
                (recur (.take ^LinkedBlockingQueue in)))))
          @c))))
  (val-eq-scan-e [_ in out v-idx attr]
    (when (and #_(seq tuples) attr)
      (when-let [props (schema attr)]
        (let [vt   (value-type props)
              aid  (props :db/aid)
              ;; nt   (.size ^List tuples)
              seen (UnifiedMap.)
              ;; res  (FastList.)
              c    (volatile! 0)
              ]
          (loop [tuple (.take ^LinkedBlockingQueue in)]
            (if (identical? tuple :datalevin/end-scan)
              (.add ^Collection out :datalevin/end-scan)
              (let [v (aget ^objects tuple v-idx)]
                (if-let [ts (not-empty (.get seen v))]
                  (let [new (r/prod-tuples (r/single-tuples tuple) ts)]
                    (.addAll ^Collection out new)
                    (vswap! c #(+ ^long % (.size ^List new))))
                  (let [es (LongArrayList.)]
                    (lmdb/visit-list
                      lmdb c/ave
                      (fn [kv] (.add es (b/read-buffer (lmdb/v kv) :id)))
                      (b/indexable nil aid v vt nil) :av)
                    (let [ts  (r/vertical-tuples (.toArray es))
                          new (r/prod-tuples (r/single-tuples tuple) ts)]
                      (.put seen v ts)
                      (.addAll ^Collection out new)
                      (vswap! c #(+ ^long % (.size ^List new))))))
                (recur (.take ^LinkedBlockingQueue in)))))
          @c))))

  (val-eq-filter-e [_ in out v-idx attr f-idx]
    (when (and #_(seq tuples) attr)
      (when-let [props (schema attr)]
        (let [vt  (value-type props)
              aid (props :db/aid)
              ;; res (FastList.)
              c   (volatile! 0)
              ]
          (loop [tuple (.take ^LinkedBlockingQueue in)]
            (if (identical? tuple :datalevin/end-scan)
              (.add ^Collection out :datalevin/end-scan)
              (let [old-e (aget ^objects in f-idx)
                    v     (aget ^objects in v-idx)]
                (lmdb/visit-list
                  lmdb c/ave (fn [kv]
                               (let [e (b/read-buffer (lmdb/v kv) :id)]
                                 (when (= ^long e ^long old-e)
                                   (.add ^Collection out tuple)
                                   (vswap! c u/long-inc))))
                  (b/indexable nil aid v vt nil) :av)
                (recur (.take ^LinkedBlockingQueue in)))))
          @c))))

  #_(sample-link-e [_ vs attr mcount]
      (when (and (seq vs) attr)
        (when-let [props (schema attr)]
          (let [vt   (value-type props)
                aid  (props :db/aid)
                res  (FastList.)
                idxs (if (< ^long mcount ^long c/init-exec-size-threshold)
                       (long-array (range mcount))
                       (u/reservoir-sampling mcount c/init-exec-size-threshold))
                len  (alength ^longs idxs)
                vi   (volatile! 0)
                vj   (volatile! 0)]
            (doseq [v vs]
              (when (< ^long @vi len)
                (let [es    (LongArrayList.)
                      work  (fn [kv] (.add es (b/read-buffer (lmdb/v kv) :id)))
                      visit (sampling vi vj idxs work)]
                  (lmdb/visit-list
                    lmdb c/ave visit (b/indexable nil aid v vt nil) :av)
                  (.addAll res (r/vertical-tuples (.toArray es))))))
            res)))))

(defn fulltext-index
  [search-engines ft-ds]
  (doseq [res    ft-ds
          :let   [op (peek res)
                  d (nth op 1)]
          domain (nth res 0)
          :let   [engine (search-engines domain)]]
    (case (nth op 0)
      :a (s/add-doc engine d (peek d) false)
      :d (s/remove-doc engine d)
      :g (s/add-doc engine [:g (nth d 0)] (peek d) false)
      :r (s/remove-doc engine [:g d]))))

(defn collect-samples
  [^Store store]
  (let [^IntLongHashMap counts   (.-counts store)
        ^IntObjectHashMap i-eids (.-i-eids store)
        ^IntObjectHashMap d-eids (.-d-eids store)
        ^FastList txs            (FastList.)
        lmdb                     (.-lmdb store)
        attrs                    (attrs store)
        r                        (Random.)]
    (locking (.-write-txn store)
      (doseq [^int aid (.toArray (.keySet counts))]
        (let [^LongIntHashMap is (.getIfAbsentPut i-eids aid (LongIntHashMap.))
              ^LongIntHashMap ds (.get d-eids aid)
              s-range            [:closed-open [aid 0]
                                  [aid c/init-exec-size-threshold]]
              ^long start-n      (a-size store (attrs aid))]
          (.add txs (lmdb/kv-tx :put c/meta aid
                                (+ start-n ^long (.get counts aid)) :int :id))
          (when ds
            (let [des (.toArray (.keySet ds))]
              (dotimes [i (alength des)]
                (let [e  (aget des i)
                      ec (.getIfAbsent is e 0)]
                  (.put is e (- ec ^int (.get ds e)))))))
          (when-not (.isEmpty is)
            (let [gaps (lmdb/range-keep
                         lmdb c/meta
                         (fn [kv]
                           (let [[_ i] (b/read-buffer (lmdb/k kv) :int-int)
                                 e     (b/read-buffer (lmdb/v kv) :id)]
                             (when (= e -1) i)))
                         s-range :int-int)
                  gc   (count gaps)
                  ga   (int-array gaps)
                  gi   (volatile! 0)
                  kc   (lmdb/range-count lmdb c/meta s-range :int-int)
                  k    (volatile! (if (zero? gc) kc (- ^long kc gc)))
                  cur  (volatile! 0)
                  ies  (.toArray (.keySet is))]
              (dotimes [i (alength ies)]
                (let [e (aget ies i)
                      c (.get is e)]
                  (cond
                    (< 0 c)
                    (dotimes [_ c]
                      (vswap! cur u/long-inc)
                      (cond
                        (< ^long @gi gc)
                        (do (.add txs (lmdb/kv-tx
                                        :put c/meta [aid (aget ga @gi)]
                                        e :int-int :id))
                            (vswap! gi u/long-inc)
                            (vswap! k u/long-inc))
                        (< ^long @k ^long c/init-exec-size-threshold)
                        (do (.add txs (lmdb/kv-tx
                                        :put c/meta [aid @k]
                                        e :int-int :id))
                            (vswap! k u/long-inc))
                        :else
                        (let [p (.nextInt r (+ start-n ^long @cur 1))]
                          (when (< p ^long c/init-exec-size-threshold)
                            (.add txs (lmdb/kv-tx
                                        :put c/meta [aid p] e :int-int :id))))))
                    (= c 0) :do-nothing
                    :else
                    (dotimes [_ (- c)]
                      (vswap! cur u/long-dec)
                      (when-let [g (lmdb/range-some
                                     lmdb c/meta
                                     (fn [kv]
                                       (when (= e (b/read-buffer (lmdb/v kv) :id))
                                         (let [[_ i] (b/read-buffer
                                                       (lmdb/k kv) :int-int)]
                                           i)))
                                     s-range :int-int :id)]
                        (.add txs (lmdb/kv-tx :put c/meta [aid g]
                                              -1 :int-int :id)))))))))))
      (lmdb/transact-kv lmdb txs)
      (.clear counts)
      (.clear i-eids)
      (.clear d-eids))))

(defn- check-cardinality
  [^Store store attr old new]
  (when (and (identical? old :db.cardinality/many)
             (identical? new :db.cardinality/one))
    (let [low-datom  (d/datom c/e0 attr c/v0)
          high-datom (d/datom c/emax attr c/vmax)]
      (when (populated? store :ave low-datom high-datom)
        (u/raise "Cardinality change is not allowed when data exist"
                 {:attribute attr})))))

(defn- check-value-type
  [^Store store attr old new]
  (when (not= old new)
    (when ((schema store) attr)
      (let [low-datom  (d/datom c/e0 attr c/v0)
            high-datom (d/datom c/emax attr c/vmax)]
        (when (populated? store :ave low-datom high-datom)
          (u/raise "Value type change is not allowed when data exist"
                   {:attribute attr}))))))

(defn- violate-unique?
  [^Store store low-datom high-datom]
  (let [prev-v   (volatile! nil)
        violate? (volatile! false)
        lmdb     (.-lmdb store)
        schema   (schema store)
        visitor  (fn [kv]
                   (let [av ^Retrieved (b/read-buffer (lmdb/k kv) :av)
                         eg ^Retrieved (b/read-buffer (lmdb/v kv) :eg)
                         r  (Retrieved. nil nil (.-v av) (.-g eg))
                         v  (retrieved->v lmdb r)]
                     (if (= @prev-v v)
                       (do (vreset! violate? true)
                           :datalevin/terminate-visit)
                       (vreset! prev-v v))))]
    (lmdb/visit-list-range
      lmdb c/ave visitor
      [:closed (index->k :ave schema low-datom false)
       (index->k :ave schema high-datom true)] :av
      [:closed (datom->indexable schema low-datom false)
       (datom->indexable schema high-datom true)] :eg)
    @violate?))

(defn- check-unique
  [store attr old new]
  (when (and (not old) new)
    (let [low-datom  (d/datom c/e0 attr c/v0)
          high-datom (d/datom c/emax attr c/vmax)]
      (when (populated? store :ave low-datom high-datom)
        (when (violate-unique? store low-datom high-datom)
          (u/raise "Attribute uniqueness change is inconsistent with data"
                   {:attribute attr}))))))

(defn- check [store attr old new]
  (doseq [[k v] new
          :let  [v' (old k)]]
    (case k
      :db/cardinality (check-cardinality store attr v' v)
      :db/valueType   (check-value-type store attr v' v)
      :db/unique      (check-unique store attr v' v)
      :pass-through)))

(defn- collect-fulltext
  [^FastList ft-ds attr props v op]
  (when-not (str/blank? v)
    (.add ft-ds
          [(cond-> (or (props :db.fulltext/domains) [c/default-domain])
             (props :db.fulltext/autoDomain) (conj (u/keyword->string attr)))
           op])))

(defn- insert-datom
  [^Store store ^Datom d ^FastList txs ^FastList ft-ds ^UnifiedMap giants
   ^IntLongHashMap counts ^IntObjectHashMap eids]
  (let [attr   (.-a d)
        schema (schema store)
        opts   (opts store)
        _      (or (not (opts :closed-schema?))
                   (schema attr)
                   (u/raise "Attribute is not defined in schema when
`:closed-schema?` is true: " attr {:attr attr :value (.-v d)}))
        props  (or (schema attr)
                   (swap-attr store attr identity))
        vt     (value-type props)
        e      (.-e d)
        v      (.-v d)
        aid    (props :db/aid)
        max-gt (max-gt store)
        _      (or (not (opts :validate-data?))
                   (b/valid-data? v vt)
                   (u/raise "Invalid data, expecting" vt " got " v {:input v}))
        i      (b/indexable e aid v vt max-gt)
        giant? (b/giant? i)
        old-c  (.getIfAbsent counts aid 0)
        old-es ^LongIntHashMap (.getIfAbsentPut eids ^int aid (LongIntHashMap.))
        old-ec (.getIfAbsent old-es e 0)]
    (.put old-es e (unchecked-inc old-ec))
    (.put counts aid (unchecked-inc old-c))
    (.add txs (lmdb/kv-tx :put c/ave i i :av :eg))
    (.add txs (lmdb/kv-tx :put c/eav e i :id :avg))
    (when (identical? :db.type/ref vt)
      (.add txs (lmdb/kv-tx :put c/vae v i :id :ae)))
    (when giant?
      (advance-max-gt store)
      (let [gd [e attr v]]
        (.put giants gd max-gt)
        (.add txs (lmdb/kv-tx :put c/giants max-gt (apply d/datom gd)
                              :id :data [:append]))))
    (when (props :db/fulltext)
      (let [v (str v)]
        (collect-fulltext ft-ds attr props v
                          (if giant? [:g [max-gt v]] [:a [e aid v]]))))))

(defn- delete-datom
  [^Store store ^Datom d ^FastList txs ^FastList ft-ds ^UnifiedMap giants
   ^IntLongHashMap counts ^IntObjectHashMap eids]
  (let [e      (.-e d)
        attr   (.-a d)
        v      (.-v d)
        d-eav  [e attr v]
        props  ((schema store) attr)
        vt     (value-type props)
        aid    (props :db/aid)
        i      ^Indexable (b/indexable e aid v vt c/g0)
        gt-cur (.get giants d-eav)
        gt     (when (b/giant? i)
                 (or gt-cur
                     (let [[_ ^Retrieved r]
                           (nth
                             (lmdb/list-range
                               (.-lmdb store) c/eav [:closed e e] :id
                               [:closed
                                i
                                (Indexable. e aid v (.-f i) (.-b i) c/gmax)]
                               :avg)
                             0)]
                       (.-g r))))
        old-c  (.getIfAbsent counts aid 0)
        old-es ^LongIntHashMap (.getIfAbsentPut eids ^int aid (LongIntHashMap.))
        old-ec (.getIfAbsent old-es e 0)]
    (.put old-es e (unchecked-inc old-ec))
    (.put counts aid (unchecked-dec old-c))
    (when (props :db/fulltext)
      (let [v (str v)]
        (collect-fulltext ft-ds attr props v (if gt [:r gt] [:d [e aid v]]))))
    (let [ii (Indexable. e aid v (.-f i) (.-b i) (or gt c/normal))]
      (.add txs (lmdb/kv-tx :del-list c/ave ii [ii] :av :eg))
      (.add txs (lmdb/kv-tx :del-list c/eav e [ii] :id :avg))
      (when gt
        (when gt-cur (.remove giants d-eav))
        (.add txs (lmdb/kv-tx :del c/giants gt :id)))
      (when (identical? :db.type/ref vt)
        (.add txs (lmdb/kv-tx :del-list c/vae v [ii] :id :ae))))))

(defn- transact-opts
  [lmdb opts]
  (lmdb/transact-kv
    lmdb (conj (for [[k v] opts]
                 (lmdb/kv-tx :put c/opts k v :attr :data))
               (lmdb/kv-tx :put c/meta :last-modified
                           (System/currentTimeMillis) :attr :long))))

(defn- load-opts
  [lmdb]
  (into {} (lmdb/get-range lmdb c/opts [:all] :attr :data)))

(defn- open-dbis
  [lmdb]
  (lmdb/open-list-dbi
    lmdb c/eav {:key-size c/+id-bytes+ :val-size c/+max-key-size+})
  (lmdb/open-list-dbi
    lmdb c/ave {:key-size c/+max-key-size+ :val-size (* 2 c/+id-bytes+)})
  (lmdb/open-list-dbi
    lmdb c/vae {:key-size c/+id-bytes+
                :val-size (+ c/+short-id-bytes+ c/+id-bytes+)})
  (lmdb/open-dbi lmdb c/giants {:key-size c/+id-bytes+})
  (lmdb/open-dbi lmdb c/schema {:key-size c/+max-key-size+})
  (lmdb/open-dbi lmdb c/meta {:key-size c/+max-key-size+})
  (lmdb/open-dbi lmdb c/opts {:key-size c/+max-key-size+}))

(defn- default-domain
  [dms search-opts search-domains]
  (let [new-opts (assoc (or (get search-domains c/default-domain)
                            search-opts
                            {})
                        :domain c/default-domain)]
    (assoc dms c/default-domain
           (if-let [opts (dms c/default-domain)]
             (merge opts new-opts)
             new-opts))))

(defn- listed-domains
  [dms domains search-domains]
  (reduce (fn [m domain]
            (let [new-opts (assoc (get search-domains domain {})
                                  :domain domain)]
              (assoc m domain
                     (if-let [opts (m domain)]
                       (merge opts new-opts)
                       new-opts))))
          dms
          domains))

(defn- init-domains
  [search-domains0 schema search-opts search-domains]
  (reduce-kv
    (fn [dms attr
        {:keys [db/fulltext db.fulltext/domains db.fulltext/autoDomain]}]
      (if fulltext
        (cond-> (if (seq domains)
                  (listed-domains dms domains search-domains)
                  (default-domain dms search-opts search-domains))
          autoDomain (#(let [domain (u/keyword->string attr)]
                         (assoc
                           % domain
                           (let [new-opts (assoc (get search-domains domain {})
                                                 :domain domain)]
                             (if-let [opts (% domain)]
                               (merge opts new-opts)
                               new-opts))))))
        dms))
    (or search-domains0 {})
    schema))

(defn- init-engines
  [lmdb domains]
  (reduce-kv
    (fn [m domain opts]
      (assoc m domain (s/new-search-engine lmdb opts)))
    {} domains))

(defn open
  "Open and return the storage."
  ([]
   (open nil nil))
  ([dir]
   (open dir nil))
  ([dir schema]
   (open dir schema nil))
  ([dir schema {:keys [kv-opts search-opts search-domains] :as opts}]
   (let [dir  (or dir (u/tmp-dir (str "datalevin-" (UUID/randomUUID))))
         lmdb (lmdb/open-kv dir kv-opts)]
     (open-dbis lmdb)
     (let [opts0     (load-opts lmdb)
           opts1     (if (empty opts0)
                       {:validate-data?    false
                        :auto-entity-time? false
                        :closed-schema?    false
                        :db-name           (str (UUID/randomUUID))
                        :cache-limit       256}
                       opts0)
           opts2     (merge opts1 opts)
           schema    (init-schema lmdb schema)
           domains   (init-domains (:search-domains opts2)
                                   schema search-opts search-domains)
           scheduler (Executors/newScheduledThreadPool 1)]
       (transact-opts lmdb opts2)
       (let [store (->Store lmdb
                            (init-engines lmdb domains)
                            (IntLongHashMap.)
                            (IntObjectHashMap.)
                            (IntObjectHashMap.)
                            scheduler
                            (load-opts lmdb)
                            schema
                            (schema->rschema schema)
                            (init-attrs schema)
                            (init-max-aid schema)
                            (init-max-gt lmdb)
                            (init-max-tx lmdb)
                            (volatile! :storage-mutex))]
         (.schedule scheduler ^Callable #(collect-samples store)
                    ^long c/sample-processing-interval TimeUnit/SECONDS)
         store)))))

(defn- transfer-engines
  [engines lmdb]
  (zipmap (keys engines) (map #(s/transfer % lmdb) (vals engines))))

(defn transfer
  "transfer state of an existing store to a new store that has a different
  LMDB instance"
  [^Store old lmdb]
  (->Store lmdb
           (transfer-engines (.-search-engines old) lmdb)
           (.-counts old)
           (.-i-eids old)
           (.-d-eids old)
           (.-scheduler old)
           (opts old)
           (schema old)
           (rschema old)
           (attrs old)
           (max-aid old)
           (max-gt old)
           (max-tx old)
           (.-write-txn old)))
