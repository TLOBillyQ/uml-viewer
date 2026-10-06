(ns uml-viewer.adapters.companion
  (:require [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(defn process!
  "外部进程边界：保留退出状态、两个输出流与超时。"
  ([argv] (process! argv 10000))
  ([argv timeout-ms]
   (try
     (let [p (.start (ProcessBuilder. (into-array String argv)))
           out (future (slurp (.getInputStream p)))
           err (future (slurp (.getErrorStream p)))
           done? (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)]
       (when-not done? (.destroy p))
       {:exit (when done? (.exitValue p))
        :out (if done? (str/trim @out) "")
        :err (if done? (str/trim @err) "process timed out")
        :timeout? (not done?)})
     (catch Exception e {:exit nil :out "" :err (.getMessage e) :timeout? false}))))

(defn checked!
  [run args]
  (let [result (run args)
        result (if (number? result) {:exit result :out "" :err ""} result)]
    (when-not (= 0 (:exit result))
      (throw (ex-info (str "Companion command failed: " (first args) ": " (:err result))
                      (assoc result :args args))))
    result))

(defn shell-word [s]
  (str "'" (str/replace (str s) "'" "'\"'\"'") "'"))

(defprotocol SessionBackend
  (create! [backend session cwd command])
  (probe! [backend session])
  (bind! [backend session pane])
  (wake! [backend pane keys])
  (recovery! [backend session pane cwd command enabled?])
  (cleanup! [backend session]))

(defrecord Tmux [run]
  SessionBackend
  (create! [_ session cwd command]
    (checked! run (into ["new-session" "-d" "-s" session "-c" cwd "-e" "COLORTERM=truecolor"] command)))
  (probe! [_ session]
    (let [r (run ["has-session" "-t" session])
          r (if (number? r) {:exit r} r)]
      (when (:timeout? r)
        (throw (ex-info "Companion session probe timed out" r)))
      (case (:exit r)
        0 true
        1 false
        (throw (ex-info "Could not probe companion session" r)))))
  (bind! [_ session pane]
    (let [out (:out (checked! run ["display-message" "-p" "-t" (or pane (str session ":0.0")) "#{pane_id}"]))]
      (if (seq out) out (or pane (str session ":0.0")))))
  (wake! [_ pane keys]
    (checked! run (into ["send-keys" "-t" pane] keys)))
  (recovery! [_ session pane cwd command enabled?]
    (if enabled?
      (do
        (checked! run ["set-option" "-p" "-t" pane "remain-on-exit" "on"])
        (checked! run ["set-hook" "-t" session "pane-died"
                      (str "respawn-pane -k -t " (shell-word pane)
                           " -c " (shell-word cwd) " -- "
                           (str/join " " (map shell-word command)))])
        (checked! run ["set-option" "-t" session "status" "off"]))
      (checked! run ["set-hook" "-t" session "-u" "pane-died"])))
  (cleanup! [this session]
    (recovery! this session nil nil nil false)
    (checked! run ["kill-session" "-t" session])))

(defn tmux [run] (->Tmux run))
