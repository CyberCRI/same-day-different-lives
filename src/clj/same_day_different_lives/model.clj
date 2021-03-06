(ns same-day-different-lives.model
  (:require [same-day-different-lives.config :refer [config]]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]))

; DATA

(def db (merge (:db config) { :stringtype "unspecified" }))

; Read names.txt file from packaged resources, and split into array
(def names 
  (-> (io/resource "names.txt")
      io/reader
      slurp
      (string/split #"\n")))

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

(defn transform-dates-to-strings [m] 
  "Copies any date vales to the string equivalent"
  (reduce-kv (fn [m k v] (assoc m k (if (instance? org.joda.time.DateTime v) (.toString v) v))) 
             {} 
             m))

(defn prepare-for-json [m] 
  (-> m transform-keys-to-clojure-keywords transform-dates-to-strings))


; OPERATIONS

(defn random-name [] (rand-nth names))

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

(defn list-quiz-responses [match-id]
  (jdbc/query db ["select user_id, created_at, gender, birth_year, religion_id, region_id, skin_color, education_level_id, politics_social, politics_economics
                  from quiz_responses
                  where match_id = ?" match-id]
                 {:row-fn prepare-for-json}))

(defn submit-quiz-response [values-map] 
  (jdbc/insert! db :quiz_responses (transform-keys-to-db-keywords values-map)))

(defn get-public-user-info [user-id]
  (first (jdbc/query db 
                     ["select user_id, pseudo, gender, birth_year, religion_id, region_id, skin_color, education_level_id, politics_social, politics_economics
                      from users
                      where user_id = ?
                      limit 1" user-id]
                     {:row-fn prepare-for-json})))

(defn get-match [match-id]
  (first (jdbc/query db 
                     ["select match_id, user_a, user_b, created_at, starts_at, ends_at, status 
                      from matches
                      where match_id = ?"
                      match-id]
                      {:row-fn prepare-for-json})))
 
(defn list-exchanges [match-id]
  (jdbc/query db 
              ["select exchange_id, user_id, created_at, message 
                from exchanges
                where match_id = ?
                order by created_at desc"
               match-id]
              {:row-fn prepare-for-json}))

(defn submit-exchange [values-map] 
  (jdbc/insert! db :exchanges (transform-keys-to-db-keywords values-map)))

(defn already-matched? [user-a user-b]
  "Returns true if the two users have already played a match together"
  (-> (jdbc/query db
                  ["select exists(
                      select match_id 
                      from matches 
                      where user_a = ? and user_b = ?
                        or user_a = ? and user_b = ?)" 
                   user-a user-b user-b user-a])
      first
      :exists))

(defn count-users [] 
  "Returns the number of registered users"
  (-> (jdbc/query db
                  ["select count(*) 
                    from users"])
      first
      :count))

(defn count-matches [] 
  "Returns the number of matches in each stage"
  (let [initial-counts {:challenge 0 :quiz 0 :exchange 0 :over 0}
        results (jdbc/query db
                             ["select status, count(*) 
                               from matches
                               group by status"])
        step-counts (reduce #(assoc %1 (keyword (:status %2)) (:count %2)) initial-counts results)
        total (apply + (vals step-counts))]
    ; Assign total to map and return
    (assoc step-counts :total total)))
