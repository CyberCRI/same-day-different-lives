(ns same-day-different-lives.handler
  (:require [compojure.core :refer [GET POST defroutes routes]]
            [compojure.route :refer [not-found resources files]]
            [hiccup.page :refer [include-js include-css html5]]
            [same-day-different-lives.middleware :refer [wrap-middleware]]
            [config.core :refer [env]]
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
            [clojure.walk :refer [keywordize-keys stringify-keys]]))

(try
  (def db (edn/read-string (slurp "config.clj")))
  (catch Exception e
    (prn "Can't load config file 'config.clj'. Quitting...")
    (System/exit 1)))


;;; WEBSITE

(def mount-target
  [:div#app "Loading..."])

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1"}]
   (include-css (if (env :dev) "/css/site.css" "/css/site.min.css"))])

(def loading-page
  (html5
    (head)
    [:body {:class "body-container"}
     mount-target
     (include-js "/js/app.js")]))


;;; API

(defn make-error-response [msg] {:status 400 :body {:code 400 :error msg}})

; Must be AFTER wrap-json-body to work
(defn wrap-keywordize [next] (fn [request] (next (keywordize-keys request))))

(defn temp-name [len] 
  (apply str (repeatedly len #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyz"))))

(defn create-file [request]
  (let [{{{tempfile :tempfile content-type :content-type} "file"} :params :as params} request
        extension (last (string/split content-type (re-pattern "/")))
        new-filename (str (temp-name 20) "." extension)]
    ;(prn "received file " params)
    (io/copy tempfile (io/file (str "uploads/" new-filename)))
    (response {})))
  
(defn create-user [request]
  (let [{:keys [pseudo email password]} (:body request)]
   (if-not (and pseudo email password) 
     (make-error-response "Pseudo, email, and password are required")
     ; TODO: encrypt password
     (do
      (jdbc/insert! db :users { :pseudo pseudo :email email :password password })
      (response {})))))

(defn login [request]
  (let [{:keys [email password]} (keywordize-keys (:body request))]
   (if-not (and email password) 
     (make-error-response "Email and password are required")
     (do 
       (let [[{:keys [user_id pseudo]}] 
             (jdbc/query db ["select user_id, pseudo, email from users
                              where email = ? and password = ?" email password])]
         (prn "found user" user_id pseudo email)
         (-> (response {:user_id user_id} )
             (assoc :session {:user-id user_id :pseudo pseudo :email email} )))))))

; Require user in session or return error  
(defn wrap-require-user [handler]
  (fn [request]
    (prn "wrap-require-user session" (:session request))
    (if (-> request :session :user-id)
      (handler request)
      (make-error-response "Access denied"))))
  
(defn user-info [{session :session}] 
  (response session))
 
(defn logout [request]
  (prn "logout called")
  (-> (response {})
      (assoc :session nil)))

(defn session-check [request]
  (prn "session-check before" (:session request))
  (let [session (:session request)
        count   (:count session 0)
        session (assoc session :count (inc count))]
    (prn "session-check after" session)
    (-> (response (str "You accessed this page " count " times."))
        (content-type "text/plain")
        (assoc :session session))))


;;; ROUTES

(def cljs-urls ["/" "/record" "/login" "/signup"])

(def site-routes (apply routes (for [url cljs-urls] (GET url [] loading-page))))

(defroutes api-routes 
  (POST "/api/files" [] (-> create-file wrap-multipart-params wrap-params wrap-json-response))
  
  (POST "/api/users" [] (-> create-user wrap-keywordize wrap-json-body wrap-json-response))

  (GET "/api/test" [] (-> session-check wrap-json-body wrap-json-response))

  (GET "/api/me" [] (-> user-info wrap-keywordize wrap-require-user wrap-json-body wrap-json-response))
  (POST "/api/login" [] (-> login wrap-json-body wrap-json-response))
  (POST "/api/logout" [] (-> logout wrap-keywordize wrap-json-body wrap-json-response wrap-require-user)))

(defroutes other-routes
  (files "/uploads" {:root "uploads"})
  (resources "/")
  (not-found "Not Found"))

(defroutes all-routes api-routes site-routes other-routes)


;;; APP

(def app (wrap-middleware all-routes (assoc-in site-defaults [:security :anti-forgery] false)))
