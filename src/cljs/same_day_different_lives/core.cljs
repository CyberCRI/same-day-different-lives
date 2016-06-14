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

(defn current-page []
  [:div [(session/get :current-page)]])

;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))


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
