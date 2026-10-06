(ns uml-viewer.adapters.companion
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
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
                      (assoc result :args args :status (if (:timeout? result) :timeout :failure)))))
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

(defn encoded [text]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str text) "UTF-16LE")))

(defn- data-string [text]
  (str "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
       (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str text) "UTF-8")) "'))"))

(defn windows-command!
  "Controlled runner: all user data survives fresh/respawn without command parsing."
  [cwd command]
  (let [file (io/file cwd ".uml-viewer" "companion-launch.xml")
        b64 #(.encodeToString (java.util.Base64/getEncoder) (.getBytes (str %) "UTF-8"))
        manifest (str "<launch><cwd>" (b64 cwd) "</cwd><executable>" (b64 (first command))
                      "</executable><argv>" (str/join (map #(str "<arg>" (b64 %) "</arg>") (rest command))) "</argv></launch>")
        runner (str "$ErrorActionPreference='Stop';function D($s){[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string]$s))};"
                    "[xml]$m=[IO.File]::ReadAllText(" (data-string (.getAbsolutePath file)) ");"
                    "Set-Location -LiteralPath (D $m.launch.cwd);$exe=D $m.launch.executable;"
                    "$ext=[IO.Path]::GetExtension($exe).ToLowerInvariant();"
                    "if($ext -notin @('.exe','.ps1')){throw 'Unsafe Claude wrapper. Set CLAUDE_BIN to the native claude.exe or a .ps1 adapter invoking node.exe with the official @anthropic-ai/claude-code/cli.js; .cmd/.bat cannot preserve arbitrary argv.'};"
                    "$PSNativeCommandArgumentPassing='Standard';"
                    "$argv=@($m.launch.argv.arg|ForEach-Object{D $_});& $exe @argv;exit $LASTEXITCODE")]
    (io/make-parents file)
    (spit file manifest)
    ["pwsh.exe" "-NoProfile" "-EncodedCommand" (encoded runner)]))

(def identity-format "UML|#{session_name}|#{session_id}|#{pid}|#{pane_id}")

(defn windows-identity! [run session pane expected]
  (let [r (checked! run ["display-message" "-p" "-t" (or pane (str "=" session ":0.0")) identity-format])
        [_ name sid pid pane-id] (re-matches #"UML\|([^|\r\n]+)\|(\$\d+)\|(\d+)\|(%\d+)" (:out r))
        observed {:session name :session-id sid :server-pid pid :pane (str "=" name ":" pane-id)}]
    (when-not (and (= session name) (or (nil? pane) (= pane (:pane observed)))
                   (or (nil? expected) (= (select-keys expected [:session :session-id :server-pid :pane]) observed)))
      (throw (ex-info "psmux identity probe failed; session absence is not proven" (assoc r :status :failure :expected expected :observed observed))))
    observed))

(defn windows-owner!
  "OS evidence for the recorded server only; PID reuse is never live-owner evidence."
  [identity]
  (let [pid (:server-pid identity)]
    (when-not (and (string? pid) (re-matches #"[1-9][0-9]*" pid))
      (throw (ex-info "No verified Windows server PID" {:status :failure})))
    (let [script (str "$ErrorActionPreference='Stop';try{$p=[Diagnostics.Process]::GetProcessById(" pid
                      ")}catch [ArgumentException]{[Console]::Write('MISSING');exit 0};"
                      "[Console]::Write(('OWNER|{0}|{1}|{2}' -f $p.Id,$p.StartTime.ToUniversalTime().Ticks,$p.ProcessName))")
          r (checked! process! ["pwsh.exe" "-NoProfile" "-EncodedCommand" (encoded script)])
          [_ seen start name] (re-matches #"OWNER\|([1-9][0-9]*)\|([0-9]+)\|([^|\r\n]+)" (:out r))]
      (cond
        (= "MISSING" (:out r)) {:status :missing}
        (and (= pid seen) (:owner-start identity) (not= start (:owner-start identity))) {:status :missing}
        (and (= pid seen) (re-matches #"(?i)(psmux|pmux|tmux)" (or name "")))
        {:status :exists :owner-start start}
        :else (throw (ex-info "Windows owner process probe is ambiguous" (assoc r :status :failure)))))))

(defrecord Psmux [run identity]
  SessionBackend
  (create! [_ session cwd command] (checked! run (into ["new-session" "-d" "-s" session "--"] command)))
  (probe! [_ session] (windows-identity! run session (:pane identity) identity) true)
  (bind! [_ session pane] (:pane (windows-identity! run session pane identity)))
  (wake! [_ pane keys]
    (when-not (and identity (= pane (:pane identity)))
      (throw (ex-info "psmux wake requires verified explicit pane ownership" {:status :failure})))
    (windows-identity! run (:session identity) pane identity)
    (let [before (when (= "-l" (first keys)) (:out (checked! run ["capture-pane" "-p" "-t" pane])))
          result (checked! run (into ["send-keys" "-t" pane] keys))]
      (when (= "-l" (first keys))
        (let [out (:out (checked! run ["capture-pane" "-p" "-t" pane]))]
          (when-not (and (not= before out) (str/includes? out (second keys)))
            (throw (ex-info "psmux wake effect was not observed; mail remains queued" {:status :failure})))))
      (windows-identity! run (:session identity) pane identity)
      result))
  (recovery! [_ session pane cwd command enabled?]
    (windows-identity! run session (or pane (:pane identity)) identity)
    (let [target (str "=" session)
          hook (when enabled? (str "respawn-pane -k -t " pane " -- " (str/join " " command)))]
      (if enabled?
        (do
          (when-not (and (re-matches #"=[A-Za-z0-9_-]+:%\d+" pane)
                         (= 4 (count command))
                         (= "pwsh.exe" (first command))
                         (= ["-NoProfile" "-EncodedCommand"] (subvec (vec command) 1 3))
                         (re-matches #"[A-Za-z0-9+/]+=*" (last command)))
            (throw (ex-info "psmux recovery requires a controlled encoded runner" {:status :failure})))
          (checked! run ["set-option" "-t" target "remain-on-exit" "on"])
          (when-not (= "on" (:out (checked! run ["show-options" "-v" "-t" target "remain-on-exit"])))
            (throw (ex-info "psmux did not retain the death pane" {:status :failure})))
          (checked! run ["set-hook" "-t" target "pane-died" hook]))
        (checked! run ["set-hook" "-t" target "-u" "pane-died"]))
      (let [out (:out (checked! run ["show-hooks" "-t" target]))]
        (when-not (if enabled? (= (str "pane-died -> " hook) out)
                       (and (seq out) (not (str/includes? out "pane-died"))))
          (throw (ex-info "psmux recovery effect could not be verified" {:status :failure :out out}))))
      (windows-identity! run session (or pane (:pane identity)) identity)))
  (cleanup! [this session]
    (when-not (:owner-start identity)
      (throw (ex-info "Windows cleanup requires recorded process creation identity" {:status :failure})))
    (when (= :exists (:status (windows-owner! identity)))
      (recovery! this session (:pane identity) nil nil false)
      (checked! run ["kill-session" "-t" (str "=" session)]))
    (let [deadline (+ (System/nanoTime) 2000000000)]
      (loop []
        (if (= :missing (:status (windows-owner! identity)))
          true
          (if (< (System/nanoTime) deadline)
            (do (Thread/sleep 50) (recur))
            (throw (ex-info "Windows companion owner did not exit after cleanup" {:status :timeout :session session}))))))))

(defn psmux [run identity]
  (let [r (checked! run ["-V"])]
    (when-not (re-matches #"tmux 3\.3\.8\r?\npsmux 3\.3\.8(?: \([^\r\n]+\))?" (:out r))
      (throw (ex-info "Native Windows requires the psmux 3.3.8 baseline" r)))
    (->Psmux run identity)))
