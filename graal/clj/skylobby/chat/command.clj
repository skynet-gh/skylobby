(ns skylobby.chat.command
  (:require
    [clojure.string :as string]
    [skylobby.direct :as direct]
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* false)


(defmulti handle
  (fn [_state-atom _server-key message]
    (-> message
        (string/split #"\s")
        first)))

(defmethod handle :default [_ server-key message]
  (log/warn "No handler for command" (pr-str message) "on server" server-key))

(defmethod handle "!start" [state-atom server-key message]
  (log/info "Starting game from server host chat command" message)
  (direct/start-game state-atom server-key))

(defmethod handle "!stop" [state-atom server-key message]
  (log/info "Stopping game from server host chat command" message)
  (swap! state-atom
    (fn [{:keys [spring-process spring-running spring-starting]}]
      (let [
            some-process (->> (get spring-process server-key)
                              (map second)
                              (filter some?)
                              first)
            some-running (->> (get spring-running server-key)
                              (map second)
                              (filter some?)
                              first)
            some-starting (->> (get spring-starting server-key)
                               (map second)
                               (filter some?)
                               first)]
        (cond
          some-process
          (do
            (log/info "Destroying spring process" some-process "on server" server-key)
            (.destroy some-process))
          some-running (log/error "Spring supposedly running but no process found on server" server-key)
          some-starting (log/warn "Spring has not finished starting on server" server-key)
          :else
          (log/warn "Nothing to do to stop spring process on server" server-key))))))
