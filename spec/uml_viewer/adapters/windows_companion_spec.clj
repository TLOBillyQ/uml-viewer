(ns uml-viewer.adapters.windows-companion-spec
  (:require [speclj.core :refer :all]
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
      (should= "=mine:%3" (companion/bind! backend "mine" nil))
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
        (companion/recovery! backend "mine" "=mine:%3" "C:/project" ["pwsh.exe" "-NoProfile" "-EncodedCommand" "AAA="] true))))
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
      (companion/recovery! backend "mine" "=mine:%3" "C:/project" ["pwsh.exe" "-NoProfile" "-EncodedCommand" "AAA="] true)
      (should= "respawn-pane -k -t =mine:%3 -- pwsh.exe -NoProfile -EncodedCommand AAA=" @hook)
      (should-throw clojure.lang.ExceptionInfo (companion/cleanup! backend "mine"))
      (should (< (.indexOf @calls ["set-hook" "-t" "=mine" "-u" "pane-died"])
                 (.indexOf @calls ["kill-session" "-t" "=mine"])))))
  (it "passes arbitrary executable, cwd and prompt as runner data instead of respawn syntax"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-windows-" (System/nanoTime))
          command (companion/windows-command! root ["C:/With Spaces/claude.ps1" "a'$(throw 1);\"b"])
          script (String. (.decode (java.util.Base64/getDecoder) (last command)) "UTF-16LE")]
      (should= ["pwsh.exe" "-NoProfile" "-EncodedCommand"] (subvec command 0 3))
      (should-not (.contains script "$(throw 1)"))
      (should (.contains script "FromBase64String"))))
  (it "preserves an owned Windows companion when Windows Terminal is unavailable"
    (with-redefs [sketch/windows? (fn [] true)
                  sketch/run-command (fn [_] {:exit nil :err "wt missing"})]
      (let [out (sketch/open-window! "mine")]
        (should= :none (:terminal out))
        (should= "psmux.exe attach-session -t =mine" (:attach out)))))
  (it "does not create a replacement when a Windows project already has an ambiguous owner record"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-existing-" (System/nanoTime))
          calls (atom [])]
      (mailbox/write-companion! root {:session "mine" :pane "=mine:%3" :backend :psmux})
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
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "=mine:%3" :session-id "$1" :server-pid "442"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] {:exit 0 :out (case (second args)
                                                                 "-V" "tmux 3.3.8\npsmux 3.3.8"
                                                                 "display-message" "UML|mine|$1|442|%3"
                                                                 "")})]
        (should-not (:woke? (sketch/request-agent! root :context {:context :real})))
        (should= :context (:op (first (:queue (clojure.edn/read-string (slurp (mailbox/to-agent root))))))))))
  (it "rejects Windows restart when the recorded server identity changed"
    (let [root (str (System/getProperty "java.io.tmpdir") "/uv-win-restart-" (System/nanoTime))]
      (mailbox/write-companion! root {:backend :psmux :session "mine" :pane "=mine:%3" :session-id "$1" :server-pid "442"})
      (with-redefs [sketch/windows? (fn [] true)
                    companion/process! (fn [args] {:exit 0 :out (if (= "-V" (second args))
                                                                 "tmux 3.3.8\npsmux 3.3.8"
                                                                 "UML|mine|$1|443|%3")})]
        (should-throw clojure.lang.ExceptionInfo
          ((ns-resolve 'uml-viewer.adapters.sketch 'remember-companion!) root)))))
)
