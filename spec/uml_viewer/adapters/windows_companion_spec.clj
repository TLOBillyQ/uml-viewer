(ns uml-viewer.adapters.windows-companion-spec
  (:require [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.domain.mailbox :as mailbox]))

(describe "Native Windows companion at the external process boundary"
  (it "rejects a tmux-compatible version line without fixed psmux provenance"
    (should-throw clojure.lang.ExceptionInfo
      (companion/psmux (fn [_] {:exit 0 :out "tmux 3.3.8"}) nil)))
  (it "binds only a complete explicit session and pane identity"
    (let [backend (companion/psmux
                    (fn [args]
                      {:exit 0 :out (if (= ["-V"] args)
                                     "tmux 3.3.8\npsmux 3.3.8 (baseline)"
                                     "UML|mine|$1|442|%3")}) nil)]
      (should= "%3" (companion/bind! backend "mine" nil))
      (should (companion/probe! backend "mine"))))
  (it "rejects ambiguous empty, wrong identity and timed out probes instead of replacing the companion"
    (doseq [response [{:exit 0 :out ""} {:exit 1 :out ""}
                      {:exit 0 :out "UML|other|$1|442|%3"}
                      {:exit nil :out "" :timeout? true}]]
      (let [backend (companion/psmux (fn [args] (if (= ["-V"] args)
                                                {:exit 0 :out "tmux 3.3.8\npsmux 3.3.8"}
                                                response)) nil)]
        (try (companion/probe! backend "mine") (should false)
             (catch clojure.lang.ExceptionInfo e
               (should= (if (:timeout? response) :timeout :failure) (:status (ex-data e))))))))
  (it "does not accept a successful command exit as proof recovery was armed"
    (let [backend (companion/psmux
                    (fn [args] {:exit 0 :out (case (first args)
                                              "-V" "tmux 3.3.8\npsmux 3.3.8"
                                              "display-message" "UML|mine|$1|442|%3"
                                              "")}) nil)]
      (should-throw clojure.lang.ExceptionInfo
        (companion/recovery! backend "mine" "%3" "C:/project" ["pwsh.exe" "-NoProfile" "-EncodedCommand" "AAA="] true))))
  (it "arms the original controlled command in its explicit pane and disables recovery before owned cleanup"
    (let [hook (atom nil) calls (atom [])
          backend (companion/psmux
                    (fn [args]
                      (swap! calls conj args)
                      {:exit 0 :out (case (first args)
                                     "-V" "tmux 3.3.8\npsmux 3.3.8"
                                     "display-message" "UML|mine|$1|442|%3"
                                     "show-options" "on"
                                     "show-hooks" (if @hook (str "pane-died -> " @hook) "(no hooks)")
                                     "set-hook" (do (reset! hook (when-not (some #{"-u"} args) (last args))) "")
                                     "")}) nil)]
      (companion/recovery! backend "mine" "%3" "C:/project" ["pwsh.exe" "-NoProfile" "-EncodedCommand" "AAA="] true)
      (should= "respawn-pane -k -t %3 -- pwsh.exe -NoProfile -EncodedCommand AAA=" @hook)
      (companion/recovery! backend "mine" "%3" nil nil false)
      (should-be-nil @hook)))
  (it "tolerates terminal rendering delay before the wake effect becomes visible"
    (let [captures (atom 0)
          backend (companion/psmux
                    (fn [args]
                      {:exit 0 :out (case (first args)
                                     "-V" "tmux 3.3.8\npsmux 3.3.8"
                                     "display-message" "UML|mine|$1|442|%3"
                                     "capture-pane" (if (< (swap! captures inc) 4)
                                                      "pane before mail"
                                                      "pane before mail and woke text")
                                     "")})
                    {:session "mine" :pane "%3" :session-id "$1" :server-pid "442"})]
      (should= 0 (:exit (companion/wake! backend "%3" ["-l" "woke text"])))
      (should (>= @captures 4))))
  (it "still fails wake and keeps mail queued when no pane effect appears in time"
    (let [backend (companion/psmux
                    (fn [args]
                      {:exit 0 :out (case (first args)
                                     "-V" "tmux 3.3.8\npsmux 3.3.8"
                                     "display-message" "UML|mine|$1|442|%3"
                                     "capture-pane" "pane before mail"
                                     "")})
                    {:session "mine" :pane "%3" :session-id "$1" :server-pid "442"})]
      (let [e (try (companion/wake! backend "%3" ["-l" "woke text"]) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (should e)
        (should= :failure (:status (ex-data e))))))
  (it "probes recovery with the verified native pane id rather than the unsupported session-pane syntax"
    (let [calls (atom [])]
      (companion/windows-identity!
        (fn [args]
          (swap! calls conj args)
          {:exit 0 :out (case (last args)
                          "UML|#{session_name}|#{session_id}|#{pid}|#{pane_id}" "UML|mine|$1|442|%3"
                          "")}) "mine" "%3" nil)
      (should (some #(= ["display-message" "-p" "-t" "%3" companion/identity-format] %) @calls))))
  (it "passes arbitrary executable, cwd and prompt as runner data instead of respawn syntax"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-windows-" (System/nanoTime))
          command (companion/windows-command! root ["C:/With Spaces/claude.ps1" "a'$(throw 1);\"b"])
          script (String. (.decode (java.util.Base64/getDecoder) (last command)) "UTF-16LE")]
      (should= ["pwsh.exe" "-NoProfile" "-EncodedCommand"] (subvec command 0 3))
      (should-not (.contains script "$(throw 1)"))
      (should (.contains script "FromBase64String"))))
  (it "rejects unsafe Claude batch wrappers with a usable native-entry diagnostic"
    (let [root (str (or (System/getenv "UML_ENTRY_TEST_TMP") (System/getProperty "java.io.tmpdir")) "/batch-" (System/nanoTime))
          fixture (clojure.java.io/file root "Claude.cmd")]
      (clojure.java.io/make-parents fixture)
      (spit fixture "#!/bin/sh\nexit 0\n")
      (.setExecutable fixture true)
      (let [command (companion/windows-command! root [(.getAbsolutePath fixture) "quoted \"prompt\""])
            result (companion/process! (assoc command 0 "pwsh"))]
        (should-not= 0 (:exit result))
        (should-contain "CLAUDE_BIN" (:err result))
        (should-contain ".ps1" (:err result)))))
  (it "delivers quoted multiline and empty argv to a real PowerShell Claude adapter"
    (let [root (str (or (System/getenv "UML_ENTRY_TEST_TMP") (System/getProperty "java.io.tmpdir")) "/argv-" (System/nanoTime))
          fixture (clojure.java.io/file root "Claude adapter.ps1")
          output (clojure.java.io/file root "observed.json")
          expected ["quoted \"value\"" "first\nsecond" "" "literal;$(throw 1)&%PATH%!" "末尾\\"]]
      (clojure.java.io/make-parents fixture)
      (spit fixture "[IO.File]::WriteAllText((Join-Path (Get-Location) 'observed.json'), (ConvertTo-Json -InputObject @($args) -Compress));exit 17")
      (let [command (companion/windows-command! root (into [(.getAbsolutePath fixture)] expected))
            result (companion/process! (assoc command 0 "pwsh"))]
        (should= 17 (:exit result))
        (should= "[\"quoted \\\"value\\\"\",\"first\\nsecond\",\"\",\"literal;$(throw 1)&%PATH%!\",\"末尾\\\\\"]" (slurp output)))))
  (it "delivers quoted multiline and empty argv to a real native process without Legacy parsing"
    (let [root (str (or (System/getenv "UML_ENTRY_TEST_TMP") (System/getProperty "java.io.tmpdir")) "/native-" (System/nanoTime))
          fixture (clojure.java.io/file root "argv.js")
          node (companion/process! ["pwsh" "-NoProfile" "-Command" "(Get-Command node).Source"])
          executable (if (sketch/windows?) (clojure.java.io/file (:out node))
                         (clojure.java.io/file root "node.exe"))]
      (when-not (and (= 0 (:exit node)) (seq (str/trim (:out node))))
        (pending "Node.js is a prerequisite for the real native process argv boundary"))
      (clojure.java.io/make-parents fixture)
      (when-not (sketch/windows?)
        (java.nio.file.Files/createSymbolicLink (.toPath executable)
          (.toPath (clojure.java.io/file (:out node))) (make-array java.nio.file.attribute.FileAttribute 0)))
      (spit fixture "process.stdout.write(JSON.stringify(process.argv.slice(2)));process.exitCode=19")
      (let [command (companion/windows-command! root [(.getAbsolutePath executable) (.getAbsolutePath fixture)
                                                     "quoted \"value\"" "first\nsecond" "" "literal;$(throw 1)&%PATH%!" "末尾\\"])
            result (companion/process! (assoc command 0 "pwsh"))]
        (should= 19 (:exit result))
        (should= "[\"quoted \\\"value\\\"\",\"first\\nsecond\",\"\",\"literal;$(throw 1)&%PATH%!\",\"末尾\\\\\"]" (:out result)))))
  (it "preserves an owned Windows companion when Windows Terminal is unavailable"
    (with-redefs [sketch/windows? (fn [] true)
                  sketch/run-command (fn [_] {:exit nil :err "wt missing"})]
      (let [out (sketch/open-window! "mine")]
        (should= :none (:terminal out))
        (should= "psmux.exe attach-session -t =mine" (:attach out)))))
  (it "does not create a replacement when a Windows project already has an ambiguous owner record"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-existing-" (System/nanoTime))
          calls (atom [])]
      (mailbox/write-companion! root {:session "mine" :pane "%3" :backend :psmux})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] (swap! calls conj args) {:exit 0 :out ""})]
        (should-throw clojure.lang.ExceptionInfo (sketch/open-in-terminal! root))
        (should-not (some #(some #{"new-session"} %) @calls)))))
  (it "creates once, verifies identity and recovery, then records the Windows owner before opening its terminal"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-fresh-" (System/nanoTime))
          session (atom nil) hook (atom nil) calls (atom [])]
      (with-redefs [sketch/windows? (fn [] true)
                    sketch/claude-executable (fn [] "C:/With Spaces/claude.cmd")
                    companion/process! (fn [args]
                      (swap! calls conj args)
                      (let [args (vec (rest args))]
                        {:exit 0 :out (case (first args)
                                       "-V" "tmux 3.3.8\npsmux 3.3.8"
                                       "new-session" (do (reset! session (nth args 3)) "")
                                       "display-message" (str "UML|" @session "|$1|442|%3")
                                       "show-options" "on"
                                       "-NoProfile" "OWNER|442|123|psmux"
                                       "set-hook" (do (reset! hook (last args)) "")
                                       "show-hooks" (str "pane-died -> " @hook)
                                       "")}))
                    sketch/open-window! (fn [sid]
                                          (should= sid (:session (mailbox/read-companion root)))
                                          {:terminal :none :session sid})]
        (should= :none (:terminal (sketch/open-in-terminal! root)))
        (should= :psmux (:backend (mailbox/read-companion root)))
        (should= 1 (count (filter #(= "new-session" (second %)) @calls))))))
  (it "discovers a configured PowerShell Claude entry with spaces at the process boundary"
    (with-redefs [sketch/windows? (fn [] true)
                  companion/process! (fn [args]
                                       (should= "pwsh.exe" (first args))
                                       {:exit 0 :out "C:\\With Spaces\\claude.ps1"})]
      (should= "C:\\With Spaces\\claude.ps1" (sketch/claude-executable))))
  (it "keeps queued Windows mail when the literal wake has no visible pane effect"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-mail-" (System/nanoTime))]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "%3" :session-id "$1" :server-pid "442"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] {:exit 0 :out (case (second args)
                                                                 "-V" "tmux 3.3.8\npsmux 3.3.8"
                                                                 "display-message" "UML|mine|$1|442|%3"
                                                                 "")})]
        (should-not (:woke? (sketch/request-agent! root :context {:context :real})))
        (should= :context (:op (first (:queue (clojure.edn/read-string (slurp (mailbox/to-agent root))))))))))
  (it "reports legacy session-prefixed Windows pane records instead of silently converting them"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-legacy-pane-" (System/nanoTime))
          calls (atom [])]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "=mine:%3" :session-id "$1" :server-pid "442"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] (swap! calls conj args) {:exit 0 :out "tmux 3.3.8\npsmux 3.3.8"})]
        (let [e (try ((ns-resolve 'uml-viewer.adapters.sketch 'live-session) root) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (should e)
          (should= :failure (:status (ex-data e)))
          (should= "=mine:%3" (:pane (ex-data e)))
          (should-not (some #(some #{"display-message"} %) @calls))))))
  (it "rejects Windows restart when the recorded server identity changed"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-restart-" (System/nanoTime))]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "%3" :session-id "$1" :server-pid "442"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] {:exit 0 :out (if (= "-V" (second args))
                                                                 "tmux 3.3.8\npsmux 3.3.8"
                                                                 "UML|mine|$1|443|%3")})]
        (should-throw clojure.lang.ExceptionInfo
          ((ns-resolve 'uml-viewer.adapters.sketch 'remember-companion!) root)))))
  (it "releases a normally closed Windows owner so the next fresh launch is possible"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-close-" (System/nanoTime))
          dead (atom false) calls (atom [])]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "%3" :session-id "$1" :server-pid "442" :owner-start "123"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args]
                      (swap! calls conj args)
                      {:exit 0 :out (case (first args)
                                     "pwsh.exe" (if @dead "MISSING" "OWNER|442|123|psmux")
                                     "psmux.exe" (case (second args)
                                                   "-V" "tmux 3.3.8\npsmux 3.3.8"
                                                   "display-message" "UML|mine|$1|442|%3"
                                                   "show-hooks" "(no hooks)"
                                                   "kill-session" (do (reset! dead true) "")
                                                   "") "")})]
        (sketch/shutdown-children! root)
        (should= {} (mailbox/read-companion root))
        (should (< (.indexOf @calls ["psmux.exe" "set-hook" "-t" "=mine" "-u" "pane-died"])
                   (.indexOf @calls ["psmux.exe" "kill-session" "-t" "=mine"]))))))
  (it "rejects fresh replacement while a verified Windows owner remains alive"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-live-" (System/nanoTime)) calls (atom [])]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :server-pid "442" :owner-start "123"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] (swap! calls conj args) {:exit 0 :out "OWNER|442|123|psmux"})]
        (should-throw clojure.lang.ExceptionInfo (sketch/open-in-terminal! root))
        (should-not (some #(some #{"new-session" "kill-session"} %) @calls)))))
)

(describe "Windows recovery pane identity at the external process boundary"
  (it "returns the pane pid once psmux populates delayed metadata"
    (let [calls (atom [])]
      (should= 5123 (companion/windows-pane-pid!
                      (fn [args]
                        (swap! calls conj args)
                        {:exit 0 :out (if (< (count @calls) 3) "" "5123")})
                      "%3" {:timeout-ms 1000 :interval-ms 1}))
      (should= 3 (count @calls))))
  (it "reports pane state instead of parsing metadata that stays empty"
    (let [e (try (companion/windows-pane-pid!
                   (fn [args] {:exit 0 :out (case (last args)
                                              "#{pane_pid}" ""
                                              "#{pane_dead}" "0"
                                              "#{pane_current_command}" "pwsh"
                                              "")})
                   "%3" {:timeout-ms 20 :interval-ms 1})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (should e)
      (should= :pane-pid-unavailable (:status (ex-data e)))
      (should= "%3" (:pane (ex-data e)))
      (should= "0" (:pane-dead (ex-data e)))
      (should= "pwsh" (:pane-command (ex-data e)))))
  (it "rejects malformed pane pid metadata instead of parsing it"
    (let [e (try (companion/windows-pane-pid!
                   (fn [_] {:exit 0 :out "BILLYPARALLEL"}) "%3" {:timeout-ms 1000 :interval-ms 1})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (should e)
      (should= :failure (:status (ex-data e)))
      (should= "BILLYPARALLEL" (:observed (ex-data e)))))
  (it "enumerates direct server children as OS recovery evidence"
    (with-redefs [companion/process!
                  (fn [args]
                    (should= "pwsh.exe" (first args))
                    {:exit 0 :out (str "CHILD|5636|12345678|pwsh.exe|"
                                       (.encodeToString (java.util.Base64/getEncoder)
                                         (.getBytes "pwsh.exe -NoProfile -EncodedCommand AAA=" "UTF-8")))})]
      (should= [{:pid 5636 :start "12345678" :name "pwsh.exe"
                 :command "pwsh.exe -NoProfile -EncodedCommand AAA="}]
               (companion/windows-server-children! "6992"))))
  (letfn [(b64 [s] (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))
          (child [pid start cmd] (str "CHILD|" pid "|" start "|pwsh.exe|" (b64 cmd)))
          (identity-run [pane-pid]
            (fn [args]
              {:exit 0 :out (case (last args)
                              "UML|#{session_name}|#{session_id}|#{pid}|#{pane_id}" "UML|mine|$1|442|%3"
                              "#{pane_pid}" pane-pid
                              "")}))
          (identity [] {:session "mine" :pane "%3" :session-id "$1" :server-pid "442"})]
    (it "verifies the recovered process through pane metadata when psmux populates it"
      (with-redefs [companion/process!
                    (fn [_] {:exit 0 :out (child 5636 200 "pwsh.exe -NoProfile -EncodedCommand AAA=")})]
        (should= {:evidence :pane-metadata :pid 5636
                  :process {:pid 5636 :start "200" :name "pwsh.exe"
                            :command "pwsh.exe -NoProfile -EncodedCommand AAA="}}
                 (companion/windows-recovered! (identity-run "5636") (identity)
                                               {:command-token "EncodedCommand AAA=" :died-after "100"
                                                :timeout-ms 50 :interval-ms 1}))))
    (it "falls back to server children when psmux 3.3.8 never repopulates pane_pid after respawn"
      (with-redefs [companion/process!
                    (fn [_] {:exit 0 :out (child 5636 200 "pwsh.exe -NoProfile -EncodedCommand AAA=")})]
        (should= {:evidence :server-children :psmux-pane-pid-defect true :pid 5636
                  :process {:pid 5636 :start "200" :name "pwsh.exe"
                            :command "pwsh.exe -NoProfile -EncodedCommand AAA="}}
                 (companion/windows-recovered! (identity-run "") (identity)
                                               {:command-token "EncodedCommand AAA=" :died-after "100"
                                                :timeout-ms 20 :interval-ms 1}))))
    (it "rejects recovery when no new process matches the original command identity"
      (with-redefs [companion/process! (fn [_] {:exit 0 :out ""})]
        (let [e (try (companion/windows-recovered! (identity-run "") (identity)
                                                   {:command-token "EncodedCommand AAA=" :died-after "100"
                                                    :timeout-ms 20 :interval-ms 1})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (should= :no-recovered-process (:reason (ex-data e))))))
    (it "rejects ambiguous recovery when multiple new processes match"
      (with-redefs [companion/process!
                    (fn [_] {:exit 0 :out (str (child 5636 200 "pwsh.exe -NoProfile -EncodedCommand AAA=") "\n"
                                               (child 5640 201 "pwsh.exe -NoProfile -EncodedCommand AAA="))})]
        (let [e (try (companion/windows-recovered! (identity-run "") (identity)
                                                   {:command-token "EncodedCommand AAA=" :died-after "100"
                                                    :timeout-ms 20 :interval-ms 1})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (should= :ambiguous-recovered-processes (:reason (ex-data e)))
          (should= 2 (count (:candidates (ex-data e)))))))
    (it "rejects a pane pid whose process does not carry the original command identity"
      (with-redefs [companion/process!
                    (fn [_] {:exit 0 :out (child 5636 200 "pwsh.exe -NoProfile -EncodedCommand OTHER=")})]
        (let [e (try (companion/windows-recovered! (identity-run "5636") (identity)
                                                   {:command-token "EncodedCommand AAA=" :died-after "100"
                                                    :timeout-ms 50 :interval-ms 1})
                     nil (catch clojure.lang.ExceptionInfo e e))]
          (should= :recovered-identity-mismatch (:reason (ex-data e))))))))
