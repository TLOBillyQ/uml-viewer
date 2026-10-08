(ns uml-viewer.adapters.companion
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(defn process!
  "外部进程边界：保留退出状态、两个输出流与超时。env-remove 列出
  不传给子进程的环境变量（工具父环境可能导出 NO_COLOR=1，psmux
  客户端的 crossterm 渲染会响应它而退化为单色）。"
  ([argv] (process! argv 10000))
  ([argv timeout-ms] (process! argv timeout-ms nil))
  ([argv timeout-ms env-remove]
   (try
     (let [pb (ProcessBuilder. (into-array String argv))]
       (doseq [v env-remove] (.remove (.environment pb) ^String v))
       (let [p (.start pb)
             out (future (slurp (.getInputStream p)))
             err (future (slurp (.getErrorStream p)))
             done? (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)]
         (when-not done? (.destroy p))
         {:exit (when done? (.exitValue p))
          :out (if done? (str/trim @out) "")
          :err (if done? (str/trim @err) "process timed out")
          :timeout? (not done?)}))
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
  (wake! [backend pane keys] [backend pane keys opts])
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
  (wake! [this pane keys _opts]
    (wake! this pane keys))
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
                    ;; The viewer JVM may be launched from a tool shell that exports
                    ;; NO_COLOR=1; without clearing it the companion Claude renders monochrome.
                    "Remove-Item Env:NO_COLOR -ErrorAction SilentlyContinue;"
                    "$argv=@($m.launch.argv.arg|ForEach-Object{D $_});& $exe @argv;exit $LASTEXITCODE")]
    (io/make-parents file)
    (spit file manifest)
    ["pwsh.exe" "-NoProfile" "-EncodedCommand" (encoded runner)]))

(def identity-format "UML|#{session_name}|#{session_id}|#{pid}|#{pane_id}")

(defn windows-target
  "psmux pane ids repeat across servers; route by the owned single-pane session."
  [session]
  (str "=" session ":0.0"))

(defn windows-identity! [run session pane expected]
  (let [r (checked! run ["display-message" "-p" "-t" (windows-target session) identity-format])
        [_ name sid pid pane-id] (re-matches #"UML\|([^|\r\n]+)\|(\$\d+)\|(\d+)\|(%\d+)" (:out r))
        observed {:session name :session-id sid :server-pid pid :pane pane-id}]
    (when-not (and (= session name) (or (nil? pane) (= pane (:pane observed)))
                   (or (nil? expected) (= (select-keys expected [:session :session-id :server-pid :pane]) observed)))
      (throw (ex-info "psmux identity probe failed; session absence is not proven" (assoc r :status :failure :expected expected :observed observed))))
    observed))

(defn- bounded-poll
  "Shared bounded poll: call probe every interval-ms until it returns truthy,
  giving up after timeout-ms (returns nil). probe may throw to abort early.
  Each caller passes its own {:timeout-ms :interval-ms} options; defaults
  differ per site because they wait for different things (see each caller's
  docstring)."
  [{:keys [timeout-ms interval-ms]} probe]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))]
    (loop []
      (or (probe)
          (when (< (System/nanoTime) deadline)
            (Thread/sleep (long interval-ms))
            (recur))))))

