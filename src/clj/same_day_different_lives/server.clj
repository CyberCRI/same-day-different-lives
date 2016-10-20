(ns same-day-different-lives.server
  (:require [same-day-different-lives.handler :refer [app]]
            [same-day-different-lives.worker :refer [run-worker]]
            [same-day-different-lives.config :refer [config]]
            [same-day-different-lives.conversion :refer [list-missing-deps]]
            [same-day-different-lives.notification :refer [run-emailer]]
            [org.httpkit.server :refer [run-server]]
            [clojure.string :as string])
  (:gen-class))

 (defn -main [& args]
   (let [missing-deps (list-missing-deps)]
     (if-not (empty? missing-deps) 
      (throw (Error. (str "Can't find command-line dependencies: " (string/join ", " missing-deps))))
      (let [port (Integer/parseInt (or (:port config) "3000"))]
       (run-server app {:port port :join? false})
       (run-worker)
       (run-emailer)))))
