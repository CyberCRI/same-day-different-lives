(ns same-day-different-lives.handler
  (:require [compojure.core :refer [GET POST defroutes routes]]
            [compojure.route :refer [not-found resources files]]
            [hiccup.page :refer [include-js include-css html5]]
            [same-day-different-lives.middleware :refer [wrap-middleware]]
            [same-day-different-lives.util :as util]
            [same-day-different-lives.config :refer [config]]
            [same-day-different-lives.conversion :refer [convert-file]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.defaults :refer [site-defaults api-defaults wrap-defaults]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.util.response :refer [response status content-type]]
            [clojure.java.jdbc :as jdbc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [crypto.password.pbkdf2 :as password]
            [clj-time.jdbc]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(def db (merge (:db config) { :stringtype "unspecified" }))


(defn include-css-version [css-base-filename] 
  (include-css (str "/css/" css-base-filename (if (not (config :dev)) ".min") ".css")))


;;; WEBSITE

(def mount-target
  [:div#app "Loading..."])

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css-version "normalize")
   (include-css-version "skeleton")
   (include-css-version "site")])

(def loading-page
  (html5
    (head)
    [:body 
     [:div.container 
       mount-target
       (include-js "/js/app.js")]]))


;;; API

(defn make-error-response [msg] {:status 400 :body {:code 400 :error msg}})

; Must be AFTER wrap-json-body to work
(defn wrap-keywordize [next] (fn [request] (next (keywordize-keys request))))

(defn temp-name [len] 
  (apply str (repeatedly len #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyz"))))

(defn create-file [request]
  "Returns the name of the created file"
  (let [{{{tempfile :tempfile content-type :content-type} :file} :params :as params} request
        [file-category original-extension] (string/split content-type (re-pattern "/"))]
    (if (and (= file-category "audio") (not= original-extension "mp3"))
      ; Convert to MP3
      (let [new-filename (str (temp-name 20) ".mp3")]
        ; TODO: check for failure
        (convert-file (.getPath tempfile) (str "uploads/" new-filename))
        { :filename new-filename :mime-type "audio/mp3"})
      ; No need to convert
      (let [new-filename (str (temp-name 20) "extension")]
        (io/copy tempfile (io/file (str "uploads/" new-filename)))
        { :filename new-filename :mime-type content-type}))))

(defn create-user [request]
  (let [{:keys [pseudo email password]} (:body request)]
   (if-not (and pseudo email password) 
     (make-error-response "Pseudo, email, and password are required")
     (do
      (jdbc/insert! db :users { :pseudo pseudo :email email :password (password/encrypt password) })
      (response {})))))

(defn login [request]
  (let [{:keys [email password]} (keywordize-keys (:body request))]
   (if-not (and email password) 
     (make-error-response "Email and password are required")
     (do 
       (let [[{:keys [user_id pseudo email password-hash status]}] 
             (jdbc/query db ["select user_id, pseudo, email, password as \"password-hash\", status from users
                              where email = ?" email])]
         (if (or (nil? user_id) (not (password/check password password-hash)))
           (make-error-response "Email or password doesn't match")
           (-> (response {:user_id user_id} )
               (assoc :session {:user-id user_id} ))))))))

(defn wrap-require-user [handler]
  "Require user-id in session or return error.
  Enrich request with :user map."
  (fn [request]
    (if-let [user-id (-> request :session :user-id)]
      (let [[{:keys [pseudo email status]}] (jdbc/query db ["select pseudo, email, status 
                                                            from users
                                                            where user_id = ?" user-id])] 
        (-> request
          (assoc :user {:user-id user-id :email email :pseudo pseudo :status status})       
          (handler)))
      (make-error-response "Access denied"))))
  
(defn get-active-match-for-user [user-id]
  (first (jdbc/query db ["select match_id, user_a, user_b 
                          from matches 
                          where running 
                            and (user_a = ? or user_b = ?)"
                          user-id user-id]
          {:row-fn (fn [{:keys [match_id user_a user_b]}] {:match-id match_id :user-a user_a :user-b user_b})})))

(defn obtain-active-match [{session :session}]
  (response (get-active-match-for-user (:user-id session))))  

(defn get-user-info [{user :user}] 
  (response user))

(defn get-user-pseudo [user-id]
  (first (jdbc/query db ["select pseudo from users where user_id = ?" user-id]
    {:row-fn :pseudo})))
 
(defn alter-user-info [{:keys [body user]}] 
  (let [new-user (merge user (select-keys body [:pseudo :status]))]
    (when (not= (:status new-user) (:status user))
      ; Stop any matches going on
      (when-let [{:keys [match-id user-a user-b]} (get-active-match-for-user (:user-id user))]
        ; Set match to stopped
        (prn "stopping match" match-id "for users" user-a user-b "by request of user" (:user-id user))
        (jdbc/update! db :matches {:running false} ["match_id = ?" match-id])
        ; Change other user's status
        (jdbc/update! db :users { :status "ready" } ["user_id = ?" (util/find-first-other [user-a user-b] (:user-id user))])))
    (jdbc/execute! db ["update users set pseudo = ?, status = cast(? as user_status) where user_id = ?" 
                       (:pseudo new-user) (:status new-user) (:user-id new-user)])
    (-> (response new-user))))
 
(defn logout [request]
  (-> (response {})
      (assoc :session nil)))


(defn get-matches-for-user [user-id]
  (let [matches (jdbc/query db ["select match_id, user_a, user_b, running, starts_at
                                from matches 
                                where(user_a = ? or user_b = ?)
                                order by starts_at DESC"
                                user-id user-id]
                {:row-fn (fn [{:keys [match_id user_a user_b running starts_at]}] {:match-id match_id :user-a user_a :user-b user_b :running running :starts_at (.toString starts_at)})})]
    (for [match matches] 
      (assoc match :other-pseudo (get-user-pseudo (util/find-first-other [(:user-a match) (:user-b match)] user-id))))))

(defn get-active-challenge-for-user [user-id]
  (let [[{:keys [challenge_instance_id type description]}]
        (jdbc/query db ["select challenge_instance_id, type, description 
                        from challenge_instances, challenges, matches 
                        where challenge_instances.starts_at < now() and challenge_instances.ends_at > now() 
                          and challenges.challenge_id = challenge_instances.challenge_id
                          and challenge_instances.match_id = matches.match_id
                          and (matches.user_a = ? or matches.user_b = ?)"
                        user-id user-id])]
        {:challenge-instance-id challenge_instance_id :type type :description description}))

(defn get-challenge-responses [challenge-instance-id]
  (jdbc/query db ["select challenge_response_id, users.pseudo, challenge_responses.filename, challenge_responses.mime_type, challenge_responses.created_at 
                  from challenge_responses, users 
                  where challenge_responses.user_id = users.user_id
                    and challenge_instance_id = ?"
                  challenge-instance-id]
              {:row-fn (fn [{:keys [challenge_response_id pseudo filename mime_type created_at]}]
                        {:challenge-response-id challenge_response_id
                         :user pseudo 
                         :filename filename 
                         :mime-type mime_type 
                         :created-at (.toString created_at)})}))

(defn get-challenges-in-match [match-id]
  (let [challenges (jdbc/query db ["select challenge_instance_id, type, description, challenge_instances.starts_at, challenge_instances.ends_at
                                    from challenge_instances, challenges 
                                    where challenges.challenge_id = challenge_instances.challenge_id
                                      and challenge_instances.match_id = ?
                                    order by challenge_instances.starts_at desc"
                                    match-id]
                               {:row-fn (fn [{:keys [challenge_instance_id type description starts_at ends_at]}]
                                         {:challenge-instance-id challenge_instance_id 
                                          :type type 
                                          :description description 
                                          :starts-at (.toString starts_at) 
                                          :ends-at (.toString ends_at)})})]
    ; For each challenge, associate any responses
    (for [challenge challenges]
      (assoc challenge :responses (get-challenge-responses (:challenge-instance-id challenge))))))

(defn get-challenge-instance [challenge-instance-id]
  (first (jdbc/query db ["select challenge_instances.challenge_instance_id, challenges.challenge_id, challenges.type, challenges.description, challenge_instances.starts_at, challenge_instances.ends_at
                          from challenge_instances, challenges 
                          where challenges.challenge_id = challenge_instances.challenge_id
                            and challenge_instances.challenge_instance_id = ?"
                          challenge-instance-id]
           {:row-fn (fn [{:keys [challenge_instance_id challenge_id type description starts_at ends_at]}]
                       {:challenge-instance-id challenge_instance_id 
                        :challenge-id challenge_id
                        :type type 
                        :description description 
                        :starts-at (.toString starts_at) 
                        :ends-at (.toString ends_at)})})))

(defn submit-challenge-response [user-id challenge-instance-id filename mime-type]
  (jdbc/insert! db :challenge_responses {:user_id user-id 
                                         :challenge_instance_id challenge-instance-id
                                         :filename filename
                                         :mime_type mime-type}))

(defn get-match-info [match-id]
  (let [match (first (jdbc/query db ["select user_a, user_b, created_at, starts_at, ends_at, running 
                                      from matches
                                      where match_id = ?"
                              match-id]
                          {:row-fn (fn [{:keys [user_a user_b created_at starts_at ends_at running]}]
                                         {:user-a user_a 
                                          :user-b user_b 
                                          :created-at (.toString created_at) 
                                          :starts-at (.toString starts_at) 
                                          :ends-at (.toString ends_at)
                                          :running running})}))
        pseudo-a (get-user-pseudo (:user-a match))
        pseudo-b (get-user-pseudo (:user-b match))]
    (assoc match :user-a pseudo-a :user-b pseudo-b)))
                          
                          
(defn obtain-active-challenge [{session :session}]
  (response (get-active-challenge-for-user (:user-id session))))  

(defn can-access-match [user-id match-id]
  (not-empty (jdbc/query db ["select match_id 
                              from matches 
                              where match_id = ? 
                                and (user_a = ? or user_b = ?)"
                             match-id user-id user-id])))

(defn obtain-matches-for-user [{:keys [session]}]
  (response (get-matches-for-user (:user-id session))))

(defn obtain-match-history [{:keys [session params]}]
  (if-not (can-access-match (:user-id session) (:match-id params))
    (make-error-response "Cannot access that match")
    (response {:challenges (get-challenges-in-match (:match-id params))
               :match (get-match-info (:match-id params))})))

(defn can-access-challenge-instance [user-id challenge-instance-id]
  (not-empty (jdbc/query db ["select challenge_instances.challenge_instance_id
                              from challenge_instances, challenges, matches 
                              where challenges.challenge_id = challenge_instances.challenge_id
                                and challenge_instances.match_id = matches.match_id
                                and challenge_instances.challenge_instance_id = ?
                                and (matches.user_a = ? or matches.user_b = ?)"
                             challenge-instance-id user-id user-id])))

(defn obtain-challenge-instance [{:keys [session params]}]
  (if-not (can-access-challenge-instance (:user-id session) (:challenge-instance-id params))
    (make-error-response "Cannot access that challenge instance")
    (response (get-challenge-instance (:challenge-instance-id params)))))

(defn post-challenge-response [request]
  ; TODO: check that we are allowed to submit
  (let [{:keys [session params]} request 
        {:keys [filename mime-type]} (create-file request)]
    (if-not (can-access-challenge-instance (:user-id session) (:challenge-instance-id params))
      (make-error-response "Cannot access that challenge instance")
      (do
        (submit-challenge-response 
          (:user-id session) 
          (:challenge-instance-id params) 
          filename 
          mime-type)
        (response {:filename filename})))))


;;; ROUTES

(def cljs-urls ["/" "/record" "/login" "/signup" "/match/:match-id" "/match/:match-id/respond/:challenge-instance-id"])

(def site-routes (apply routes (for [url cljs-urls] (GET url [] loading-page))))

(defroutes api-routes 
  (POST "/api/users" [] (-> create-user wrap-keywordize wrap-json-body wrap-json-response))

  (GET "/api/me" [] (-> get-user-info wrap-keywordize wrap-require-user wrap-json-body wrap-json-response))
  (POST "/api/me" [] (-> alter-user-info wrap-keywordize wrap-require-user wrap-json-body wrap-json-response))

  (GET "/api/me/match" [] (-> obtain-active-match wrap-require-user wrap-json-body wrap-json-response))
  (GET "/api/me/matches" [] (-> obtain-matches-for-user wrap-require-user wrap-json-body wrap-json-response))
  (GET "/api/match/:match-id" [] (-> obtain-match-history wrap-require-user wrap-json-body wrap-json-response))

  (GET "/api/challenge-instance/:challenge-instance-id" [] (-> obtain-challenge-instance wrap-require-user wrap-json-body wrap-json-response))
  (POST "/api/challenge-instance/:challenge-instance-id" [] (-> post-challenge-response wrap-require-user wrap-multipart-params wrap-params wrap-json-response))
  
  (POST "/api/login" [] (-> login wrap-json-body wrap-json-response))
  (POST "/api/logout" [] (-> logout wrap-keywordize wrap-json-body wrap-json-response wrap-require-user)))

(defroutes other-routes
  (files "/uploads" {:root "uploads"})
  (resources "/")
  (not-found "Not Found"))

(defroutes all-routes api-routes site-routes other-routes)


;;; APP

(def app (wrap-middleware all-routes (assoc-in site-defaults [:security :anti-forgery] false)))
