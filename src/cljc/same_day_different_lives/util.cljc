(ns same-day-different-lives.util)

(defn foo-cljc [x]
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(defn find-first-other [col value]
  ; Returns the first item in col that is not equal to val
  (first (filter #(not= % value) col))) 

(defn pluck [col & keys]
  "Similar to Underscore's pluck()"
  (for [row col] 
    (for [key keys] (get row key))))
