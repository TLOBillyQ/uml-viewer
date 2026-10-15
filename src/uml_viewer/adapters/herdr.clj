(ns uml-viewer.adapters.herdr
  "Herdr (>=0.9.3) session ownership. Every CLI request selects the recorded session."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.adapters.recovery :as recovery]))

(defn- fail! [message data]
  (throw (ex-info (str message "; ownership retained") (merge {:status :failure} data))))

(defn- clock [] (System/nanoTime))
(defn- canonical [path] (.getCanonicalPath (io/file path)))
(defn- ps-word [text] (str "'" (str/replace (str text) "'" "''") "'"))

(defn- parse-json [result]
  (try (json/read-str (:out result) :key-fn keyword)
       (catch Exception _ (fail! "Malformed Herdr JSON" {:result result}))))

(defn- checked-json! [argv]
  (let [result (companion/checked! companion/process! argv)
        data (parse-json result)]
    (when (:error data) (fail! "Herdr API error" {:error (:error data) :result result}))
    data))

(defn- cli! [session & args]
  (checked-json! (into [(companion/herdr) "--session" session] args)))

(defn- session! [session]
  (when-not (and (string? session) (re-matches #"uml-viewer-[A-Za-z0-9_-]+" session))
    (fail! "Herdr requires an isolated uml-viewer named session" {:session session})))

(defn- status! [session]
  (let [s (cli! session "status" "server" "--json")]
    (when-not (and (= session (:session s)) (string? (:socket s))
                   (.isAbsolute (io/file (:socket s))) (boolean? (:running s)))
      (fail! "Ambiguous Herdr endpoint" {:observed s}))
    s))

(defn- compatible! [status]
  (when-not (and (true? (:running status)) (companion/herdr-version-supported? (:version status))
                 (= 22 (:protocol status)) (true? (:compatible status))
                 (true? (:endpoint_compatible status)))
    (fail! "Herdr endpoint is not a verified >=0.9.3/protocol 22 server" {:observed status})))

(defn process-owner!
  "Read process creation identity; nil only for positively absent OS PID."
  [pid]
  (when-not (re-matches #"[1-9][0-9]*" (str pid))
    (fail! "Invalid owned process PID" {:pid pid}))
  (if (companion/windows?)
    (checked-json!
      ["pwsh.exe" "-NoProfile" "-EncodedCommand"
       (companion/encoded
         (str "$ErrorActionPreference='Stop';try{$p=[Diagnostics.Process]::GetProcessById(" pid
              ")}catch [ArgumentException]{[Console]::Write('null');exit 0};"
              "@{pid=$p.Id;start=$p.StartTime.ToUniversalTime().Ticks.ToString();name=$p.ProcessName}|ConvertTo-Json -Compress"))])
    (let [r (companion/process! ["ps" "-p" (str pid) "-o" "lstart=" "-o" "comm="])]
      (cond
        (and (= 1 (:exit r)) (str/blank? (:out r)) (not (:timeout? r))) nil
        (= 0 (:exit r))
        (if-let [[_ start name] (re-matches #"\s*(\w{3}\s+\w{3}\s+\d+\s+\d{2}:\d{2}:\d{2}\s+\d{4})\s+(.+)" (:out r))]
          {:pid (long (bigint pid)) :start start :name name}
          (fail! "Ambiguous process creation identity" {:result r}))
        :else (fail! "Could not read process creation identity" {:result r})))))

(defn- owner-matches! [pid start]
  (let [owner (process-owner! pid)]
    (when-not (and start (= start (:start owner)))
      (fail! "Herdr process creation identity changed" {:pid pid :expected start :observed owner}))
    owner))

(defn- poll! [timeout-ms interval-ms f message]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long timeout-ms)))]
    (loop []
      (or (f)
          (if (< (System/nanoTime) deadline)
            (do (Thread/sleep (long interval-ms)) (recur))
            (fail! message {:status :timeout}))))))

(defn- launch-server! [session cwd]
  (if (companion/windows?)
    ;; Start-Process on Windows creates an independent process. No inherited JVM
    ;; output pipes are retained, and the launcher exits after reporting identity.
    (checked-json!
      ["pwsh.exe" "-NoProfile" "-EncodedCommand"
       (companion/encoded
         (str "$ErrorActionPreference='Stop';"
              "$p=Start-Process -FilePath " (ps-word (companion/herdr)) " -ArgumentList @('--session'," (ps-word session)
              ",'server') -WorkingDirectory " (ps-word cwd) " -WindowStyle Hidden -PassThru;"
              "@{pid=$p.Id;start=$p.StartTime.ToUniversalTime().Ticks.ToString();name=$p.ProcessName}|ConvertTo-Json -Compress"))])
    (let [script (str "cd " (companion/shell-word cwd) " || exit; nohup " (companion/shell-word (companion/herdr)) " --session "
                      (companion/shell-word session) " server </dev/null >/dev/null 2>&1 & echo $!")
          pid (:out (companion/checked! companion/process! ["/bin/sh" "-c" script]))]
      (or (process-owner! pid) (fail! "Detached Herdr server exited" {:pid pid})))))

