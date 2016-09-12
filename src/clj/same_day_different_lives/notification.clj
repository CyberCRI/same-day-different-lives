(ns same-day-different-lives.notification
  (:require [org.httpkit.server :as httpkit]))


(def users-to-channels (atom {}))


(defn send-by-ws [user-id data]
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

