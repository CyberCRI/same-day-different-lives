(ns same-day-different-lives.worker
  (:require [clojure.core.async :refer [<! timeout chan go]]
            [config.core :refer [env]]
            [clojure.java.jdbc :as jdbc]
            [clj-time.jdbc]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(def db (merge (:db env) { :stringtype "unspecified" }))

(defn pair-users []
  "Makes pairs of users"
  ; TODO try to find people who haven't been together
  ; TODO try to find people who are demographically different
  ; For now, just pair the users randomly
  (let [users (jdbc/query db ["select user_id from users
                               where status = 'ready'"]
                          {:row-fn :user_id})
        user-pairs (partition 2 (shuffle users))
        challenges (jdbc/query db ["select challenge_id from challenges"]
                               {:row-fn :challenge_id})
        selected-challenges (take 7 (shuffle challenges))]
    (doseq [[user-a user-b] user-pairs]
      (prn "making match for users" user-a user-b "challenges" selected-challenges)
      ; Make match
      (let [[{match-id :match_id}] 
            (jdbc/insert! db :matches { :user_a user-a :user_b user-b
                                        :starts_at (t/now)
                                        :ends_at (t/plus (t/now) (t/days 7)) })]
        ; Setup challenges
        (doseq [challenge selected-challenges]
          (jdbc/insert! db :challenge_instances { :challenge_id challenge :match_id match-id })))

      ; Change user statuses
      (jdbc/update! db :users { :status "playing" } ["user_id in (?, ?)" user-a user-b]))))

(defn run-worker [] 
  (prn "worker started") 
  (go
    (while true
      (<! (timeout 1000))
      ; TODO: expire matches
      (pair-users))))
