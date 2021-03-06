;;namespace
(ns witchazzan.comms
  (:require [witchazzan.common :refer :all])
  (:require [witchazzan.behavior :as behavior])
  (:require [org.httpkit.server :as server])
  (:require [clojure.data.json :as json])
  (:require [next.jdbc :as jdbc])
  (:gen-class))
(use '[clojure.java.shell :only [sh]])
;;namespace

(defn message-player [data player]
  (try (server/send! (:socket player) (json/write-str data))
       (catch Exception e (log "error sending data to client") (log e))))

(defn broadcast
  "takes an n-level map and distributes it to all/selected clients as json"
  [data & [players]]
  (run!
   #(message-player data @%)
   (cond players players :else (game-pieces {:type :player}))))

(defn establish-identity
  "comunicates to a client which player object belongs to them"
  [player]
  (message-player {:messageType "identity" :id (:id player)
                   :name (:name player)} player)
  (broadcast {:messageType "chat" :id -1 :name "Witchazzan.core"
              :content (str "Welcome, " (:name player))}))

(defn handle-chat
  "broadcasts chats as json"
  [message channel _]
  (let [player @(first (game-pieces {:socket channel}))
        id (get message "targetPlayerId")
        audience (if id [(one-game-piece id)] (game-pieces {:type :player}))]
    (broadcast  {:messageType "chat" :name (:name player) :id (:id player)
                 :content (get message "text")} audience)))

(defn handle-location-update [message channel _]
  (let [player (first (game-pieces {:socket channel}))]
    (send
     player
     merge
     (apply merge
            (map
             (fn [pair]
               {(keyword (first pair)) (if (= "scene" (first pair)) (keyword (second pair)) (second pair))}) (seq message))))))

(defn handle-login [message channel request]
  (let [db-data (jdbc/execute-one! ds ["select * from users where id = ?;" (:auth (:session request))])
        username (:users/username db-data)
        id (gen-id)
        db-id (:users/id db-data)
        sprite (get message "sprite")
        moving (get message "moving")
        existing-user (first (game-pieces {:type :player :db-id db-id}))
        default-health 100]
    (cond
      (not existing-user)
      (do
        (behavior/add-game-piece!
         {:id id
          :db-id db-id
          :x 0
          :y 0
          :type :player
          :scene :LoruleH8
          :health default-health
          :energy 100
          :active true
          :defence 0
          :sprite sprite
          :name username
          :socket channel
          :milliseconds (System/currentTimeMillis)})
        (establish-identity @(one-game-piece id)))
      :else
      (do
        (send
         existing-user
         merge
         {:milliseconds (System/currentTimeMillis)
          :health default-health
          :energy 100
          :socket channel
          :sprite sprite
          :active true})
        (await existing-user)
        (establish-identity @existing-user)))))

(defn handle-command
  "this handler is a bit of a switch case inside of a switch case,
  it handles all of the text commands entered
  via the command bar on the client"
  [message channel _]
  (let [player @(first (game-pieces {:socket channel}))]
    (when (re-find #"^look" (get message "command"))
      (message-player {:messageType "chat" :name "Witchazzan.core"
                       :content
                       "You see a realm of unimaginable possibility."}
                      player))
    (when (re-find #"^listen" (get message "command"))
      (message-player {:messageType "chat" :name "Witchazzan.core"
                       :content
                       "You hear the distant chatter of a keyboard.
                     A developer is hard at work."}
                      player))
    (when (re-find #"^who" (get message "command"))
      (message-player {:messageType "chat" :name "Witchazzan.core"
                       :content
                       (apply str (map #(str (:name @%) ", ") (active-pieces {:type :player})))}
                      player))
    (when (re-find #"^reload" (get message "command"))
      (require 'witchazzan.common :reload)
      (require 'witchazzan.comms :reload)
      (require 'witchazzan.behavior :reload)
      (require 'witchazzan.world :reload)
      (message-player {:messageType "chat" :name "Witchazzan.core"
                       :content "Reloading source files"} player))
    (when (re-find #"^git-pull" (get message "command"))
      (message-player {:messageType "chat" :name "Witchazzan.core"
                       :content (:out (sh "git" "pull"))} player))
    #_(when (re-find #"^reset" (get message "command"))
        (message-player {:messageType "chat" :name "Witchazzan.core"
                         :content "Deleting save."} player)
        (reset))
    #_(when (re-find #"^save-game" (get message "command"))
        (message-player {:messageType "chat" :name "Witchazzan.core"
                         :content "Saving."} player)
        (save))
    #_(when (re-find #"^load-game" (get message "command"))
        (message-player {:messageType "chat" :name "Witchazzan.core"
                         :content "Loading."} player)
        (load-game))))
