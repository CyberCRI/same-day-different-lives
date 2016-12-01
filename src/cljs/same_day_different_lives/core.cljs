(ns same-day-different-lives.core
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    (:require [same-day-different-lives.util :as util]
              [reagent.core :as reagent :refer [atom create-class]]
              [reagent.session :as session]
              [secretary.core :as secretary :include-macros true]
              [accountant.core :as accountant]
              [reagent-forms.core :refer [bind-fields]]
              [ajax.core :refer [GET POST]]
              [clojure.string :as string]
              [clojure.walk :refer [keywordize-keys stringify-keys]]
              [cljs-http.client :as http]
              [cljs.core.async :refer [<! >! chan mult tap untap]]
              [clojure.walk :as walk]
              [cljsjs.moment]))

;; -------------------------
;; Data

(defonce user-model (atom nil))

(defonce ws-connection (atom nil))

(defonce notifications (atom []))

(defonce notification-chan (chan))

(defonce notification-mult (mult notification-chan))


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

(defn get-stats []
  "Returns a promise chan on which it will put /api/stats"
  (let [promise (chan 1)
        stats-chan (http/get "/api/stats")]
    (go 
      (>! promise (:body (<! stats-chan))))
    promise))

(defn find-first-other [col value]
 ; Returns the first item in col that is not equal to val
 (first (filter #(not= % value) col))) 

(defn get-selected-file [] (aget (.getElementById js/document "file-input") "files" 0))

(defn submit-file [match-id challenge-instance-id caption] 
  "Returns [response-chan progress-chan]"
  (let [file (get-selected-file)
        progress-chan (chan)
        response-chan (http/post (str "/api/challenge-instance/" challenge-instance-id)
                                  {:multipart-params [["file" file] ["caption" (or caption "")]]
                                   :progress progress-chan})]
    [response-chan progress-chan]))

(defn confirm [f text]
  (when (js/confirm text) (f)))

(defn format-percent [x] (str (.floor js/Math (* x 100)) "%"))

(defn on-ios? []
  (re-find #"(iPad|iPhone|iPod)" (.-userAgent js/navigator)))

;; -------------------------
;; Views

(defn button-link [href text]
  [:button {:on-click #(accountant/navigate! href)} text])

(defn remove-notification [notification-id]
  (swap! notifications (fn [col] (remove #(= (:id %1) notification-id) col))))

(defn alerts []
  [:div 
    (for [notification @notifications]
      (let [prepared-notification (util/describe-notification notification)]
        ^{:key (:id notification)} [:div.row 
          [:div.twelve.columns.box.alert 
            [:a {:href (:link prepared-notification) 
                 :on-click #(remove-notification (:id notification))} 
             (:text prepared-notification)] 
            [:a.cancel {:href "" :on-click #(remove-notification (:id notification))} "X"]]]))])

(defn header []
  [:div
    [:div.row 
      [:div.six.columns [:h2 "Same Day"]]
      [:div.six.columns.flip [:h2 "Different Lives"]]]
    [:div.row
      [:div.two.columns [:a {:href "/"} "Home"]]]
    [alerts]])

(defn header-with-login []
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
          [button-link "/signup" "sign up"]])]
    [alerts]])

(defn respond-page [match-id challenge-instance-id]
  (let [challenge-instance-model (atom nil)
        fields (atom {})
        file-selected? (atom false)
        upload-in-progress? (atom false)
        upload-progress (atom nil)
        error-message (atom nil)
        handle-submit (fn [] 
                        (when (not @upload-in-progress?)
                          (reset! upload-in-progress? true)
                          (let [[response-chan progress-chan] (submit-file match-id challenge-instance-id (:caption @fields))]
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
        [header-with-login]
        (if (:error @challenge-instance-model)
          [:p.error-message (str "Error: " (:error @challenge-instance-model))]
          ; On iOS, accept videos instead of audio recordings
          (let [accept-mime-family (if (and (= "audio" (:type @challenge-instance-model)) (on-ios?))
                                     "video" 
                                     (:type @challenge-instance-model))]
            [:div
              [:h3 (:description @challenge-instance-model)]
              [:p (str "Answer with a " (if (= "audio" (:type @challenge-instance-model)) "recording" "picture"))]
              (when (on-ios?)
               [:p "On iOS, record a video of yourself talking. It will be converted into an audio file."])
              [:div.row
                [:input {:type :file 
                         :id :file-input 
                         :accept (str (:type @challenge-instance-model) "/*")
                         :on-change #(reset! file-selected? (get-selected-file))}]] 
              [bind-fields
                [:div.row
                  [:input.caption {:field :text :id :caption :placeholder "Caption (optional)"}]] 
                fields]                
              [:p 
                (when @file-selected?
                  [:button.button-primary {:on-click handle-submit} 
                                          (if @upload-in-progress? (str "Sending... " @upload-progress) "Send")])]
              [:div.row 
                [:p.error-message @error-message]]]))])))

(defn home-page [] 
  (let [local-notif-chan (chan)
        match-model (atom nil)
        all-matches-model (atom nil)
        stats-model (atom nil)
        load-data (fn [] 
                    (get-active-match match-model)
                    (get-matches all-matches-model))]
    (go
      (reset! stats-model (<! (get-stats))))
    (load-data)
    (tap notification-mult local-notif-chan)
    (go-loop []
      (let [notification (<! local-notif-chan)]
        ; Reload
        (prn "Reloading due to notification")
        (load-data)
        (recur)))
    (create-class 
      {:component-will-unmount #(untap notification-mult local-notif-chan)
       :reagent-render
        (fn [] 
          [:div
           [header-with-login]
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
            (let [past-matches (filter #(= (:status %1) "over") @all-matches-model)]
              [:div 
               [:h3 "Past Journals"]
               (if (empty? past-matches)
                [:p "No past journals"]
                (for [{:keys [match-id starts_at other-pseudo]} past-matches]
                 ^{:key match-id} [:div.row 
                  [:div.six.columns (str "Started " (-> (js/moment (str starts_at)) (.format "MMMM Do YYYY")))]
                  [:div.six.columns 
                   [:a {:href (str "/match/" match-id)} (str "Journal with " other-pseudo)]]]))]))
           (when @stats-model 
             (let [{users :users {:keys [challenge quiz exchange over total]} :matches} @stats-model
                   active-matches (- total over)]
               [:p.statistics (str "Statistics: there are " users " users and " total " journals, of which " active-matches " are ongoing and " over " are over. " 
                                    "Right now " challenge " pairs of players are answering questions, "
                                    quiz " are doing a quiz, and " exchange " are exchanging directly.")])) 
])})))

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
       [header]
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

(defn make-select-box [id option-pairs]
  ; concat is needed here to "splice" the dynamic options in the :select vector 
  (concat [:select.u-full-width {:field :list :id id :required true }]
   ^{:key 0} [[:option]]
   (for [[k v] option-pairs]
     ^{:key k} [:option {:key k} v])))

(defn put-other-at-end [list-of-pairs]
  "Re-order list to put the one with value 'Other' (key = 1) at the end"
  (sort (fn [[k1 _] [k2 _]] (if (= k1 1) 1 (if (= k2 1) -1 0))) list-of-pairs))

(defn get-lists []
  "Returns a promise chan on which it will put a map of :religions, :regions, and :education-levels"
  (let [promise (chan 1)
        religions-chan (http/get "/api/religions")
        regions-chan (http/get "/api/regions")
        education-levels-chan (http/get "/api/educationLevels")]
    (go 
      (>! promise (-> {} 
          (assoc 
            :religions (-> (<! religions-chan) :body (util/pluck :religion-id :religion-name) put-other-at-end)
            :regions (-> (<! regions-chan) :body (util/pluck :region-id :region-name) put-other-at-end)
            :education-levels (-> (<! education-levels-chan) :body (util/pluck :education-level-id :education-level-name) put-other-at-end)))))
    promise))

(defn signup-page [] 
  (let [fields (atom {})
        data-lists (atom nil)
        error-message (atom nil)
        new-pseudo (atom nil)
        signup (fn [e] 
                (if (not= (:password @fields) (:password2 @fields)) 
                  (reset! error-message "Passwords don't match")
                  (POST "/api/users" 
                    {:params @fields 
                     :format :json 
                     :handler (fn [response]
                                (prn "Got response" response) 
                                (reset! new-pseudo (-> response keywordize-keys :pseudo)))
                     :error-handler #(reset! error-message (str "Could not sign up. " (:response %1)))}))
                (.preventDefault e))]
    (go
      (reset! data-lists (<! (get-lists))))
    (fn []
      [:div 
       [header]
       [:h3 "Sign up"]
       (if-not @data-lists   
         [:div "Loading..."]
         (if @new-pseudo 
           [:div
             [:p [:strong "Account Created"]]
             [:p "To hide your identity, we have randomly chosen the name " 
              [:strong @new-pseudo] 
              " for you."]
             [:button.button-primary {:on-click #(accountant/navigate! "/login")} "Go to login page"]]
           [:form {:on-submit signup}
             [bind-fields 
              [:div 
                [:div.row
                 [:div.six.columns
                  [:label {:for "email"} "Email (kept private)"] 
                  [:input.u-full-width {:field :email :id :email :required true}]]]
                [:div.row
                 [:div.six.columns
                  [:label {:for "password"} "Password"] 
                  [:input.u-full-width {:field :password :id :password :required true}]]
                 [:div.six.columns
                  [:label {:for "password2"} "Confirm password"] 
                  [:input.u-full-width {:field :password :id :password2 :required true}]]]
                [:div.row
                 [:h4.u-full-width "About you"]
                 [:p.u-full-width "This information is used to pair you up with other people."]]
                [:div.row
                 [:div.six.columns
                  [:label {:for "gender"} "Gender"] 
                  (make-select-box :gender {:male "Male" :female "Female" :other "Other"})]
                 [:div.six.columns
                  [:label {:for "birth-year"} "Year of birth"] 
                  [:input.u-full-width {:field :numeric :type :number :id :birth-year :required true :min 1900 :max (.getFullYear (js/Date.))}]]]
                [:div.row
                 [:div.six.columns
                  [:label {:for "religion"} "Religion"] 
                  (make-select-box :religion-id (:religions @data-lists))]
                 [:div.six.columns
                  [:label {:for "regions"} "Region"] 
                  (make-select-box :region-id (:regions @data-lists))]]           
                [:div.row
                 [:div.six.columns
                  [:label {:for "skin-color"} "Skin color"] 
                  (make-select-box :skin-color {:dark "Dark" :in-between "In Between" :light "Light"})]
                 [:div.six.columns
                  [:label {:for "regions"} "Education level"] 
                  (make-select-box :education-level-id (:education-levels @data-lists))]]           
                [:div.row
                 [:div.six.columns
                  [:label {:for "politics-social"} "Politics on a social dimension"] 
                  (make-select-box :politics-social {:liberal "Liberal" :moderate "Moderate" :conservative "Conservative"})]
                 [:div.six.columns
                  [:label {:for "regions"} "Politics on a economic dimension"] 
                  (make-select-box :politics-economics {:liberal "Liberal" :moderate "Moderate" :conservative "Conservative"})]]]
              fields]
             [:p 
              [:input.button-primary {:type :submit :value "Sign up"}]]
             [:p.error-message @error-message]]))])))

(defn to-ms [date-string] (.getTime (new js/Date date-string)))

(defn format-from-now [date] (.fromNow (js/moment date)))

(defn active? [challenge]
  (let [{:keys [starts-at ends-at]} challenge]
    (and 
      (< (to-ms starts-at) (js/Date.now))
      (> (to-ms ends-at) (js/Date.now)))))

(defn responded-to-challenge? [challenge]
  (let [responses (:responses challenge)]
    (not-empty (filter #(= (:user %) (:pseudo @user-model)) responses))))

(defn capitalize [s] 
  (if s (string/capitalize s) s))

(def standard-quiz-criteria [:gender :religion-id :region-id :skin-color :education-level-id :politics-social :politics-economics])

(defn match-page [match-id]
  (let [local-notif-chan (chan)
        match-model (atom nil)
        load-data (fn [] (get-match-model match-id match-model))
        lightbox-img (atom nil)
        data-lists (atom nil)
        submit-in-progress (atom false)
        exchange-error-message (atom nil)
        send-message (fn [e] 
                       (.preventDefault e)
                       (when-not @submit-in-progress
                         (let [message (.-value (.getElementById js/document "message"))]
                           (prn "Sending message" message)
                           (go 
                             (let [response (<! (http/post (str "/api/exchanges/" match-id) {:json-params {:message message}}))]
                              (if (= :no-error (:error-code response))
                                (do 
                                  ; Clear message input box
                                  (set! (.-value (.getElementById js/document "message")) "")
                                  (load-data))
                                (reset! exchange-error-message (:body response))))
                              (reset! submit-in-progress false)))))
        form-data (atom {})
        quiz-error-message (atom nil)
        response-in-progress (atom false)
        make-guess (fn [] 
                    (when-not @response-in-progress
                      (reset! response-in-progress true)
                      (go
                        (let [response (<! (http/post (str "/api/quiz/" match-id) {:json-params @form-data}))]
                          (if (= :no-error (:error-code response))
                            (load-data)
                            (reset! quiz-error-message (:body response)))
                          (reset! response-in-progress false)))))]
    (load-data)
    (tap notification-mult local-notif-chan)
    (go-loop []
      (let [notification (<! local-notif-chan)]
        ; Reload
        (prn "Reloading due to notification")
        (load-data)
        (recur)))
    (go
      (reset! data-lists (<! (get-lists))))
    (create-class 
      {:component-will-unmount #(untap notification-mult local-notif-chan)
       :reagent-render
        (fn []
          [:div
            [header-with-login]
            (if-not (and @data-lists @match-model)
              [:p "Loading..."]
              (if (:error @match-model)
                [:p.error-message (str "Error: " (:error @match-model))]
                (let [{:keys [match challenges quiz-responses other-user-info exchanges]} @match-model
                      other-pseudo (find-first-other [(:user-a match) (:user-b match)] 
                                                     (:pseudo @user-model))
                      showable-challenges (filter #(< (to-ms (:starts-at %)) (js/Date.now)) challenges)
                      upcoming-challenges (filter #(> (to-ms (:starts-at %)) (js/Date.now)) challenges)
                      own-quiz-response (first (filter #(= (:user-id %) (:user-id @user-model)) quiz-responses))
                      correct-answer? (fn [criteria] (and (criteria own-quiz-response) 
                                                          (= (criteria own-quiz-response) (criteria other-user-info))))
                      birth-year-correct-answer? (and (:birth-year own-quiz-response) 
                                                      (<= (js/Math.abs (- (:birth-year own-quiz-response) (:birth-year other-user-info))) 5))
                      answer-class (fn [b] (if b "correct-answer" "wrong-answer"))
                      lookup-list-value (fn [criteria value] (-> (filter #(= (first %) value) (criteria @data-lists)) 
                                                                 first 
                                                                 second))
                      correct-answer-count (+ (->> standard-quiz-criteria 
                                                (map correct-answer?)
                                                (filter true?)
                                                (count))
                                              (if birth-year-correct-answer? 1 0))]
                  [:div 
                   ; Lightbox (only visible when lightbox-img contains a value)
                   [:div.lightbox {:class (if @lightbox-img "visible" nil) :on-click #(reset! lightbox-img nil)}
                    [:img {:src @lightbox-img}]]
                   
                   [:h3 (str "Journal with " other-pseudo)]
                   [:p (str "This journal started " (format-from-now (:starts-at match)) 
                            (if (not= (:status match) "over") " and ends " " and ended ")            
                            (format-from-now (:ends-at match)) ".")]
                   (when (or (not-empty exchanges) (= (:status match) "exchange"))
                     [:div
                      [:h4 "Exchange"]
                      (if (empty? exchanges)
                        [:p [:em (if (= (:status match) "exchange") "You can say something to the other player" "No exchanges")]])
                      (when (= (:status match) "exchange")
                        [:form {:on-submit send-message}
                          [:div.row
                           [:div.ten.columns
                            [:input.u-full-width {:type :text :id :message :required true :placeholder "Write your message here..."}]]
                           [:div.two.columns
                            [:input.button-primary {:type :submit :value "Send"}]]
                          [:div.row 
                            [:p.error-message @exchange-error-message]]]])
                      (doall 
                        (for [exchange exchanges]
                          ^{:key (:exchange-id exchange)} [:div.box.vertical-space
                           [:div.row 
                             [:div.three.columns (format-from-now (:created-at exchange))]
                             [:div.two.columns 
                              [:div.header (if (= (:user-id exchange) (:user-id @user-model)) 
                                             (:pseudo @user-model) 
                                             (:pseudo other-user-info))] ]
                             [:div.seven.columns 
                              [:em (:message exchange)]]]]))])
                   (when own-quiz-response
                     [:div
                      [:h4 "Quiz Results"]
                      [:p "Let's see how close your guess was:"]
                      [:table.u-full-width
                       [:thead
                        [:tr
                         [:th "Criteria"]
                         [:th "Your Guess"]
                         [:th "Correct Answer"]]]
                       [:tbody
                        [:tr 
                         [:td "Gender"]
                         [:td {:class (answer-class (correct-answer? :gender))} (capitalize (:gender own-quiz-response))]
                         [:td (capitalize (:gender other-user-info))]]
                        [:tr 
                         [:td "Birth year"]
                         [:td {:class (answer-class birth-year-correct-answer?)} 
                              (:birth-year own-quiz-response)]
                         [:td (:birth-year other-user-info)]]
                        [:tr 
                         [:td "Religion"]
                         [:td {:class (answer-class (correct-answer? :religion-id))} (lookup-list-value :religions (:religion-id own-quiz-response))]
                         [:td (lookup-list-value :religions (:religion-id other-user-info))]]
                        [:tr 
                         [:td "Region"]
                         [:td {:class (answer-class (correct-answer? :region-id))} (lookup-list-value :regions (:region-id own-quiz-response))]
                         [:td (lookup-list-value :regions (:region-id other-user-info))]]
                        [:tr 
                         [:td "Skin color"]
                         [:td {:class (answer-class (correct-answer? :skin-color))} (capitalize (:skin-color own-quiz-response))]
                         [:td (capitalize (:skin-color other-user-info))]]
                        [:tr 
                         [:td "Education level"]
                         [:td {:class (answer-class (correct-answer? :education-level-id))} (lookup-list-value :education-levels (:education-level-id own-quiz-response))]
                         [:td (lookup-list-value :education-levels (:education-level-id other-user-info))]]
                        [:tr 
                         [:td "Politics on a social dimension"]
                         [:td {:class (answer-class (correct-answer? :politics-social))} (capitalize (:politics-social own-quiz-response))]
                         [:td (capitalize (:politics-social other-user-info))]]
                        [:tr 
                         [:td "Politics on an economic dimension"]
                         [:td {:class (answer-class (correct-answer? :politics-economics))} (capitalize (:politics-economics own-quiz-response))]
                         [:td (capitalize (:politics-economics other-user-info))]]]]
                      [:div.row
                       [:p
                        [:strong (str "You made " correct-answer-count " correct guesses out of 8")]]]])
                   (when (and (not own-quiz-response) (= (:status match) "quiz"))
                     [:div 
                      [:h4 "Quiz"]
                      [:p "Try to guess the demographic information about your journal partner."]
                      [bind-fields
                       [:div
                         [:div.row
                           [:div.six.columns
                            [:label {:for "gender"} "Gender"] 
                            (make-select-box :gender {:male "Male" :female "Female" :other "Other"})]
                           [:div.six.columns
                            [:label {:for "birth-year"} "Year of birth"] 
                            [:input.u-full-width {:field :numeric :type :number :id :birth-year :required true :min 1900 :max (.getFullYear (js/Date.))}]]]
                          [:div.row
                           [:div.six.columns
                            [:label {:for "religion"} "Religion"] 
                            (make-select-box :religion-id (:religions @data-lists))]
                           [:div.six.columns
                            [:label {:for "regions"} "Region"] 
                            (make-select-box :region-id (:regions @data-lists))]]           
                          [:div.row
                           [:div.six.columns
                            [:label {:for "skin-color"} "Skin color"] 
                            (make-select-box :skin-color {:dark "Dark" :in-between "In Between" :light "Light"})]
                           [:div.six.columns
                            [:label {:for "regions"} "Education level"] 
                            (make-select-box :education-level-id (:education-levels @data-lists))]]           
                          [:div.row
                           [:div.six.columns
                            [:label {:for "politics-social"} "Politics on a social dimension"] 
                            (make-select-box :politics-social {:liberal "Liberal" :moderate "Moderate" :conservative "Conservative"})]
                           [:div.six.columns
                            [:label {:for "regions"} "Politics on a political dimension"] 
                            (make-select-box :politics-economics {:liberal "Liberal" :moderate "Moderate" :conservative "Conservative"})]]]
                      form-data]
                      [:div.row 
                       [:div.six.columns
                        [:button.button-primary {:on-click make-guess} "Make your guess"]]]
                      [:div.row 
                        [:p.error-message @quiz-error-message]]])
                   [:div
                     [:h4 "Questions"]
                     (doall 
                       (for [challenge showable-challenges]
                         ^{:key (:challenge-instance-id challenge)} [:div.box.challenge 
                          [:h5 [:em (:description challenge)]]
                          [:div.row [:p (-> (js/moment (:starts-at challenge)) (.format "MMMM Do YYYY"))]]
                          (when (and (not= (:status match) "over") (active? challenge)) 
                            [:div.row [:p (str "This question ends " (format-from-now (:ends-at challenge)))]])
                          (when (and (not (responded-to-challenge? challenge)) (active? challenge) (not= (:status match) "over"))
                            [:div.row 
                              [:button.button-primary {:on-click #(accountant/navigate! (str "/match/" match-id "/respond/" (:challenge-instance-id challenge)))} "Answer now"]])
                          (if (empty? (:responses challenge))
                            [:div.row
                             [:p "No one has answered"]]
                            (for [response (:responses challenge)]
                              ^{:key (:challenge-response-id response)} 
                              [:div.response-container
                                [:div.row 
                                 [:div.two.columns 
                                  [:div.header (:user response)]]
                                 [:div.ten.columns.centered 
                                  (if (= "image" (:type challenge))
                                    [:img.response-image {:src (str "/uploads/" (:filename response)) 
                                                          :on-click #(reset! lightbox-img (str "/uploads/" (:filename response)))}]
                                    [:audio.response-image {:controls true :src (str "/uploads/" (:filename response))}])]]
                                (if-let [caption (:caption response)] 
                                  [:div.row 
                                    [:div.twelve.columns.caption caption]])]))]))]
                   [:div.row.section
                    (if (and (not= (:status match) "over") (not-empty upcoming-challenges)) 
                      [:h4 (str "Plus " (count upcoming-challenges) " more questions to come...")]
                      [:h4 "That's it! No more questions coming."])]])))])})))
       
       
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
;; Web socket functions

(def make-id-count (atom 0))
(defn make-id [] 
  (let [old-id @make-id-count]
    (swap! make-id-count inc)
    old-id))

(defn parse-json [json]
  (js->clj (.parse js/JSON json)))

(defn parse-notification [event]
  (-> event
   .-data 
   parse-json
   walk/keywordize-keys
   ; Turn :type value into a keyword
   (update :type keyword) 
   ; Assign arbitrary ID
   (assoc :id (make-id))))

(defn open-ws-connection! []
 (prn "Attempting to connect websocket...")
 (if-let [connection (js/WebSocket. (str "ws://" (.-host js/location) "/ws"))]
   (do
     (set! (.-onmessage connection) (fn [e]
                                (prn "got notification from server" (.-data e))
                                (let [notification (parse-notification e)]
                                  (prn "parsed notification" notification)
                                  ; Add to notifications
                                  (go 
                                    (>! notification-chan notification))
                                  ; Add to list
                                  (swap! notifications conj notification)
                                  ; If a match has created or ended, reload the user-model
                                  (when (contains? #{:created-match :ended-match} (:type notification))
                                     (check-login)))))
     (reset! ws-connection connection)
     (prn "Websocket connection established"))
   (throw (js/Error. "Websocket connection failed!"))))

(defn close-ws-connection! []
  (when @ws-connection
    (.close @ws-connection)
    (reset! ws-connection nil)
    (prn "Closed websocket connection")))
           
(add-watch user-model nil (fn [_ _ old-state new-state]
                            (cond 
                              (and (not old-state) new-state) ; Just logged in
                                (open-ws-connection!)
                              (and old-state (not new-state)) ; Just logged out
                                (do 
                                  (close-ws-connection!)
                                  (reset! notifications [])))))



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