;; psmux 3.3.8 defect (issue #8): after respawn-pane, #{pane_pid} stays empty
;; permanently even though a live respawned process exists (pane_dead = 0).
;; #{pane_pid} must therefore be polled with a deadline and never parsed when
;; empty; persistent emptiness is handled by windows-recovered! below.
(defn windows-pane-pid!
  "Recovery verification API (acceptance / external harness entry point):
  bounded poll of #{pane_pid} for one pane. Empty metadata is retried until
  the deadline instead of being parsed; persistent emptiness throws with the
  pane state so timing, psmux metadata defects and identity mismatches stay
  distinguishable. Defaults 5000ms/100ms: when psmux fills the metadata at all
  it does so sub-second, so five seconds amply separates timing from defect."
  ([run pane] (windows-pane-pid! run pane {}))
  ([run pane {:keys [timeout-ms interval-ms] :or {timeout-ms 5000 interval-ms 100}}]
   (or (bounded-poll {:timeout-ms timeout-ms :interval-ms interval-ms}
                     (fn []
                       (let [out (:out (checked! run ["display-message" "-p" "-t" pane "#{pane_pid}"]))]
                         (cond
                           (re-matches #"[1-9][0-9]*" out) (Long/parseLong out)
                           (seq out) (throw (ex-info "psmux pane pid metadata is malformed"
                                                     {:status :failure :pane pane :observed out}))))))
       (throw (ex-info "psmux pane pid metadata stayed empty past the deadline"
                       {:status :pane-pid-unavailable
                        :pane pane
                        :pane-dead (:out (checked! run ["display-message" "-p" "-t" pane "#{pane_dead}"]))
                        :pane-command (:out (checked! run ["display-message" "-p" "-t" pane "#{pane_current_command}"]))
                        :timeout-ms timeout-ms})))))

(defn windows-server-children!
  "Recovery verification API (acceptance / external harness entry point):
  OS enumeration of the direct children of the verified psmux server process.
  This is the fallback recovery identity evidence while #{pane_pid} stays empty
  after respawn-pane on psmux 3.3.8 (issue #8)."
  [server-pid]
  (when-not (and (string? server-pid) (re-matches #"[1-9][0-9]*" server-pid))
    (throw (ex-info "No verified Windows server PID" {:status :failure})))
  (let [script (str "$ErrorActionPreference='Stop';"
                    "Get-CimInstance Win32_Process -Filter \"ParentProcessId=" server-pid "\" | ForEach-Object {"
                    "$c=if($_.CommandLine){[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.CommandLine))}else{''};"
                    "[Console]::WriteLine(('CHILD|{0}|{1}|{2}|{3}' -f $_.ProcessId,$_.CreationDate.ToUniversalTime().Ticks,$_.Name,$c))}")
        out (:out (checked! process! ["pwsh.exe" "-NoProfile" "-EncodedCommand" (encoded script)]))]
    (into []
          (keep (fn [line]
                  (when-let [[_ pid start name cmd] (re-matches #"CHILD\|([1-9][0-9]*)\|([0-9]+)\|([^|\r\n]+)\|([A-Za-z0-9+/=]*)" line)]
                    {:pid (Long/parseLong pid)
                     :start start
                     :name name
                     :command (if (seq cmd)
                                (String. (.decode (java.util.Base64/getDecoder) cmd) "UTF-8")
                                "")})))
          (str/split-lines out))))

(defn windows-recovered!
  "Recovery verification API (acceptance / external harness entry point):
  verify that a controlled exit respawned exactly one new process carrying the
  original command identity. Primary evidence is #{pane_pid} metadata; on
  psmux 3.3.8 respawn-pane leaves that metadata permanently empty (issue #8),
  so the verified server's OS children are the documented fallback evidence.
  Note the fallback proves a unique new process under the verified server with
  the original command; it does not by itself prove binding to the recorded
  pane, because pane metadata is exactly what is defective — pane-level
  evidence must come from input delivered to that pane being consumed by the
  process. :command-token is a unique substring of the original command line
  (the encoded runner payload ties the process to its original cwd and argv);
  :died-after is the UTC tick at which the old process was terminated.
  Defaults 10000ms/200ms: the poll must span the pane-died hook, respawn-pane
  and process spawn, which is slower than a plain metadata read."
  [run identity {:keys [command-token died-after timeout-ms interval-ms]
                 :or {timeout-ms 10000 interval-ms 200}}]
  (when-not (and (string? command-token) (seq command-token))
    (throw (ex-info "Windows recovery verification requires the original command token"
                    {:status :failure})))
  (windows-identity! run (:session identity) (:pane identity) identity)
  (let [matches (fn [children]
                  (into [] (filter (fn [{:keys [start command]}]
                                     (and (str/includes? (or command "") command-token)
                                          (or (nil? died-after)
                                              (>= (bigint start) (bigint died-after))))))
                        children))
        describe-child (fn [c] (select-keys c [:pid :start :name :command]))]
    (try
      (let [pid (windows-pane-pid! run (windows-target (:session identity))
                                   {:timeout-ms timeout-ms :interval-ms interval-ms})
            child (first (filter #(= pid (:pid %))
                                 (matches (windows-server-children! (:server-pid identity)))))]
        (if child
          {:evidence :pane-metadata :pid pid :process (describe-child child)}
          (throw (ex-info "psmux pane pid does not match the original command identity"
                          {:status :failure :reason :recovered-identity-mismatch :pane-pid pid}))))
      (catch clojure.lang.ExceptionInfo e
        (if (not= :pane-pid-unavailable (:status (ex-data e)))
          (throw e)
          (let [candidates (matches (windows-server-children! (:server-pid identity)))]
            (case (count candidates)
              0 (throw (ex-info "No recovered process matches the original command identity"
                                {:status :failure :reason :no-recovered-process
                                 :pane (:pane identity)}))
              1 {:evidence :server-children :psmux-pane-pid-defect true
                 :pid (:pid (first candidates)) :process (describe-child (first candidates))}
              (throw (ex-info "Multiple recovered processes match the original command identity"
                              {:status :failure :reason :ambiguous-recovered-processes
                               :candidates (mapv describe-child candidates)})))))))))

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
  (wake! [this pane keys] (wake! this pane keys {}))
  (wake! [_ pane keys {:keys [timeout-ms interval-ms] :or {timeout-ms 2000 interval-ms 100}}]
    (when-not (and identity (= pane (:pane identity)))
      (throw (ex-info "psmux wake requires verified explicit pane ownership" {:status :failure})))
    (windows-identity! run (:session identity) pane identity)
    (let [target (windows-target (:session identity))
          before (when (= "-l" (first keys)) (:out (checked! run ["capture-pane" "-p" "-t" target])))
          result (checked! run (into ["send-keys" "-t" target] keys))]
      (when (= "-l" (first keys))
        ;; 2000ms/100ms default: only terminal rendering delay is awaited, so
        ;; the window stays much shorter than the recovery verification polls.
        (when-not (bounded-poll {:timeout-ms timeout-ms :interval-ms interval-ms}
                                (fn []
                                  (let [out (:out (checked! run ["capture-pane" "-p" "-t" target]))]
                                    (when (and (not= before out) (str/includes? out (second keys)))
                                      out))))
          (throw (ex-info "psmux wake effect was not observed; mail remains queued" {:status :failure}))))
      (windows-identity! run (:session identity) pane identity)
      result))
  (recovery! [_ session pane cwd command enabled?]
    (windows-identity! run session (or pane (:pane identity)) identity)
    (let [target (str "=" session)
          hook (when enabled? (str "respawn-pane -k -t " (windows-target session) " -- " (str/join " " command)))]
      (if enabled?
        (do
          (when-not (and (re-matches #"%\d+" pane)
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
    (when-not (bounded-poll {:timeout-ms 2000 :interval-ms 50}
                            (fn [] (when (= :missing (:status (windows-owner! identity))) true)))
      (throw (ex-info "Windows companion owner did not exit after cleanup" {:status :timeout :session session})))))

(defn psmux [run identity]
  (let [r (checked! run ["-V"])]
    (when-not (re-matches #"tmux 3\.3\.8\r?\npsmux 3\.3\.8(?: \([^\r\n]+\))?" (:out r))
      (throw (ex-info "Native Windows requires the psmux 3.3.8 baseline" r)))
    (->Psmux run identity)))
