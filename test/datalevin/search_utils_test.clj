(ns datalevin.search-utils-test
  (:require
   [datalevin.search-utils :as sut]
   [datalevin.analyzer :as a]
   [datalevin.util :as u]
   [datalevin.core :as d]
   [clojure.test :refer [is deftest testing]])
  (:import
   [java.util UUID ]))

(deftest custom-analyzer-test
  (let [s1               "This is a Datalevin-Analyzers test"
        s2               "This is a Datalevin-Analyzers test. "
        cust-analyzer-en (sut/create-analyzer
                           {:tokenizer
                            (sut/create-regexp-tokenizer
                              #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
                            :token-filters [sut/lower-case-token-filter
                                            sut/en-stop-words-token-filter]})]
    (is (fn? cust-analyzer-en))
    (is (= (a/en-analyzer s1)
           (cust-analyzer-en s1)
           (cust-analyzer-en s2)
           [["datalevin-analyzers" 3 10] ["test" 4 30]]))))

(deftest autocomplete-analyzer-test
  (let [s1               "clock"
        s2               "cloud"
        cust-analyzer-en (sut/create-analyzer
                           {:tokenizer
                            (sut/create-regexp-tokenizer
                              #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
                            :token-filters [sut/lower-case-token-filter
                                            sut/prefix-token-filter]})]
    (is (= (into [] (cust-analyzer-en s1))
           [["c" 0 0] ["cl" 0 0] ["clo" 0 0] ["cloc" 0 0] ["clock" 0 0]]))
    (is (= (into [] (cust-analyzer-en s2))
           [["c" 0 0] ["cl" 0 0] ["clo" 0 0] ["clou" 0 0] ["cloud" 0 0]]))))

(deftest ngram-filter-test
  (let [dir      (u/tmp-dir (str "search-utils-test-" (UUID/randomUUID)))
        analyzer (sut/create-analyzer
                   {:tokenizer
                    (sut/create-regexp-tokenizer
                      #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
                    :token-filters [(sut/create-ngram-token-filter 2)]})
        db       (-> (d/empty-db
                       dir
                       {:text {:db/valueType :db.type/string
                               :db/fulltext  true}}
                       {:search-opts
                        {:query-analyzer analyzer
                         :analyzer       analyzer}})
                     (d/db-with
                       [{:db/id 1,
                         :text  "The quick red fox jumped over the lazy red dogs."}
                        {:db/id 2,
                         :text  "Mary had a little lamb whose fleece was red as fire."}
                        {:db/id 3,
                         :text  "Moby Dick is a story of a whale and a man obsessed."}]))]
    (is (= 1 (count (d/fulltext-datoms db "Mo"))))
    (is (= 3 (d/q '[:find ?e .
                    :in $ ?q
                    :where
                    [(fulltext $ ?q) [[?e _ _]]]]
                  db "Mo")))
    (d/close-db db)
    (u/delete-files dir)))

(deftest stemming-test
  (let [dir      (u/tmp-dir (str "stemming-test-" (UUID/randomUUID)))
        analyzer (sut/create-analyzer
                   {:token-filters [(sut/create-stemming-token-filter
                                      "english")]})
        db       (-> (d/empty-db
                       dir
                       {:text {:db/valueType :db.type/string
                               :db/fulltext  true}}
                       {:search-opts
                        {:query-analyzer analyzer
                         :analyzer       analyzer}})
                     (d/db-with
                       [{:db/id 1,
                         :text  "The quick red fox jumped over the lazy red dogs."}
                        {:db/id 2,
                         :text  "Mary had a little lamb whose fleece was red as fire."}
                        {:db/id 3,
                         :text  "Moby Dick is a story of a whale and a man obsessed."}]))]
    (is (= 1 (count (d/fulltext-datoms db "jump dog"))))
    (is (= 3 (d/q '[:find ?e .
                    :in $ ?q
                    :where
                    [(fulltext $ ?q) [[?e _ _]]]]
                  db "obsess")))
    (d/close-db db)
    (u/delete-files dir)))

(deftest stop-words-test
  (let [dir    (u/tmp-dir (str "stop-words-" (UUID/randomUUID)))
        lmdb   (d/open-kv dir)
        engine (d/new-search-engine
                 lmdb
                 {:analyzer
                  (sut/create-analyzer
                    {:tokenizer
                     (sut/create-regexp-tokenizer
                       #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
                     :token-filters [sut/lower-case-token-filter
                                     sut/unaccent-token-filter
                                     (sut/create-stop-words-token-filter
                                       #{"over" "red" "the" "lazy"})]})
                  :index-position? true})]
    (d/open-dbi lmdb "raw")
    (d/transact-kv
      lmdb
      [[:put "raw" 1 "The quick red fox jumped over the lazy red dogs."]
       [:put "raw" 2 "Mary had a little lamb whose fleece was red as fire."]
       [:put "raw" 3 "Moby Dick is a story of some dogs' and a whale."]])
    (doseq [i [1 2 3]]
      (d/add-doc engine i (d/get-value lmdb "raw" i)))
    (is (= [[1 [["dogs" [43]]]] [3 [["dogs" [29]]]]]
           (d/search engine "dogs" {:display :offsets})))
    (d/close-kv lmdb)
    (u/delete-files dir)))
