(ns onyx.peer.single-peer-test
  (:require [midje.sweet :refer :all]
            [onyx.peer.hornetq-util :as hq-util]
            [onyx.api]))

(def n-messages 150)

(def batch-size 25)

(def timeout 500)

(def echo 10)

(def in-queue (str (java.util.UUID/randomUUID)))

(def out-queue (str (java.util.UUID/randomUUID)))

(hq-util/write-and-cap! in-queue (map (fn [x] {:n x}) (range n-messages)) echo)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n n))

(def catalog
  [{:onyx/name :in
    :onyx/direction :input
    :onyx/consumption :concurrent
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name in-queue
    :hornetq/host "localhost"
    :hornetq/port 5445
    :hornetq/batch-size batch-size
    :hornetq/timeout timeout}
   {:onyx/name :inc
    :onyx/fn :onyx.peer.single-peer-test/my-inc
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/timeout timeout}
   {:onyx/name :inc-2
    :onyx/fn :onyx.peer.single-peer-test/my-inc
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/timeout timeout}
   {:onyx/name :out
    :onyx/direction :output
    :onyx/consumption :concurrent
    :onyx/type :queue
    :onyx/medium :hornetq
    :hornetq/queue-name out-queue
    :hornetq/host "localhost"
    :hornetq/port 5445
    :onyx/batch-size batch-size
    :onyx/timeout timeout}])

(def workflow {:in {:inc :out}})

(def id (str (java.util.UUID/randomUUID)))

(def coord-opts {:datomic-uri (str "datomic:mem://" id)
                 :hornetq-host "localhost"
                 :hornetq-port 5445
                 :zk-addr "127.0.0.1:2181"
                 :onyx-id id
                 :revoke-delay 2000})

(def conn (onyx.api/connect (str "onyx:memory//localhost/" id) coord-opts))

(def peer-opts {:hornetq-host "localhost"
                :hornetq-port 5445
                :zk-addr "127.0.0.1:2181"
                :onyx-id id})

(def v-peers (onyx.api/start-peers conn 1 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results (hq-util/read! out-queue (inc n-messages) echo))

(try
  ;; (dorun (map deref (map :runner v-peers)))
  (finally
   (doseq [v-peer v-peers]
     (try
       ((:shutdown-fn v-peer))
       (catch Exception e (prn e))))
   (try
     (onyx.api/shutdown conn)
     (catch Exception e (prn e)))))

(fact results => (conj (vec (map (fn [x] {:n x}) (range n-messages))) :done))

