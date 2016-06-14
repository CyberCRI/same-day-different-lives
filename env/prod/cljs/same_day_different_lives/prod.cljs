(ns same-day-different-lives.prod
  (:require [same-day-different-lives.core :as core]))

;;ignore println statements in prod
(set! *print-fn* (fn [& _]))

(core/init!)
