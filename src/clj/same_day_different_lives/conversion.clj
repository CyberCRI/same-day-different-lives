(ns same-day-different-lives.conversion
  (:require [clojure.java.shell :refer [sh]]))

(defn list-missing-deps []
  "Returns list of missing command-line dependencies"
  (remove nil? [
         (when (not= 0 (:exit (sh "which" "ffmpeg"))) "ffmpeg")
         (when (not= 0 (:exit (sh "which" "convert"))) "ImageMagick")]))

(defn convert-audio-video [input-filename output-filename]
  "Returns true if conversion worked"
  (prn "convert-audio-video called with input" input-filename "output" output-filename)
  (let [result (sh "ffmpeg" "-i" input-filename output-filename)]
    (if (not= 0 (:exit result))
      (do
        (prn "Error converting file:" (:err result))
        false)
      true)))

(defn convert-photo [input-filename output-filename]
  "Returns true if conversion worked"
  (prn "convert-photo called with input" input-filename "output" output-filename)
  (let [result (sh "convert" input-filename "-auto-orient" output-filename)]
    (if (not= 0 (:exit result))
      (do
        (prn "Error converting file:" (:err result))
        false)
      true)))
