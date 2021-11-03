(ns datalevin.search-test
  (:require [datalevin.search :as sut]
            [datalevin.lmdb :as l]
            [datalevin.constants :as c]
            [datalevin.util :as u]
            [clojure.test :refer [is deftest testing]])
  (:import [java.util UUID Map ArrayList]
           [org.eclipse.collections.impl.map.mutable.primitive IntShortHashMap
            IntObjectHashMap ObjectIntHashMap]
           [datalevin.sm SymSpell Bigram]
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
               "The quick red fox jumped over the lazy red dogs." true)
  (sut/add-doc engine :doc2
               "Mary had a little lamb whose fleece was red as fire." true)
  (sut/add-doc engine :doc3
               "Moby Dick is a story of a whale and a man obsessed." true)
  (sut/add-doc engine :doc4
               "The robber wore a red fleece jacket and a baseball cap." true)
  (sut/add-doc engine :doc5
               "The English Springer Spaniel is the best of all red dogs I know."
               true))

(deftest index-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "index-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-engine lmdb)]
    (add-docs engine)

    (let [unigrams ^ObjectIntHashMap (.-unigrams engine)
          tid      (.get unigrams "red")]
      (is (= (.size unigrams)
             (l/range-count lmdb c/terms [:all] :int)
             32))
      (is (= (l/get-value lmdb c/terms tid :int :string) "red"))
      (is (l/in-list? lmdb c/term-docs tid 1 :int :int))
      (is (l/in-list? lmdb c/term-docs tid 5 :int :int))
      (is (= (l/list-count lmdb c/term-docs tid :int) 4))
      (is (= (l/get-list lmdb c/term-docs tid :int :int) [1 2 4 5]))
      (is (= (l/list-count lmdb c/positions [1 tid] :int-int) 2))
      (is (= (l/list-count lmdb c/positions [5 tid] :int-int) 1))
      (is (= (l/get-list lmdb c/positions [5 tid] :int-int :int-int)
             [[9 48]]))
      (is (= (l/range-count lmdb c/positions [:closed [5 0] [5 Long/MAX_VALUE]]
                            :int-int)
             9))

      (is (= (l/get-value lmdb c/docs :doc1 :data :int-short true) [1 7]))
      (is (= (l/get-value lmdb c/docs :doc4 :data :int-short true) [4 7]))
      (is (= (l/range-count lmdb c/docs [:all]) 5))

      (sut/remove-doc engine :doc5)
      (is (= (l/range-count lmdb c/docs [:all]) 4))
      (is (not (l/in-list? lmdb c/term-docs tid 5 :int :int)))
      (is (= (l/list-count lmdb c/term-docs tid :int) 3))
      (is (= (l/list-count lmdb c/positions [5 tid] :int-id) 0))
      (is (= (l/get-list lmdb c/positions [5 tid] :int-id :int-int) [])))

    (l/close-kv lmdb)))

(deftest search-test
  (let [lmdb   (l/open-kv (u/tmp-dir (str "search-" (UUID/randomUUID))))
        engine ^SearchEngine (sut/new-engine lmdb)]
    (add-docs engine)

    (is (= (sut/search engine "cap" {:display :offsets})
           (sut/search engine "cap" {:algo :prune :display :offsets})
           (sut/search engine "cap" {:algo :bitmap :display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "notaword cap" {:display :offsets})
           (sut/search engine "notaword cap" {:algo :prune :display :offsets})
           (sut/search engine "notaword cap" {:algo :bitmap :display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "fleece" {:display :offsets})
           (sut/search engine "fleece" {:algo :prune :display :offsets})
           (sut/search engine "fleece" {:algo :bitmap :display :offsets})
           [[:doc4 [["fleece" [22]]]] [:doc2 [["fleece" [29]]]]]))
    (is (= (sut/search engine "red fox" {:display :offsets})
           (sut/search engine "red fox" {:algo :prune :display :offsets})
           (sut/search engine "red fox" {:algo :bitmap :display :offsets})
           [[:doc1 [["fox" [14]] ["red" [10 39]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]
            [:doc5 [["red" [48]]]]]))
    (is (= (sut/search engine "red dogs" {:display :offsets})
           (sut/search engine "red dogs" {:algo :prune :display :offsets})
           (sut/search engine "red dogs" {:algo :bitmap :display :offsets})
           [[:doc1 [["dogs" [43]] ["red" [10 39]]]]
            [:doc5 [["dogs" [52]] ["red" [48]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]]))
    (is (empty? (sut/search engine "solar")))
    (is (empty? (sut/search engine "solar" {:algo :prune})))
    (is (empty? (sut/search engine "solar" {:algo :bitmap})))
    (is (empty? (sut/search engine "solar wind")))
    (is (empty? (sut/search engine "solar wind" {:algo :prune})))
    (is (empty? (sut/search engine "solar wind" {:algo :bitmap})))
    (is (= (sut/search engine "solar cap" {:display :offsets})
           (sut/search engine "solar cap" {:algo :prune :display :offsets})
           (sut/search engine "solar cap" {:algo :bitmap :display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (l/close-kv lmdb)))