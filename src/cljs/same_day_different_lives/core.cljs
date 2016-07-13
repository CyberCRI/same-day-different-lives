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
    {:handler (fn [match] 
                (let [match-keys (keywordize-keys match)]
                  (reset! match-model (if (:match-id match-keys) match-keys nil))))}))

(defn get-match-model [match-id match-model]
  (GET (str "/api/match/" match-id) 
    {:handler (fn [match] (reset! match-model (keywordize-keys match)))}))

(defn get-challenge-instance [challenge-instance-id challenge-instance-model]
  (GET (str "/api/challenge-instance/" challenge-instance-id) 
    {:handler (fn [challenge-instance] (reset! challenge-instance-model (keywordize-keys challenge-instance)))}))

 
(defn find-first-other [col value]
 ; Returns the first item in col that is not equal to val
 (first (filter #(not= % value) col))) 

(defn submit-file [match-id challenge-instance-id] 
  (let [file-input (js/document.getElementById "file-input")
        file (aget file-input "files" 0)
        form-data (doto
                    (js/FormData.)
                    (.append "file" file (aget file "name")))]
  (POST (str "/api/challenge-instance/" challenge-instance-id) 
    {:body form-data
     :error-handler #(prn "error uploading")
     :handler #(accountant/navigate! (str "/match/" match-id))})))


;; -------------------------
;; Views

(defn respond-page [match-id challenge-instance-id]
  (let [challenge-instance-model (atom nil)]
    (get-challenge-instance challenge-instance-id challenge-instance-model)
    (fn []
      [:div 
        [:h2 "Same Day Different Lives"]
        [:h3 (:description @challenge-instance-model)]
        [:p (str "Reply with a " (if (= "audio" (:type @challenge-instance-model)) "recording" "picture"))]
        [:input {:type :file 
                 :id :file-input 
                 :accept (str (:type @challenge-instance-model) "/*")}] 
        [:p 
          [:button { :on-click #(submit-file match-id challenge-instance-id) } "Send" ]]])))

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
                          [:a {:href (str "/match/" (:match-id @match-model))} "See your current conversation"]])
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
        [:div.row
         [:div.six.columns
          [:label {:for "email"} "Email"] 
          [:input.u-full-width {:field :text :id :email}]] 
         [:div.six.columns
          [:label {:for "password"} "Password"] 
          [:input.u-full-width {:field :password :id :password}]]]
        fields]
       [:div.row 
        [:button.button-primary {:on-click login} "Login"]]
       [:div.row 
        [:p.error-message @error-message]]
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

(defn to-ms [date-string] (.getTime (new js/Date date-string)))

(defn active? [challenge]
  (let [{:keys [starts-at ends-at]} challenge]
    (and 
      (< (to-ms starts-at) (js/Date.now))
      (> (to-ms ends-at) (js/Date.now)))))

(defn responded-to-challenge? [challenge]
  (let [responses (:responses challenge)]
    (not-empty (filter #(= (:user %) (:pseudo @user-model)) responses))))

(defn match-page [match-id]
  (let [match-model (atom nil)]
    (get-match-model match-id match-model)
    (fn []
      (let [{:keys [match challenges]} @match-model
            other-pseudo (find-first-other [(:user-a match) (:user-b match)] 
                                           (:pseudo @user-model))
            showable-challenges (filter #(< (to-ms (:starts-at %)) (js/Date.now)) challenges)
            upcoming-challenges (filter #(> (to-ms (:starts-at %)) (js/Date.now)) challenges)] 
        [:div 
         [:h2 "Same Day Different Lives"]
         [:h3 (str "Conversation with " other-pseudo)]
         [:p (if (:running match) "Going now" "Already over")]
         (for [challenge showable-challenges]
           [:div.box.challenge 
            [:h4 "Challenge: " [:em (:description challenge)]]
            (when (and (not (responded-to-challenge? challenge)) (active? challenge))
              [:a {:href (str "/match/" match-id "/respond/" (:challenge-instance-id challenge))} "Answer now!"])
            (for [response (:responses challenge)]
              [:div.row 
               [:div {:class "two columns"} 
                [:div.header (:user response)]]
               [:div {:class "ten columns"}
                (if (= "image" (:type challenge))
                  [:img.response-image {:src (str "/uploads/" (:filename response))}]
                  [:audio.response-image {:controls true :src (str "/uploads/" (:filename response))}])]])])
         [:div.row.section 
          [:h4 (str "Plus " (count upcoming-challenges) " more challenges to come...")]]]))))
       
       
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
  (session/put! :current-page [#'match-page match-id]))

(secretary/defroute "/match/:match-id/respond/:challenge-instance-id" [match-id challenge-instance-id]
  (session/put! :current-page [#'respond-page match-id challenge-instance-id]))



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
