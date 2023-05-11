(ns ^:no-doc datalevin.scan
  "Index scan routines common to all bindings"
  (:require
   [datalevin.bits :as b]
   [datalevin.spill :as sp]
   [datalevin.util :as u :refer [raise]]
   [datalevin.lmdb :as l])
  (:import
   [datalevin.spill SpillableVector]
   [clojure.lang Seqable IReduceInit]
   [java.nio ByteBuffer]
   [java.util Iterator]
   [java.lang AutoCloseable Iterable]))

(defmacro scan
  ([call error]
   `(scan ~call ~error false))
  ([call error keep-rtx?]
   `(let [~'dbi (l/get-dbi ~'lmdb ~'dbi-name false)
          ~'rtx (if (l/writing? ~'lmdb)
                  @(l/write-txn ~'lmdb)
                  (l/get-rtx ~'lmdb))
          ~'cur (l/get-cursor ~'dbi ~'rtx)]
      (try
        ~call
        (catch Exception ~'e
          ~error)
        (finally
          (if (l/read-only? ~'rtx)
            (l/return-cursor ~'dbi ~'cur)
            (l/close-cursor ~'dbi ~'cur))
          (when-not (or (l/writing? ~'lmdb) ~keep-rtx?)
            (l/return-rtx ~'lmdb ~'rtx)))))))

(defn get-value
  [lmdb dbi-name k k-type v-type ignore-key?]
  (scan
    (do
      (l/put-key rtx k k-type)
      (when-let [^ByteBuffer bb (l/get-kv dbi rtx)]
        (if ignore-key?
          (b/read-buffer bb v-type)
          [(b/expected-return k k-type) (b/read-buffer bb v-type)])))
    (raise "Fail to get-value: " e
           {:dbi dbi-name :k k :k-type k-type :v-type v-type})))

(defn get-first
  [lmdb dbi-name k-range k-type v-type ignore-key?]
  (scan
    (let [iterable       (l/iterate-kv dbi rtx cur k-range k-type v-type)
          ^Iterator iter (.iterator ^Iterable iterable)]
      (when (.hasNext iter)
        (let [kv (.next iter)
              v  (when (not= v-type :ignore)
                   (b/read-buffer (l/v kv) v-type))]
          (if ignore-key?
            (if v v true)
            [(b/read-buffer (l/k kv) k-type) v]))))
    (raise "Fail to get-first: " e
           {:dbi    dbi-name :k-range k-range
            :k-type k-type   :v-type  v-type})))

(defn get-range
  [lmdb dbi-name k-range k-type v-type ignore-key?]
  (scan
    (do
      (assert (not (and (= v-type :ignore) ignore-key?))
              "Cannot ignore both key and value")
      (let [iterable (l/iterate-kv dbi rtx cur k-range k-type v-type)
            ^SpillableVector holder
            (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))]
        (loop [^Iterator iter (.iterator ^Iterable iterable)]
          (if (.hasNext iter)
            (let [kv (.next iter)
                  v  (when (not= v-type :ignore)
                       (b/read-buffer (l/v kv) v-type))]
              (.cons holder (if ignore-key?
                              v
                              [(b/read-buffer (l/k kv) k-type) v]))
              (recur iter))
            holder))))
    (raise "Fail to get-range: " e
           {:dbi    dbi-name :k-range k-range
            :k-type k-type   :v-type  v-type})))

(defn key-range
  [lmdb dbi-name k-range k-type]
  (scan
    (let [^Iterable iterable (l/iterate-key dbi rtx cur k-range k-type)
          ^SpillableVector holder
          (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))]
      (loop [^Iterator iter (.iterator iterable)]
        (if (.hasNext iter)
          (let [kv (.next iter)]
            (.cons holder (b/read-buffer (l/k kv) k-type))
            (recur iter))
          holder)))
    (raise "Fail to get key-range: " e
           {:dbi dbi-name :k-range k-range :k-type k-type })))

(defn- range-seq*
  [lmdb dbi rtx cur k-range k-type v-type ignore-key?
   {:keys [batch-size] :or {batch-size 100}}]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (let [^Iterable itb  (l/iterate-kv dbi rtx cur k-range k-type v-type)
        ^Iterator iter (.iterator itb)
        item           (fn [kv]
                         (let [v (when (not= v-type :ignore)
                                   (b/read-buffer (l/v kv) v-type))]
                           (if ignore-key?
                             (if v v true)
                             [(b/read-buffer (l/k kv) k-type) v])))
        fetch          (fn [^long k]
                         (let [holder (transient [])]
                           (loop [i 0]
                             (if (.hasNext iter)
                               (let [kv (.next iter)]
                                 (conj! holder (item kv))
                                 (if (< i k)
                                   (recur (inc i))
                                   {:batch  (persistent! holder)
                                    :next-k k}))
                               {:batch  (persistent! holder)
                                :next-k nil}))))]
    (reify
      Seqable
      (seq [_]
        (u/lazy-concat
          ((fn next [ret]
             (when (clojure.core/seq (:batch ret))
               (cons (:batch ret)
                     (when-some [k (:next-k ret)]
                       (lazy-seq (next (fetch k)))))))
           (fetch batch-size))))

      IReduceInit
      (reduce [_ rf init]
        (loop [acc init
               ret (fetch batch-size)]
          (if (clojure.core/seq (:batch ret))
            (let [acc (rf acc (:batch ret))]
              (if (reduced? acc)
                @acc
                (if-some [k (:next-k ret)]
                  (recur acc (fetch k))
                  acc)))
            acc)))

      AutoCloseable
      (close [_] (l/return-rtx lmdb rtx))

      Object
      (toString [this] (str (apply list this))))))

(defn range-seq
  ([lmdb dbi-name k-range k-type v-type ignore-key?]
   (range-seq lmdb dbi-name k-range k-type v-type ignore-key? nil))
  ([lmdb dbi-name k-range k-type v-type ignore-key? opts]
   (scan
     (range-seq* lmdb dbi rtx cur k-range k-type v-type ignore-key? opts)
     (raise "Fail in range-seq: " e
            {:dbi    dbi-name :k-range k-range
             :k-type k-type   :v-type  v-type})
     true)))

(defn range-count*
  [iterable]
  (loop [^Iterator iter (.iterator ^Iterable iterable)
         c              0]
    (if (.hasNext iter)
      (do (.next iter) (recur iter (inc c)))
      c)))

(defn range-count
  [lmdb dbi-name k-range k-type]
  (scan
    (let [iterable (l/iterate-kv dbi rtx cur k-range k-type nil)]
      (range-count* iterable))
    (raise "Fail to range-count: " e
           {:dbi dbi-name :k-range k-range :k-type k-type})))

(defn get-some
  [lmdb dbi-name pred k-range k-type v-type ignore-key?]
  (scan
    (do
      (assert (not (and (= v-type :ignore) ignore-key?))
              "Cannot ignore both key and value")
      (let [iterable (l/iterate-kv dbi rtx cur k-range k-type v-type)]
        (loop [^Iterator iter (.iterator ^Iterable iterable)]
          (when (.hasNext iter)
            (let [kv (.next iter)]
              (if (pred kv)
                (let [v (when (not= v-type :ignore)
                          (b/read-buffer (.rewind ^ByteBuffer (l/v kv))
                                         v-type))]
                  (if ignore-key?
                    v
                    [(b/read-buffer (.rewind ^ByteBuffer (l/k kv)) k-type)
                     v]))
                (recur iter)))))))
    (raise "Fail to get-some: " e
           {:dbi    dbi-name :k-range k-range
            :k-type k-type   :v-type  v-type})))

(defn range-filter
  [lmdb dbi-name pred k-range k-type v-type ignore-key?]
  (scan
    (do
      (assert (not (and (= v-type :ignore) ignore-key?))
              "Cannot ignore both key and value")
      (let [^SpillableVector holder
            (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))]
        (let [iterable (l/iterate-kv dbi rtx cur k-range k-type v-type)]
          (loop [^Iterator iter (.iterator ^Iterable iterable)]
            (if (.hasNext iter)
              (let [kv (.next iter)]
                (if (pred kv)
                  (let [v (when (not= v-type :ignore)
                            (b/read-buffer
                              (.rewind ^ByteBuffer (l/v kv)) v-type))]
                    (.cons holder (if ignore-key?
                                    v
                                    [(b/read-buffer
                                       (.rewind ^ByteBuffer (l/k kv))
                                       k-type) v]))
                    (recur iter))
                  (recur iter)))
              holder)))))
    (raise "Fail to range-filter: " e
           {:dbi    dbi-name :k-range k-range
            :k-type k-type   :v-type  v-type})))

(defn- filter-count*
  [iterable pred]
  (loop [^Iterator iter (.iterator ^Iterable iterable)
         c              0]
    (if (.hasNext iter)
      (let [kv (.next iter)]
        (if (pred kv)
          (recur iter (inc c))
          (recur iter c)))
      c)))

(defn range-filter-count
  [lmdb dbi-name pred k-range k-type]
  (scan
    (let [iterable (l/iterate-kv dbi rtx cur k-range k-type nil)]
      (filter-count* iterable pred))
    (raise "Fail to range-filter-count: " e
           {:dbi dbi-name :k-range k-range :k-type k-type})))

(defn- visit*
  [iterable visitor]
  (loop [^Iterator iter (.iterator ^Iterable iterable)]
    (when (.hasNext iter)
      (when-not (= (visitor (.next iter)) :datalevin/terminate-visit)
        (recur iter)))))

(defn visit
  [lmdb dbi-name visitor k-range k-type]
  (scan
    (let [iterable (l/iterate-kv dbi rtx cur k-range k-type nil)]
      (visit* iterable visitor))
    (raise "Fail to visit: " e
           {:dbi dbi-name :k-range k-range :k-type k-type})))

(defn list-range
  [lmdb dbi-name k-range k-type v-range v-type]
  (scan
    (let [^SpillableVector holder
          (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))
          iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (loop [^Iterator iter (.iterator ^Iterable iterable)]
        (if (.hasNext iter)
          (let [kv (.next iter)]
            (.cons holder [(b/read-buffer (l/k kv) k-type)
                           (b/read-buffer (l/v kv) v-type)])
            (recur iter))
          holder)))
    (raise "Fail to get list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )

(defn list-range-first
  [lmdb dbi-name k-range k-type v-range v-type]
  (scan
    (let [iterable       (l/iterate-list dbi rtx cur k-range k-type
                                         v-range v-type)
          ^Iterator iter (.iterator ^Iterable iterable)]
      (when (.hasNext iter)
        (let [kv (.next iter)]
          [(b/read-buffer (l/k kv) k-type)
           (b/read-buffer (l/v kv) v-type)])))
    (raise "Fail to get list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )

(defn list-range-count
  [lmdb dbi-name k-range k-type v-range v-type]
  (scan
    (let [iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (range-count* iterable))
    (raise "Fail to get list range count: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )

(defn list-range-filter
  [lmdb dbi-name pred k-range k-type v-range v-type]
  (scan
    (let [^SpillableVector holder
          (sp/new-spillable-vector nil (:spill-opts (l/opts lmdb)))
          iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (loop [^Iterator iter (.iterator ^Iterable iterable)]
        (if (.hasNext iter)
          (let [kv (.next iter)]
            (if (pred kv)
              (let [k (.rewind ^ByteBuffer (l/k kv))
                    v (.rewind ^ByteBuffer (l/v kv))]
                (.cons holder [(b/read-buffer k k-type)
                               (b/read-buffer v v-type)])
                (recur iter))
              (recur iter)))
          holder)))
    (raise "Fail to filter list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )

(defn list-range-some
  [lmdb dbi-name pred k-range k-type v-range v-type]
  (scan
    (let [iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (loop [^Iterator iter (.iterator ^Iterable iterable)]
        (when (.hasNext iter)
          (let [kv (.next iter)]
            (if (pred kv)
              (let [k (.rewind ^ByteBuffer (l/k kv))
                    v (.rewind ^ByteBuffer (l/v kv))]
                [(b/read-buffer k k-type) (b/read-buffer v v-type)])
              (recur iter))))))
    (raise "Fail to find some in list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )

(defn list-range-filter-count
  [lmdb dbi-name pred k-range k-type v-range v-type]
  (scan
    (let [iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (filter-count* iterable pred))
    (raise "Fail to count filtered list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})))

(defn visit-list-range
  [lmdb dbi-name visitor k-range k-type v-range v-type]
  (scan
    (let [iterable (l/iterate-list dbi rtx cur k-range k-type
                                   v-range v-type)]
      (visit* iterable visitor))
    (raise "Fail to visit list range: " e
           {:dbi dbi-name :key-range k-range :val-range v-range})) )