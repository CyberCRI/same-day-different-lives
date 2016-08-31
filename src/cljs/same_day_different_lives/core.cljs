(ns same-day-different-lives.core
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as reagent :refer [atom create-class]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [reagent-forms.core :refer [bind-fields]]
              [ajax.core :refer [GET POST]]
              [clojure.string :as string]
              [clojure.walk :refer [keywordize-keys stringify-keys]]
              [cljs-http.client :as http]
              [cljs.core.async :refer [<! chan]]))

;; -------------------------
;; Data

(defonce user-model (atom nil))


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

(defn get-matches [matches-model]
  (GET "/api/me/matches" 
    {:handler (fn [matches] 
                (reset! matches-model (keywordize-keys matches)))}))

(defn get-match-model [match-id match-model]
  (GET (str "/api/match/" match-id) 
    {:handler (fn [match] (reset! match-model (keywordize-keys match)))
     :error-handler (fn [response] (reset! match-model {:error (get-in (keywordize-keys response) [:response :error])}))}))

(defn get-challenge-instance [challenge-instance-id challenge-instance-model]
  (GET (str "/api/challenge-instance/" challenge-instance-id) 
    {:handler (fn [challenge-instance] (reset! challenge-instance-model (keywordize-keys challenge-instance)))
     :error-handler (fn [response] (reset! challenge-instance-model {:error (get-in (keywordize-keys response) [:response :error])}))}))
 
