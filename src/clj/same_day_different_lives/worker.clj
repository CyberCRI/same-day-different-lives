(ns same-day-different-lives.worker
  (:require [clojure.core.async :refer [<! timeout chan go]]
            [config.core :refer [env]]))

(def db (:db env))

(defn worker []
  (prn "worker run"))

(defn run-worker [] 
  (prn "run worker called with env" db) 
  (go
    (while true
      (<! (timeout 1000))
      (worker))))
