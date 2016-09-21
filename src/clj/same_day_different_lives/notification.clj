(ns same-day-different-lives.notification
  (:require [org.httpkit.server :as httpkit]
            [clojure.data.json :as json]
            [same-day-different-lives.config :refer [config]]))


;; -------------------------
;;; Data

(def users-to-channels (atom {}))


;; -------------------------
;;; Functions


; Notifications look like:
; { :type :new-response :match-id 51 :challenge-instance-id 355 }
; { :type :unlocked-challenge :match-id 51 :challenge-instance-id 355 }
; The types are: 
;   :new-response
;   :unlocked-challenge
;   :created-match
;   :ended-match

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


(defn prepare-email [notification]
  (condp = (:type notification)
    :new-response {:text "The other player has answered the question."
                   :link (str "/match/" (:match-id notification))}
    :unlocked-challenge {:text "There's a new question to answer."
                         :link (str "/match/" (:match-id notification))} 
    :ended-match {:text "Your journal has ended."
                  :link (str "/match/" (:match-id notification))}
    :created-match {:text "You have been paired up to make a new journal."
                    :link (str "/match/" (:match-id notification))}
    (throw  (Error. (str "ERROR unknown notification type" (:type notification))))))

(defn make-email-body [email-fields]
  (str "Hello Same Day Different Lives player, \n\n"
       (:text email-fields) "\n\n"
       "Go to " (get-in config [:email :link-prefix] " to find out more.\n\n")
       "Yours truly,\n"
       "The SDDL Robot"))

(defn send-by-email! [user-id notification]
  ; TODO: actually send email :)
  (prn "Sending mail to " user-id ": " notification)
  (let [email-fields (prepare-email notification)
        email-body (make-email-body email-fields)]
    ; TODO: actually send email
    ))


(defn send! [user-id notification]
  (if (user-is-connected? user-id)
    (send-by-ws! user-id notification)
    (send-by-email! user-id notification)))