(defn find-first-other [col value]
 ; Returns the first item in col that is not equal to val
 (first (filter #(not= % value) col))) 

(defn get-selected-file [] (aget (.getElementById js/document "file-input") "files" 0))

(defn submit-file [match-id challenge-instance-id] 
  "Returns [response-chan progress-chan]"
  (let [file (get-selected-file)
        progress-chan (chan)
        response-chan (http/post (str "/api/challenge-instance/" challenge-instance-id)
                                  {:multipart-params [["file" file]]
                                   :progress progress-chan})]
    [response-chan progress-chan]))

(defn confirm [f text]
  (when (js/confirm text) (f)))

(defn format-percent [x] (str (.floor js/Math (* x 100)) "%"))


;; -------------------------
;; Views

(defn button-link [href text]
  [:button {:on-click #(accountant/navigate! href)} text])

(defn header []
  [:div
    [:div.row 
      [:div.six.columns [:h2 "Same Day"]]
      [:div.six.columns.flip [:h2 "Different Lives"]]]
    [:div.row
      [:div.two.columns [:a {:href "/"} "Home"]]
      (if @user-model         
        [:div.ten.columns.align-right "You are " [:strong (:pseudo @user-model)] " "
          [:button { :on-click #(when (js/confirm "Are you sure you want logout?") (logout))} "Logout"]]
        [:div.ten.columns.align-right "Why not " 
          [button-link "/login" "log in"]
          " or " 
          [button-link "/signup" "sign up"]])]])

(defn respond-page [match-id challenge-instance-id]
  (let [challenge-instance-model (atom nil)
        file-selected? (atom false)
        upload-in-progress? (atom false)
        upload-progress (atom nil)
        error-message (atom nil)
        handle-submit (fn [] 
                        (when (not @upload-in-progress?)
                          (reset! upload-in-progress? true)
                          (let [[response-chan progress-chan] (submit-file match-id challenge-instance-id)]
                            (go 
                              (while @upload-in-progress?
                                (let [[v ch] (alts! [response-chan progress-chan])]
                                  (if (= ch response-chan)
                                    ; Response finished
                                    (do 
                                      (reset! upload-in-progress? false)
                                      (if (= :no-error (:error-code v))
                                        (accountant/navigate! (str "/match/" match-id))
                                        (reset! error-message (:body v))))
                                    ; Progress event
                                    (reset! upload-progress (format-percent (/ (:loaded v) (:total v)))))))))))]
    (get-challenge-instance challenge-instance-id challenge-instance-model)
    (fn []
      [:div 
        [header]
        (if (:error @challenge-instance-model)
          [:p.error-message (str "Error: " (:error @challenge-instance-model))]
          [:div
            [:h3 (:description @challenge-instance-model)]
            [:p (str "Reply with a " (if (= "audio" (:type @challenge-instance-model)) "recording" "picture"))]
            [:input {:type :file 
                     :id :file-input 
                     :accept (str (:type @challenge-instance-model) "/*")
                     :on-change #(reset! file-selected? (get-selected-file))}] 
            [:p 
              (when @file-selected?
                [:button.button-primary {:on-click handle-submit} 
                                        (if @upload-in-progress? (str "Sending... " @upload-progress) "Send")])]
            [:div.row 
              [:p.error-message @error-message]]])])))

(defn home-page [] 
  (let [match-model (atom nil)
        all-matches-model (atom nil)]
    (get-active-match match-model)
    (get-matches all-matches-model)
    (fn [] 
      [:div
       [header] 
       (when @user-model 
        (case (:status @user-model) 
          "dormant" [:div 
                     [:p "Do you want to play?"]
                     [:p [:button.button-primary { :on-click #(change-state "ready") } "Find a match" ]]]
          "ready"   [:div 
                     [:p "Waiting for the game to find a match for you"]
                     [:p [:button { :on-click #(change-state "dormant") } "I don't want to play anymore" ]]]
          "playing" [:div
                     [:p "You are currently playing"]
                     (when @match-model 
                       [:p 
                         [:button.button-primary {:on-click #(accountant/navigate! (str "/match/" (:match-id @match-model)))} "Go to your shared journal"]])
                     [:p [:button { :on-click (fn [] (confirm #(change-state "dormant") "Are you sure you want to stop?"))} "Stop playing" ]]]))
       (when @all-matches-model
        (let [past-matches (filter #(not (:running %1)) @all-matches-model)]
          [:div 
           [:h3 "Past Journals"]
           (if (empty? past-matches)
            [:p "No past journals"]
            (for [{:keys [match-id starts_at other-pseudo]} past-matches]
             [:div.row 
              [:div.six.columns (str starts_at)]
              [:div.six.columns 
               [:a {:href (str "/match/" match-id)} (str "Journal with " other-pseudo)]]]))]))])))

(defn login-page [] 
  (let [fields (atom {})
        error-message (atom nil)
        login (fn [] 
                (POST "/api/login" 
                  {:params @fields 
                   :format :json 
                   :handler (fn [] 
                              (check-login) 
                              (accountant/navigate! "/"))
                   :error-handler #(reset! error-message "Could not log in")})
                false)]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Login"]
       [:form {:on-submit login} 
        [bind-fields 
          [:div.row
           [:div.six.columns
            [:label {:for "email"} "Email"] 
            [:input.u-full-width {:field :email :id :email :required true}]] 
           [:div.six.columns
            [:label {:for "password"} "Password"] 
            [:input.u-full-width {:field :password :id :password :required true}]]]
          fields]
         [:div.row 
          [:input.button-primary {:type :submit :value "Login"}]]
       [:div.row 
        [:p.error-message @error-message]]]])))

(defn signup-page [] 
  (let [fields (atom {})
        error-message (atom nil)
        signup (fn [] 
                (if (not= (:password @fields) (:password2 @fields))
                  (reset! error-message "Passwords don't match")
                  (POST "/api/users" 
                    {:params @fields 
                     :format :json 
                     :handler #(accountant/navigate! "/login")
                     :error-handler #(reset! error-message "Could not sign up")}))
                false)]
    (fn []
      [:div 
       [:h2 "Same Day Different Lives"]
       [:h3 "Sign up"]
       [:form {:on-submit signup}
         [bind-fields 
          [:div 
            [:div.row
             [:div.six.columns
              [:label {:for "email"} "Email (kept private)"] 
              [:input.u-full-width {:field :email :id :email :required true}]] 
             [:div.six.columns
              [:label {:for "pseudo"} "Pseudonyme (this will be shown to others)"] 
              [:input.u-full-width {:field :text :id :pseudo :required true}]]] 
            [:div.row
             [:div.six.columns
              [:label {:for "password"} "Password"] 
              [:input.u-full-width {:field :password :id :password :required true}]]
             [:div.six.columns
              [:label {:for "password2"} "Confirm password"] 
              [:input.u-full-width {:field :password :id :password2 :required true}]]]]
          fields]
         [:p 
          [:input.button-primary {:type :submit :value "Sign up"}]]
         [:p.error-message @error-message]]])))

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
      [:div
        [header]
        (if (:error @match-model)
          [:p.error-message (str "Error: " (:error @match-model))]
          (let [{:keys [match challenges]} @match-model
                other-pseudo (find-first-other [(:user-a match) (:user-b match)] 
                                               (:pseudo @user-model))
                showable-challenges (filter #(< (to-ms (:starts-at %)) (js/Date.now)) challenges)
                upcoming-challenges (filter #(> (to-ms (:starts-at %)) (js/Date.now)) challenges)] 
            [:div 
             [:h3 (str "Journal with " other-pseudo)]
             [:p (str "This journal is " (if (:running match) "going on now" "over"))]
             (doall 
               (for [challenge showable-challenges]
                 ^{:key (:challenge-instance-id challenge)} [:div.box.challenge 
                  [:h4 "Challenge: " [:em (:description challenge)]]
                  (when (and (not (responded-to-challenge? challenge)) (active? challenge) (:running match))
                    [:div.row 
                      [:button.button-primary {:on-click #(accountant/navigate! (str "/match/" match-id "/respond/" (:challenge-instance-id challenge)))} "Answer now"]])
                  (if (empty? (:responses challenge))
                    [:div.row
                     [:p "No one has answered"]]
                    (for [response (:responses challenge)]
                      ^{:key (:challenge-response-id response)} [:div.row 
                       [:div {:class "two columns"} 
                        [:div.header (:user response)]]
                       [:div {:class "ten columns"}
                        (if (= "image" (:type challenge))
                          [:img.response-image {:src (str "/uploads/" (:filename response))}]
                          [:audio.response-image {:controls true :src (str "/uploads/" (:filename response))}])]]))]))
             [:div.row.section
              (if (and (:running match) (not-empty upcoming-challenges)) 
                [:h4 (str "Plus " (count upcoming-challenges) " more challenges to come...")]
                [:h4 "No more challenges to come"])]]))])))
       
       
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
