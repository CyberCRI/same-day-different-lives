(ns same-day-different-lives.model
  (:require [same-day-different-lives.config :refer [config]]
            [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]))

; DATA

(def db (merge (:db config) { :stringtype "unspecified" }))


; UTILITY FUNCTIONS

(defn to-db-keyword [clojure-keyword] 
  "Transforms a Clojure-style keyword into a DB-style keyword by lowering dashes" 
  (-> clojure-keyword
      (string/replace "-" "_")
      (string/replace ":" "")
      keyword))

(defn transform-keys-to-db-keywords [m]
  "Transforms a map with Clojure-style keyword keys to DB-style keyword keys" 
  (reduce-kv (fn [m k v] (assoc m (to-db-keyword k) v)) {} m))

(defn to-clojure-keyword [db-keyword] 
  "Transforms a DB-style keyword into a Clojure-style keyword by raising dashes" 
  (-> db-keyword
      (string/replace "_" "-")
      (string/replace ":" "")
      keyword))

(defn transform-keys-to-clojure-keywords [m]
  "Transforms a map with DB-style keyword keys to DB-style keyword keys" 
  (reduce-kv (fn [m k v] (assoc m (to-clojure-keyword k) v)) {} m))


; OPERATIONS

(defn list-religions [] 
  (jdbc/query db ["select religion_id, religion_name
                  from religion
                  order by religion_id"]
                 {:row-fn transform-keys-to-clojure-keywords}))

(defn list-regions [] 
  (jdbc/query db ["select region_id, region_name
                  from region
                  order by region_id"]
                 {:row-fn transform-keys-to-clojure-keywords}))

(defn list-education-levels [] 
  (jdbc/query db ["select education_level_id, education_level_name
                  from education_level
                  order by education_level_id"]
                 {:row-fn transform-keys-to-clojure-keywords}))

(defn submit-quiz-response [values-map] 
  (jdbc/insert! db :quiz_responses (transform-keys-to-db-keywords values-map)))