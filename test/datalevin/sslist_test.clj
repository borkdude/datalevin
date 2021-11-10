(ns datalevin.sslist-test
  (:require [datalevin.sslist :as sut]
            [datalevin.bits :as b]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :as test]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer [deftest is]])
  (:import
   [java.nio ByteBuffer]
   [datalevin.sslist SparseShortArrayList]
   [org.eclipse.collections.impl.list.mutable.primitive ShortArrayList]))

(deftest basic-ops-test
  (let [ssl (sut/sparse-short-arraylist)]
    (-> ssl
        (sut/set 42 99)
        (sut/set 88 888)
        (sut/set 1000 2)
        (sut/set 2000 0))
    (is (= (sut/size ssl) 4))
    (is (= (sut/select ssl 0) 99))
    (is (= (sut/select ssl 1) 888))
    (is (= (seq (.toArray ^ShortArrayList (.-items ^SparseShortArrayList ssl)))
           [99 888 2 0]))
    (is (nil? (sut/get ssl 99)))
    (is (= (sut/get ssl 42) 99))
    (is (= (sut/get ssl 1000) 2))
    (is (= (sut/get ssl 2000) 0))
    (is (= ssl (sut/sparse-short-arraylist {42 99 88 888 1000 2 2000 0})))
    (is (= ssl (sut/sparse-short-arraylist [42 88 1000 2000] [99 888 2 0])))
    (sut/set ssl 1000 3)
    (is (= (sut/get ssl 1000) 3))
    (is (= ssl (sut/sparse-short-arraylist {42 99 88 888 1000 3 2000 0})))
    (sut/remove ssl 1000)
    (is (nil? (sut/get ssl 1000)))
    (is (= (sut/get ssl 2000) 0))
    (is (= (sut/select ssl 2) 0))
    (is (= ssl (sut/sparse-short-arraylist {42 99 88 888 2000 0})))
    (is (= (sut/size ssl) 3))))

(test/defspec nippy-roundtrip-generative-test
  100
  (prop/for-all [ks (gen/vector gen/int)
                 vs (gen/vector gen/small-integer)]
                (let [^ByteBuffer bf            (b/allocate-buffer 16384)
                      ks                        (sort ks)
                      ^SparseShortArrayList ssl (sut/sparse-short-arraylist ks vs)]
                  (.clear bf)
                  (b/put-buffer bf ssl)
                  (.flip bf)
                  (= ssl (b/read-buffer bf)))))
