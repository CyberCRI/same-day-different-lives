(ns same-day-different-lives.conversion
  (:require [clojure.java.shell :refer [sh]]))

(defn has-deps? []
  "Returns true if ffmpeg is installed"
  (= 0 (:exit (sh "which" "ffmpeg"))))

(defn convert-file [input-filename output-filename]
  "Returns true if conversion worked"
  (prn "convert-file called with input" input-filename "output" output-filename)
  (let [result (sh "ffmpeg" "-i" input-filename output-filename)]
    (if (not= 0 (:exit result))
      (do
        (prn "Error converting file:" (:err result))
        false)
      true)))
