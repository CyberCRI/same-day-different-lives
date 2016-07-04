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
;; Data

(def user-model (atom nil))


;; -------------------------
;; Common functions

(defn check-login []
  (GET "/api/me" 
    {:handler (fn [user-info] (reset! user-model (keywordize-keys user-info)))}))

(defn logout []
  (POST "/api/logout" 
    {:handler (fn [user-info] 
                (reset! user-model nil)
                (accountant/navigate! "/"))}))

(defn status []
  (GET "/api/me" 
    {:handler (fn [user-info] (reset! user-model (keywordize-keys user-info)))}))

(defn change-state [status]
  (POST "/api/me" 
    {:params { :status status } 
     :format :json 
     :handler (fn [user-info] (reset! user-model (keywordize-keys user-info)))}))

(defn get-active-challenge [challenge-model]
  (GET "/api/me/challenge" 
    {:handler (fn [challenge] (reset! challenge-model (keywordize-keys challenge)))}))

(defn get-active-match [match-model]
  (GET "/api/me/match" 
    {:handler (fn [match] (reset! match-model (keywordize-keys match)))}))


;; -------------------------
;; Views

(defn submit-file [] 
  (let [file-input (js/document.getElementById "file-input")
        file (aget file-input "files" 0)
        form-data (doto
                    (js/FormData.)
                    (.append "file" file (aget file "name")))]
  (POST "/api/files" {:body form-data
                      :error-handler #(prn "error" %)})))
  
(defn submit-image-page []
  (let [fields (atom {})]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Take a photo of your breakfast"]
       [bind-fields [:input {:field :file :id :file-input :accept "image/*"}] fields]
       [:p 
        [:button { :on-click submit-file } "Send" ]]
       ])))

(defn submit-audio-page []
  (let [fields (atom {})]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Tell us about the first time you noticed that you were different"]
       [bind-fields [:input {:field :file :id :file-input :accept "audio/*"}] fields]
       [:p 
        [:button { :on-click submit-file } "Send" ]]
       ])))

(defn home-page [] 
  (let [match-model (atom nil)]
    (get-active-match match-model)
    (fn [] 
      [:div 
       [:h2 "Same Day Different Lives"]
       (if @user-model 
         [:div 
          [:p (str "You are logged in as " (:pseudo @user-model))]
          [:p [:button { :on-click logout } "Logout"]]
          (case (:status @user-model) 
            "dormant" [:div 
                       [:p "Do you want to play?"]
                       [:p [:button { :on-click #(change-state "ready") } "Find a match" ]]]
            "ready"   [:div 
                       [:p "Waiting for the game to find a match for you"]
                       [:p [:button { :on-click #(change-state "dormant") } "I don't want to play" ]]]
            "playing" [:div
                       [:p "You are playing"]
                       (when @match-model 
                         [:p 
                          [:a {:href "/match"} "See your current conversation"]])
                       [:p [:button { :on-click #(change-state "dormant") } "Stop playing" ]]])]
         [:p "You need to " 
          [:a {:href "/login"} "login"]
          " or " 
          [:a {:href "/signup"} "sign up"]])])))

(defn login-page [] 
  (let [fields (atom {})
        error-message (atom nil)
        login (fn [] 
                (POST "/api/login" 
                  {:params @fields 
                   :format :json 
                   :handler #((check-login) (accountant/navigate! "/"))
                   :error-handler #(reset! error-message "Could not log in")}))]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Login"]
       [bind-fields 
        [:div 
         [:input {:field :text :id :email}] 
         [:input {:field :password :id :password}]] 
        fields]
       [:p 
        [:button {:on-click login} "Login"]]
       [:p.error-message @error-message]
       ])))

(defn signup-page [] 
  (let [fields (atom {})
        error-message (atom nil)
        signup (fn [] 
                (POST "/api/users" 
                  {:params @fields 
                   :format :json 
                   :handler #(accountant/navigate! "/login")
                   :error-handler #(reset! error-message "Could not sign up")}))]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Sign up"]
       [bind-fields 
        [:div 
         [:p "Email " [:input {:field :text :id :email}]] 
         [:p "Pseudoname " [:input {:field :text :id :pseudo}]] 
         [:p "Password " [:input {:field :password :id :password}]]] 
        fields]
       [:p 
        [:button {:on-click signup} "Sign up"]]
       [:p.error-message @error-message]
       ])))

; TODO: have access to match by ID
(defn match-page [match-id]
  (let [challenge-model (atom nil)]
    (get-active-challenge challenge-model)
    (fn [] 
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 (str "Match " match-id)]])))
       
(defn current-page []
  (let [page (session/get :current-page)]  
    (if (vector? page)
      [:div page]      
      [:div [page]])))


;; -------------------------
;; Routes

(secretary/defroute "/" []
  (session/put! :current-page #'home-page))

(secretary/defroute "/login" []
  (session/put! :current-page #'login-page))

(secretary/defroute "/signup" []
  (session/put! :current-page #'signup-page))

(secretary/defroute "/match/:match-id" [match-id]
  (prn "match-id" match-id)
  (session/put! :current-page [#'match-page match-id]))

(secretary/defroute "/record" []
  (session/put! :current-page #'submit-audio-page))

(secretary/defroute "/audio" []
  (session/put! :current-page #'submit-image-page))



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
  (check-login)
  (mount-root))
