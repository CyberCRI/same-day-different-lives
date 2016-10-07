(ns same-day-different-lives.worker
  (:require [clojure.core.async :refer [<! timeout chan go]]
            [same-day-different-lives.config :refer [config]]
            [same-day-different-lives.notification :as notification]
            [same-day-different-lives.model :as model]
            [clojure.java.jdbc :as jdbc]
            [clj-time.jdbc]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(def db (merge (:db config) { :stringtype "unspecified" }))

; Taken from https://github.com/mikera/clojure-utils/blob/master/src/main/clojure/mikera/cljutils/loops.clj
; Licensed under LGPL 3
(defmacro doseq-indexed 
  "loops over a set of values, binding index-sym to the 0-based index of each value"
  ([[val-sym values index-sym] & code]
  `(loop [vals# (seq ~values) 
          ~index-sym (long 0)]
     (if vals#
       (let [~val-sym (first vals#)]
             ~@code
             (recur (next vals#) (inc ~index-sym)))
       nil))))

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
        selected-challenges (take 6 (shuffle challenges))]
    (doseq [[user-a user-b] user-pairs]
      (prn "making match for users" user-a user-b "challenges" selected-challenges)
      ; Make match
      (let [[{match-id :match_id}] 
            (jdbc/insert! db :matches { :user_a user-a :user_b user-b
                                        :starts_at (t/now)
                                        :quiz_at (t/plus (t/now) (t/days 6)) 
                                        :ends_at (t/plus (t/now) (t/days 7)) })]
        ; Setup challenges
        (doseq-indexed [challenge selected-challenges offset]
          (jdbc/insert! db :challenge_instances {:challenge_id challenge :match_id match-id
                                                 :starts_at (t/plus (t/now) (t/days offset))
                                                 :ends_at (t/plus (t/now) (t/days (inc offset)))}))

        ; Send notifications
        (doseq [user-id [user-a user-b]]
          (notification/send! user-id 
                              {:type :created-match 
                               :match-id match-id})))

      ; Change user statuses
      (jdbc/update! db :users { :status "playing" } ["user_id in (?, ?)" user-a user-b]))))

(defn move-matches-to-quiz-mode [] 
  "Look for matches that should enter the quiz mode, and change the status"
  (let [quiz-matches (jdbc/query db ["select match_id, user_a, user_b 
                                        from matches
                                        where status = 'challenge'
                                          and quiz_at < now()"]
                          {:row-fn model/transform-keys-to-clojure-keywords})]
    (doseq [{:keys [match-id user-a user-b]} quiz-matches]
      (prn "moving match to quiz mode" match-id)
      ; Set match to quiz mode
      (jdbc/update! db :matches {:status "quiz"} ["match_id = ?" match-id])
      ; Send notifications
      (doseq [user-id [user-a user-b]]
        (notification/send! user-id 
                            {:type :unlocked-quiz 
                             :match-id match-id})))))

(defn expire-matches [] 
  "Look for matches that are over, and expire them"
  (let [expired-matches (jdbc/query db ["select match_id, user_a, user_b 
                                        from matches
                                        where status != 'over'
                                          and ends_at < now()"]
                          {:row-fn (fn [{:keys [match_id user_a user_b]}]
                                     {:match-id match_id :user-a user_a :user-b user_b})})]
    (doseq [{:keys [match-id user-a user-b]} expired-matches]
      (prn "expiring match " match-id)
      ; Set match to expired
      (jdbc/update! db :matches {:status "over"} ["match_id = ?" match-id])
      ; Set challenge instances to expired
      (jdbc/update! db :challenge_instances {:status "over"} ["match_id = ?" match-id])
      ; Change user statuses
      (jdbc/update! db :users { :status "ready" } ["user_id in (?, ?)" user-a user-b])
      ; Send notifications
      (doseq [user-id [user-a user-b]]
        (notification/send! user-id 
                            {:type :ended-match 
                             :match-id match-id})))))

(defn unlock-challenge-instances [] 
  "Look for challenge instances that have just begun, and unlock them"
  (let [unlocked-challenge-instances (jdbc/query db ["select matches.match_id, matches.user_a, matches.user_b, challenge_instances.challenge_instance_id 
                                                    from matches, challenge_instances
                                                    where challenge_instances.match_id = matches.match_id 
                                                      and matches.status != 'over'
                                                      and challenge_instances.status = 'upcoming'
                                                      and challenge_instances.starts_at < now()
                                                      and challenge_instances.ends_at > now()"]
                                                {:row-fn (fn [{:keys [match_id user_a user_b challenge_instance_id]}]
                                                           {:match-id match_id :user-a user_a :user-b user_b :challenge-instance-id challenge_instance_id})})]
    (doseq [{:keys [match-id user-a user-b challenge-instance-id]} unlocked-challenge-instances]
      (prn "unlocking challenge instance " challenge-instance-id)
      ; Set challenge instance to active
      (jdbc/update! db :challenge_instances {:status "active"} ["challenge_instance_id = ?" challenge-instance-id])
      ; Send notifications
      (doseq [user-id [user-a user-b]]
        (notification/send! user-id 
                            {:type :unlocked-challenge 
                             :match-id match-id
                             :challenge-instance-id challenge-instance-id})))))

(defn expire-challenge-instances [] 
  "Look for challenge instances that are over, and expire them"
  (jdbc/execute! db ["update challenge_instances
                     set status = 'over'
                     where status != 'over'
                      and ends_at < now()"]))

(defn run-worker [] 
  (prn "worker started") 
  (go
    (while true
      (<! (timeout 1000))
      (expire-matches)
      (move-matches-to-quiz-mode)
      (expire-challenge-instances)
      (unlock-challenge-instances)
      (pair-users))))
