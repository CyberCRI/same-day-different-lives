(ns same-day-different-lives.server
  (:require [same-day-different-lives.handler :refer [app]]
            [same-day-different-lives.worker :refer [run-worker]]
            [config.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

 (defn -main [& args]
   (let [port (Integer/parseInt (or (env :port) "3000"))]
     (run-jetty app {:port port :join? false})
     (run-worker)))
