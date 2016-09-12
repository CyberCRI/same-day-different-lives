(ns same-day-different-lives.server
  (:require [same-day-different-lives.handler :refer [app]]
            [same-day-different-lives.worker :refer [run-worker]]
            [same-day-different-lives.config :refer [config]]
            [same-day-different-lives.conversion :refer [has-deps?]]
            [org.httpkit.server :refer [run-server]])
  (:gen-class))

 (defn -main [& args]
   (if-not (has-deps?) 
    (throw (Error. "ffmpeg is either not installed or inaccessible"))
    (let [port (Integer/parseInt (or (:port config) "3000"))]
     (run-server app {:port port :join? false})
     (run-worker))))
