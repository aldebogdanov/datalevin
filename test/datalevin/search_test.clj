(ns datalevin.search-test
  (:require [datalevin.search :as sut]
            [datalevin.lmdb :as l]
            [datalevin.bits :as b]
            [datalevin.sparselist :as sl]
            [datalevin.constants :as c]
            [datalevin.util :as u]
            [clojure.test :refer [is deftest testing]])
  (:import [java.util UUID Map ArrayList]
           [org.eclipse.collections.impl.map.mutable.primitive IntShortHashMap
            IntObjectHashMap ObjectIntHashMap]
           [org.roaringbitmap RoaringBitmap]
           [datalevin.sm SymSpell Bigram]
           [datalevin.sparselist SparseIntArrayList]
           [datalevin.search SearchEngine]))

(deftest english-analyzer-test
  (let [s1 "This is a Datalevin-Analyzers test"
        s2 "This is a Datalevin-Analyzers test. "]
    (is (= (sut/en-analyzer s1)
           (sut/en-analyzer s2)
           [["datalevin-analyzers" 3 10] ["test" 4 30]]))
    (is (= (subs s1 10 (+ 10 (.length "datalevin-analyzers")))
           "Datalevin-Analyzers" ))))

(defn- add-docs
  [engine]
  (sut/add-doc engine :doc1
               "The quick red fox jumped over the lazy red dogs.")
  (sut/add-doc engine :doc2
               "Mary had a little lamb whose fleece was red as fire.")
  (sut/add-doc engine :doc3
               "Moby Dick is a story of a whale and a man obsessed.")
  (sut/add-doc engine :doc4
               "The robber wore a red fleece jacket and a baseball cap.")
  (sut/add-doc engine :doc5
               "The English Springer Spaniel is the best of all red dogs I know."))

(deftest index-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "index-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-engine lmdb)]
    (add-docs engine)

    (let [[tid mw ^SparseIntArrayList sl]
          (l/get-value lmdb c/terms "red" :string :term-info true)]
      (is (= (l/range-count lmdb c/terms [:all] :string) 32))
      (is (= (l/get-value lmdb c/terms "red" :string :int) tid))

      (is (sl/contains-index? sl 1))
      (is (sl/contains-index? sl 5))
      (is (= (sl/size sl) 4))
      (is (= (seq (.-indices sl)) [1 2 4 5]))

      (is (= (l/list-count lmdb c/positions [tid 1] :int-int)
             (sl/get sl 1)
             2))
      (is (= (l/list-count lmdb c/positions [tid 2] :int-int)
             (sl/get sl 2)
             1))

      (is (= (l/list-count lmdb c/positions [tid 3] :int-int)
             0))
      (is (nil? (sl/get sl 3)))

      (is (= (l/list-count lmdb c/positions [tid 4] :int-int)
             (sl/get sl 4)
             1))
      (is (= (l/list-count lmdb c/positions [tid 5] :int-int)
             (sl/get sl 5)
             1))

      (is (= (l/get-list lmdb c/positions [tid 5] :int-int :int-int)
             [[9 48]]))

      (is (= (l/get-value lmdb c/docs 1 :int :doc-info true) [7 :doc1]))
      (is (= (l/get-value lmdb c/docs 2 :int :doc-info true) [8 :doc2]))
      (is (= (l/get-value lmdb c/docs 3 :int :doc-info true) [6 :doc3]))
      (is (= (l/get-value lmdb c/docs 4 :int :doc-info true) [7 :doc4]))
      (is (= (l/get-value lmdb c/docs 5 :int :doc-info true) [9 :doc5]))
      (is (= (l/range-count lmdb c/docs [:all]) 5))

      (sut/remove-doc engine :doc1)
      (let [[tid mw ^SparseIntArrayList sl]
            (l/get-value lmdb c/terms "red" :string :term-info true)]
        (is (= (l/range-count lmdb c/docs [:all]) 4))
        (is (not (sl/contains-index? sl 1)))
        (is (= (sl/size sl) 3))
        (is (= (l/list-count lmdb c/positions [tid 1] :int-id) 0))
        (is (nil? (l/get-list lmdb c/positions [tid 1] :int-id :int-int)))))

    (l/close-kv lmdb)))

(deftest search-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-engine lmdb)]
    (add-docs engine)

    (is (= (sut/search engine "cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "notaword cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "fleece" {:display :offsets})
           [[:doc4 [["fleece" [22]]]] [:doc2 [["fleece" [29]]]]]))
    (is (= (sut/search engine "red fox" {:display :offsets})
           [[:doc1 [["fox" [14]] ["red" [10 39]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]
            [:doc5 [["red" [48]]]]]))
    (is (= (sut/search engine "red dogs" {:display :offsets})
           [[:doc1 [["dogs" [43]] ["red" [10 39]]]]
            [:doc5 [["dogs" [52]] ["red" [48]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]]))
    (is (empty? (sut/search engine "solar")))
    (is (empty? (sut/search engine "solar wind")))
    (is (= (sut/search engine "solar cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (l/close-kv lmdb)))