(ns same-day-different-lives.config
  (:require [config.core :refer [env]]
            [clojure.edn :as edn]))

(defonce config 
  (merge (edn/read-string (slurp "config.edn")) 
         env))
