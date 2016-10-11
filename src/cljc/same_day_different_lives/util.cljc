(ns same-day-different-lives.util)

(defn foo-cljc [x]
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(defn find-first-other [col value]
  ; Returns the first item in col that is not equal to val
  (first (filter #(not= % value) col))) 

(defn pluck [col & keys]
  "Similar to Underscore's pluck()"
  (for [row col] 
    (for [key keys] (get row key))))

(defn describe-notification [notification]
  (condp = (:type notification)
    :new-response {:text "The other player has answered the question"
                   :link (str "/match/" (:match-id notification))}
    :unlocked-challenge {:text "There's a new question to answer"
                         :link (str "/match/" (:match-id notification))} 
    :unlocked-quiz {:text "There's a quiz to play"
                    :link (str "/match/" (:match-id notification))} 
    :unlocked-exchange {:text "You can communicate directly with the other player"
                        :link (str "/match/" (:match-id notification))} 
    :new-exchange-message {:text "The other player has sent you a message"
                           :link (str "/match/" (:match-id notification))} 
    :ended-match {:text "Your journal has ended"
                  :link (str "/match/" (:match-id notification))}
    :created-match {:text "You have been paired up to make a new journal"
                    :link (str "/match/" (:match-id notification))}
    (throw  (Error. (str "ERROR unknown notification type" (:type notification))))))
