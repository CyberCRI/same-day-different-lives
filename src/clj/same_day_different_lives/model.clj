(ns same-day-different-lives.model
  (:require [clojure.string :as string]))

(defn to-db-keyword [clojure-keyword] 
  "Transforms a Clojure-style keyword into a DB-style keyword by lowering dashes" 
  (-> clojure-keyword
      (string/replace "-" "_")
      (string/replace ":" "")
      keyword))

(defn transform-keys-to-db-keywords [m]
  "Transforms a map with Clojure-style keyword keys to DB-style keyword keys" 
  (reduce-kv (fn [m k v] (assoc m (to-db-keyword k) v)) {} m))

