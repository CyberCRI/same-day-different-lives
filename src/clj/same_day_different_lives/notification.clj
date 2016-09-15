(ns same-day-different-lives.notification
  (:require [org.httpkit.server :as httpkit]
            [clojure.data.json :as json]))


;; -------------------------
;;; Data

(def users-to-channels (atom {}))


;; -------------------------
;;; Functions


; Notifications look like:
; { :type :new-response :match-id 51 :challenge-instance-id 355 }
; { :type :unlocked-challenge :match-id 51 :challenge-instance-id 355 }


(defn user-is-connected? [user-id] (contains? @users-to-channels user-id))

(defn send-by-ws! [user-id notification]
  """ If the user is connected, send the notification to them by websocket """
  (when-let [channel (@users-to-channels user-id)]
    (prn "Sending WS notification to " user-id ": " notification)
    (httpkit/send! channel (json/write-str notification))))

(defn ws-handler [request]
  (let [user-id (get-in request [:session :user-id])]
    (httpkit/with-channel request channel
      (prn "accepting WS connection from user" user-id)
      (swap! users-to-channels assoc user-id channel)
      
      (httpkit/on-close channel (fn [status] 
                                  (println "channel closed: " status " for user " user-id)
                                  (swap! users-to-channels dissoc user-id))))))

(defn send-by-email! [user-id notification]
  ; TODO: actually send email :)
  (prn "Sending mail to " user-id ": " notification))

(defn send! [user-id notification]
  (if (user-is-connected? user-id)
    (send-by-ws! user-id notification)
    (send-by-email! user-id notification)))
