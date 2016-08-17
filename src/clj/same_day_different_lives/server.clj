(ns same-day-different-lives.server
  (:require [same-day-different-lives.handler :refer [app]]
            [same-day-different-lives.worker :refer [run-worker]]
            [same-day-different-lives.config :refer [config]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

 (defn -main [& args]
   (let [port (Integer/parseInt (or (:port config) "3000"))]
     (run-jetty app {:port port :join? false})
     (run-worker)))