(defn- snapshot! [session]
  (let [snapshot (get-in (cli! session "api" "snapshot") [:result :snapshot])]
    (when-not (and (companion/herdr-version-supported? (:version snapshot)) (= 22 (:protocol snapshot))
                   (vector? (:panes snapshot)) (vector? (:workspaces snapshot)))
      (fail! "Invalid Herdr snapshot" {:observed snapshot}))
    snapshot))

(defn- complete! [identity]
  (session! (:session identity))
  (when-not (and (= :herdr (:backend identity)) (= :companion (:owner-role identity))
                 (every? #(some? (get identity %))
                         [:socket :workspace :pane :terminal-id :shell-pid :owner-start
                          :server-pid :server-start :cwd :command]))
    (fail! "Incomplete Herdr ownership" {:identity identity})))

(defn- observe! [expected session pane]
  (complete! expected)
  (when-not (and (= session (:session expected)) (= pane (:pane expected)))
    (fail! "Herdr selector conflicts with ownership" {:session session :pane pane}))
  (let [status (status! session)]
    (compatible! status)
    (when-not (= (:socket expected) (:socket status))
      (fail! "Herdr socket changed" {:expected expected :observed status})))
  (owner-matches! (:server-pid expected) (:server-start expected))
  (let [snapshot (snapshot! session)
        matches (filter #(= pane (:pane_id %)) (:panes snapshot))
        observed (first matches)
        info (get-in (cli! session "pane" "process-info" "--pane" pane) [:result :process_info])]
    (when-not (and (= 1 (count matches))
                   (some #(= (:workspace expected) (:workspace_id %)) (:workspaces snapshot))
                   (= (:workspace expected) (:workspace_id observed))
                   (= (:terminal-id expected) (:terminal_id observed))
                   (= pane (:pane_id info)) (= (:shell-pid expected) (:shell_pid info))
                   (string? (:cwd observed))
                   (= (canonical (:cwd expected)) (canonical (:cwd observed))))
      (fail! "Herdr pane identity changed" {:expected expected :observed observed :process-info info}))
    (owner-matches! (:shell-pid expected) (:owner-start expected))
    expected))

(defn runner-state
  "Read only the status belonging to this launch token; stale/malformed files cannot acknowledge startup."
  [identity]
  (let [file (io/file (:runner-state identity))]
    (when (.isFile file)
      (try
        (let [state (try (recovery/read-state file)
                         (catch Exception _ (json/read-str (slurp file) :key-fn keyword)))]
          (when (= (:runner-token identity) (:token state))
            (update state :phase name)))
        (catch Exception _ nil)))))

(defn- runner-command! [identity command]
  (when (and (companion/windows?) (not (str/ends-with? (str/lower-case (first command)) ".exe")))
    (fail! "Herdr recovery requires direct claude.exe or node.exe + official cli.js; wrappers cannot prove child identity" {:status :unsupported}))
  (let [manifest (str (:runner-state identity) ".launch.edn")]
    (recovery/write-state! manifest (assoc identity :command command))
    (recovery/java-command "runner" manifest)))

(defn- pane-command! [command shell-name]
  ;; ps comm reports argv0; macOS login shells carry a leading dash (-zsh).
  (let [name (-> shell-name (str/split #"[/\\]") last str/lower-case (str/replace #"^-" ""))]
    (cond
      (#{"pwsh" "pwsh.exe" "powershell" "powershell.exe"} name)
      (str "& " (str/join " " (map ps-word command)))
      (#{"sh" "bash" "zsh" "dash"} name)
      (str/join " " (map companion/shell-word command))
      :else (fail! "Unsupported Herdr pane shell; command was not sent" {:shell shell-name}))))

(def recovery-prompt
  (str "Recovery: do not scan sources or rewrite policy. Read the current .uml-viewer/to-agent.edn queue; "
       "pop and handle commands oldest first, preserving the latest context and existing proposals. "
       "Apply the standing quality-result gate to each refresh; only valid results followed by successful IR permit :display. "
       "A failed refresh stops that request's IR/display, reports its cause, and leaves later FIFO mail runnable. "
       "If there is no pending refresh, restore :display from .uml-viewer/view.edn; "
       "never issue an unconditional recovery display after a failed refresh. Then wait for directives."))

(defn- terminal-reset-script
  "PowerShell that writes terminal mode resets into the pane console from an
  external process. A force-killed TUI leaves ConPTY in application modes (alt
  screen, bracketed paste, kitty keyboard) that mangle or swallow the next
  injected command; only output parsed by ConPTY clears them."
  [shell-pid]
  (str "$ErrorActionPreference='Stop';"
       "Add-Type -Namespace K -Name W -MemberDefinition '"
       "[DllImport(\"kernel32.dll\",SetLastError=true)] public static extern bool FreeConsole();"
       "[DllImport(\"kernel32.dll\",SetLastError=true)] public static extern bool AttachConsole(uint dwProcessId);"
       "[DllImport(\"kernel32.dll\",CharSet=CharSet.Unicode,SetLastError=true)] public static extern System.IntPtr CreateFile(string n, uint a, uint s, System.IntPtr p, uint d, uint f, System.IntPtr t);"
       "[DllImport(\"kernel32.dll\",SetLastError=true)] public static extern bool WriteFile(System.IntPtr h, byte[] b, uint n, out uint w, System.IntPtr o);"
       "';"
       "[K.W]::FreeConsole() | Out-Null;"
       "if (-not [K.W]::AttachConsole(" shell-pid ")) { exit 3 };"
       "$e = [char]27;"
       "$seq = $e + '[?1049l' + $e + '[?2004l' + $e + '[<u' + $e + '[<u' + $e + '[<u' + $e + '[?25h';"
       "$bytes = [Text.Encoding]::UTF8.GetBytes($seq);"
       "$h = [K.W]::CreateFile(\"CONOUT$\", 1073741824, 3, [System.IntPtr]::Zero, 3, 0, [System.IntPtr]::Zero);"
       "if ($h -eq [System.IntPtr](-1)) { exit 4 };"
       "$written = 0;"
       "if (-not [K.W]::WriteFile($h, $bytes, $bytes.Length, [ref]$written, [System.IntPtr]::Zero)) { exit 4 }"))

(defn- terminal-reset!
  "Clear killed-TUI terminal residue, then cancel any half-typed input line.
  Keystrokes only decode after the modes reset, so ctrl+c follows the write."
  [identity]
  (when (companion/windows?)
    (companion/checked! companion/process!
                        ["pwsh.exe" "-NoProfile" "-EncodedCommand"
                         (companion/encoded (terminal-reset-script (:shell-pid identity)))])
    (Thread/sleep 250)
    (companion/checked! companion/process!
                        [(companion/herdr) "--session" (:session identity) "pane" "send-keys" (:pane identity) "ctrl+c"])
    (Thread/sleep 150)))

(defn- pane-run!
  "Deliver text to the pane. A freshly spawned macOS pty is still in canonical
  mode (MAX_CANON 1024), so herdr rejects long lines until the shell enters
  raw mode; retry only that rejection, per the server's own guidance."
  [session pane text]
  (let [argv [(companion/herdr) "--session" session "pane" "run" pane text]
        deadline (+ (clock) (* 10000 1000000))]
    (loop []
      (let [result (companion/process! argv)]
        (when-not (= 0 (:exit result))
          (if (and (< (clock) deadline)
                   (str/includes? (str (:out result) (:err result)) "canonical mode"))
            (do (Thread/sleep 100) (recur))
            (fail! "Herdr pane run failed" {:result result})))))))

(defn relaunch!
  "Submit one journalled generation only into the verified, idle original pane."
  [identity]
  (companion/ensure-herdr!)
  (observe! identity (:session identity) (:pane identity))
  (let [info (get-in (cli! (:session identity) "pane" "process-info" "--pane" (:pane identity)) [:result :process_info])
        foreground (:foreground_processes info)]
    (when-not (and (vector? foreground)
                   (every? #(= (:shell-pid identity) (:pid %)) foreground))
      (fail! "Recovery foreground is unknown or occupied; refusing replacement" {:observed info})))
  (terminal-reset! identity)
  (let [shell (process-owner! (:shell-pid identity))
        command (if (:recovery-command identity) (:recovery-command identity)
                    (:command identity))
        text (pane-command! (runner-command! identity command) (:name shell))]
    (pane-run! (:session identity) (:pane identity) text)))

(defn recovery-status [identity]
  (when (:supervisor identity) (recovery/status identity)))

(defrecord Herdr [identity]
  companion/SessionBackend
  (create! [_ session cwd command]
    (session! session)
    (when-not (and (seq command) (every? string? command))
      (fail! "Herdr command must be a nonempty argv vector" {:command command}))
    (when (and (companion/windows?) (not (str/ends-with? (str/lower-case (first command)) ".exe")))
      (fail! "Herdr requires direct native claude.exe (or node.exe + official cli.js); wrapper child identity is unsupported" {:status :unsupported}))
    (when (and identity (not (:provisional identity)))
      (fail! "Cannot create over existing Herdr ownership" {:identity identity}))
    (let [sessions (:sessions (cli! session "session" "list" "--json"))]
      (when-not (vector? sessions) (fail! "Ambiguous session inventory" {}))
      (when (some #(= session (:name %)) sessions)
        (fail! "Herdr named session already exists" {:session session})))
    (let [base (merge identity {:backend :herdr :owner-role :companion :session session
                                :cwd (canonical cwd) :command (vec command) :provisional true})
          _ (mailbox/write-companion! cwd base)
          owner (launch-server! session cwd)
          base (assoc base :server-pid (str (:pid owner)) :server-start (:start owner))
          _ (mailbox/write-companion! cwd base)
          status (poll! 5000 50 #(let [s (status! session)] (when (:running s) s))
                        "Herdr server did not become reachable")
          _ (compatible! status)
          base (assoc base :socket (:socket status))
          _ (mailbox/write-companion! cwd base)
          created (:result (cli! session "workspace" "create" "--cwd" (:cwd base) "--no-focus"))
          pane (:root_pane created)
          base (assoc base :workspace (get-in created [:workspace :workspace_id])
                           :pane (:pane_id pane) :terminal-id (:terminal_id pane))
          _ (mailbox/write-companion! cwd base)
          info (get-in (cli! session "pane" "process-info" "--pane" (:pane base)) [:result :process_info])
          shell (process-owner! (:shell_pid info))
          base (assoc base :shell-pid (:shell_pid info) :owner-start (:start shell)
                           :runner-token (str (java.util.UUID/randomUUID))
                           :runner-state (.getAbsolutePath (io/file cwd ".uml-viewer" (str session "-runner.edn")))
                           :supervisor {:token (str (java.util.UUID/randomUUID))
                                        :manifest (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-launch.edn"))
                                        :state-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery.edn"))
                                        :control-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-control.edn"))
                                        :gate-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-gate.lock"))
                                        :lock-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-controller.lock"))})
          _ (mailbox/write-companion! cwd base)
          _ (observe! base session (:pane base))
          text (pane-command! (runner-command! base command) (:name shell))]
      (pane-run! session (:pane base) text)
      ;; Native PowerShell startup exceeded five seconds during concurrent coverage.
      ;; Wait for this launch token without ever resubmitting the command.
      (let [state (poll! 30000 100 #(runner-state base) "Herdr runner did not acknowledge startup")]
        (when-not (or (= "started" (:phase state))
                      (and (= "exited" (:phase state)) (= 0 (:exit state))))
          (fail! "Herdr runner failed during startup" {:runner-state state})))
      (observe! base session (:pane base))
      (let [owned (assoc base :provisional false)]
        (mailbox/write-companion! cwd owned)
        (recovery/start! owned)
        owned)))
  (identity! [_ session pane] (observe! identity session pane))
  (probe! [_ session] (observe! identity session (:pane identity)) true)
  (bind! [_ session pane] (:pane (observe! identity session pane)))
  (wake! [this pane keys] (companion/wake! this pane keys {}))
  (wake! [_ pane keys {:keys [token acknowledged? timeout-ms interval-ms]
                      :or {timeout-ms 5000 interval-ms 50}}]
    (when-not (and (string? token) (seq token) (fn? acknowledged?)
                   (= 2 (count keys)) (= "-l" (first keys))
                   (string? (second keys)) (str/includes? (second keys) token))
      (fail! "Herdr wake requires literal text, a unique token and consumption acknowledgement" {}))
    (observe! identity (:session identity) pane)
    (when (acknowledged? token)
      (fail! "Herdr wake acknowledgement predates this delivery" {}))
    (let [agents (:agents (snapshot! (:session identity)))]
      (when-not (some #(and (= pane (:pane_id %))
                            (= (:terminal-id identity) (:terminal_id %))
                            (= "claude" (:agent %))) agents)
        (fail! "No Claude agent occupies the owned Herdr pane" {:pane pane})))
    ;; Herdr validates agent occupancy again and atomically submits text+Enter.
    (companion/checked! companion/process!
                        [(companion/herdr) "--session" (:session identity) "agent" "prompt" pane (second keys)])
    (poll! timeout-ms interval-ms #(acknowledged? token)
           "Herdr wake consumption was not acknowledged; mail remains queued")
    (observe! identity (:session identity) pane)
    {:status :acknowledged :token token})
  (recovery! [_ session pane _cwd _command enabled?]
    (observe! identity session (or pane (:pane identity)))
    (if (:supervisor identity)
      (if enabled? (do (recovery/verify! identity) {:enabled? true}) (recovery/cancel! identity))
      (if enabled? (fail! "No durable supervisor registered; refusing replacement" {:status :unsupported})
        {:enabled? false})))
  (cleanup! [_ session]
    (complete! identity)
    (when-not (= session (:session identity)) (fail! "Cleanup selector conflicts with ownership" {}))
    (when (:supervisor identity) (recovery/cancel! identity))
    (let [saved (mailbox/read-companion (:cwd identity))
          _ (when (and (seq saved)
                       (not= (dissoc identity :cleanup-phase) (dissoc saved :cleanup-phase)))
              (fail! "Cleanup ownership changed" {:observed saved}))
          phase (or (:cleanup-phase saved) (:cleanup-phase identity))
          checkpoint! #(mailbox/write-companion! (:cwd identity) (assoc identity :cleanup-phase %))
          endpoint (status! session)]
      (when-not (= (:socket identity) (:socket endpoint)) (fail! "Cleanup socket changed" {}))
      (when-not phase
        (observe! identity session (:pane identity))
        (checkpoint! :workspace-closing))
      (when (or (nil? phase) (#{:workspace-closing :workspace-closed} phase))
        (compatible! endpoint)
        (owner-matches! (:server-pid identity) (:server-start identity))
        (when (some #(= (:workspace identity) (:workspace_id %)) (:workspaces (snapshot! session)))
          (observe! identity session (:pane identity))
          (cli! session "workspace" "close" (:workspace identity)))
        (when (some #(= (:workspace identity) (:workspace_id %)) (:workspaces (snapshot! session)))
          (fail! "Herdr workspace remains after close" {}))
        (checkpoint! :workspace-closed))
      (when-not (contains? #{nil :workspace-closing :workspace-closed :server-stopping :server-stopped :session-deleting} phase)
        (fail! "Unknown cleanup phase" {:phase phase}))
      (when-not (#{:server-stopped :session-deleting} phase)
        (checkpoint! :server-stopping)
        (let [endpoint (status! session)]
          (when-not (= (:socket identity) (:socket endpoint)) (fail! "Cleanup socket changed" {}))
          (if (:running endpoint)
            (do
              (compatible! endpoint)
              (owner-matches! (:server-pid identity) (:server-start identity))
              (let [stopped (cli! session "session" "stop" session "--json")]
                (when-not (and (true? (:stopped stopped)) (= session (get-in stopped [:session :name]))
                               (= (:socket identity) (get-in stopped [:session :socket_path])))
                  (fail! "Herdr session stop was not confirmed" {:observed stopped}))))
            (when (process-owner! (:server-pid identity))
              (fail! "Stopped endpoint has a live or reused server PID" {}))))
        (poll! 3000 50 #(nil? (process-owner! (:server-pid identity))) "Herdr server process remains after stop")
        (checkpoint! :server-stopped))
      (let [endpoint (status! session)]
        (when-not (and (false? (:running endpoint)) (= (:socket identity) (:socket endpoint))
                       (nil? (process-owner! (:server-pid identity))))
          (fail! "Herdr endpoint remains after stop" {:observed endpoint})))
      (checkpoint! :session-deleting)
      (let [sessions (:sessions (cli! session "session" "list" "--json"))]
        (when-not (vector? sessions) (fail! "Ambiguous cleanup inventory" {}))
        (when (some #(= session (:name %)) sessions)
          (let [deleted (cli! session "session" "delete" session "--json")]
            (when-not (and (true? (:deleted deleted)) (= session (get-in deleted [:session :name]))
                           (= (:socket identity) (get-in deleted [:session :socket_path])))
              (fail! "Herdr session deletion was not confirmed" {:observed deleted})))))
      (let [sessions (:sessions (cli! session "session" "list" "--json"))]
        (when-not (and (vector? sessions) (not-any? #(= session (:name %)) sessions))
          (fail! "Herdr session remains after deletion" {:observed sessions}))))
    {:released? true}))

(defn herdr
  "Construct a backend from persisted ownership (or a fresh provisional record).
  process! remains dynamically redefinable. Recovery is deliberately unsupported."
  [identity]
  (->Herdr identity))
