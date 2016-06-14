(ns same-day-different-lives.middleware
  (:require [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
            [prone.middleware :refer [wrap-exceptions]]
            [ring.middleware.reload :refer [wrap-reload]]))

(defn wrap-middleware [handler defaults]
  (-> handler
      (wrap-defaults defaults)
      wrap-exceptions
      wrap-reload))
