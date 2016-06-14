(ns same-day-different-lives.core
    (:require [reagent.core :as reagent :refer [atom create-class]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [reagent-forms.core :refer [bind-fields]]
              [ajax.core :refer [GET POST]]
              [clojure.string :as string]
              [clojure.walk :refer [keywordize-keys stringify-keys]]))


;; -------------------------
;; Views

(defn submit-file [] 
  ; (POST "/api/files" 
  ;   {:body (js/document.getElementById "file-form") 
  ;    :handler #(prn "success" %)
  ;    :error-handler #(prn "error" %)}))
  (let [file-input (js/document.getElementById "photo")
        file (aget file-input "files" 0)
        form-data (doto
                    (js/FormData.)
                    (.append "file" file (aget file "name")))]
  (POST "/api/files" {:body form-data
                      :error-handler #(prn "error" %)})))
  
(defn home-page []
  (let [fields (atom {})]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Take a photo of your breakfast"]
       [bind-fields [:input {:field :file :id :photo :accept "image/*"}] fields]
       [:p 
        [:button { :on-click submit-file } "Send" ]]
       ])))

(defn record-page [] 
  ; Shim
  (aset js/navigator "AudioContext" (or js/window.AudioContext js/window.webkitAudioContext))
  (aset js/navigator "getUserMedia" (or js/navigator.getUserMedia js/navigator.webkitGetUserMedia))
  (let [audio-source (atom nil)
        audio-recorder (atom nil)
        recording-url (atom nil) 
        click-count (atom 0)
        
        audio-context (js/AudioContext.)
        stream (js/navigator.getUserMedia #js { "audio" true } 
                (fn [stream] 
                  (reset! audio-source (.createMediaStreamSource audio-context stream))
                  (reset! audio-recorder (js/WebAudioRecorder. @audio-source #js { "workerDir" "js/" }))
                  (aset @audio-recorder "onEncoderLoaded" (fn [recorder _] (prn  "encoder loaded")))
                  (aset @audio-recorder "onComplete" 
                        (fn [recorder blob] 
                         (prn  "got blob" blob)
                         (reset! recording-url (js/URL.createObjectURL blob))
                         (prn "got url" @recording-url)))
                  (aset @audio-recorder "onError" (fn [recorder msg] (prn  "got error" msg)))
                  (prn "setup recorder" @audio-recorder))
                (fn [err] (prn err)))
        start-recording #(.startRecording @audio-recorder)
        stop-recording #(.finishRecording @audio-recorder)]
    (fn []     
      [:div
       [:h2 "Same Day Different Lives"]
       [:h3 "Record a story of the first thing that you thought of this morning"]
       [:p
        [:button { :on-click start-recording } "Record"]
        [:button { :on-click stop-recording } "Stop"]]
       [:p
        [:audio { :src @recording-url :controls true }]]])))


(defn current-page []
  [:div [(session/get :current-page)]])


;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

(secretary/defroute "/record" []
  (session/put! :current-page #'record-page))


;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (accountant/configure-navigation!
    {:nav-handler
     (fn [path]
       (secretary/dispatch! path))
     :path-exists?
     (fn [path]
       (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
