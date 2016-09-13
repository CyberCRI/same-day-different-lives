(ns same-day-different-lives.notification
  (:require [org.httpkit.server :as httpkit]))


;; -------------------------
;;; Data

(def users-to-channels (atom {}))


;; -------------------------
;;; Functions

(defn user-is-connected? [user-id] (contains? @users-to-channels user-id))

(defn send-by-ws! [user-id data]
  """ If the user is connected, send the data to them by websocket """
  (when-let [channel (@users-to-channels user-id)]
    (httpkit/send! channel data)))

(defn ws-handler [request]
  (let [user-id (get-in request [:session :user-id])]
    (httpkit/with-channel request channel
      (prn "accepting WS connection from user" user-id)
      (swap! users-to-channels assoc user-id channel)
      
      (httpkit/on-close channel (fn [status] 
                                  (println "channel closed: " status " for user " user-id)
                                  (swap! users-to-channels dissoc user-id))))))


(defn send-by-email! [user-id data]
  ; TODO: actually send email :)
  (prn "Sending mail to " user-id ": " data))


(defn send! [user-id notification]
  (if (user-is-connected? user-id)
    (send-by-ws! user-id notification)
    (send-by-email! user-id notification)))