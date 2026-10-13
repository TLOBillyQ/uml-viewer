(ns uml-viewer.adapters.herdr-spec
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.herdr :as herdr]
            [uml-viewer.adapters.recovery :as recovery]
            [uml-viewer.domain.mailbox :as mailbox]))

(def owned {:backend :herdr :owner-role :companion :session "uml-viewer-A"
            :socket "C:/herdr/sessions/A/herdr.sock" :workspace "w1" :pane "w1:p1"
            :terminal-id "term_A" :shell-pid 42 :owner-start "shell-start"
            :server-pid "41" :server-start "server-start"
            :cwd (System/getProperty "user.dir") :command ["claude.exe"]})
(defn response [x] {:exit 0 :out (json/write-str x) :err ""})
(defn fake-cli [calls state]
  (fn [argv]
    (swap! calls conj argv)
    (let [[exe selector session & args] argv
          op (vec (take 2 args))
          s @state]
      (when-not (and (= "herdr" exe) (= "--session" selector) (= "uml-viewer-A" session))
        (throw (ex-info "Unexpected selector" {:argv argv})))
      (if (= op (:fail s))
        {:exit 1 :out "" :err "injected failure"}
        (response
          (case op
            ["status" "server"] {:session session :socket (:socket owned) :running (not (:stopped s))
                                   :version "0.9.3" :protocol 22 :compatible true :endpoint_compatible true}
            ["api" "snapshot"] {:result {:snapshot {:version "0.9.3" :protocol 22
                                                       :workspaces (if (:closed s) [] [{:workspace_id "w1"}])
                                                       :panes [{:pane_id "w1:p1" :workspace_id "w1"
                                                                :terminal_id (or (:terminal s) "term_A") :cwd (:cwd owned)}]
                                                       :agents (if (:no-agent s) [] [{:pane_id "w1:p1" :terminal_id "term_A" :agent "claude"}])}}}
            ["pane" "process-info"] {:result {:process_info {:pane_id "w1:p1" :shell_pid 42}}}
            ["workspace" "close"] (do (when-not (:ignore-close s) (swap! state assoc :closed true)) {:result {:type "ok"}})
            ["session" "stop"] (do (swap! state assoc :stopped true)
                                    {:stopped true :session {:name session :socket_path (:socket owned)}})
            ["session" "delete"] (do (swap! state assoc :deleted true)
                                      {:deleted true :session {:name session :socket_path (:socket owned)}})
            ["session" "list"] {:sessions (if (:deleted s) [{:name "uml-viewer-B"}] [{:name session} {:name "uml-viewer-B"}])}
            ["agent" "prompt"] (do (swap! state assoc :prompted true) {:result {:type "ok"}})
            (throw (ex-info "Unexpected CLI call" {:argv argv}))))))))
(defn fake-owner [state]
  (fn [pid]
    (case (str pid)
      "41" (when-not (:stopped @state) {:pid 41 :start (or (:server-start @state) "server-start") :name "herdr"})
      "42" {:pid 42 :start (or (:shell-start @state) "shell-start") :name "pwsh"})))

(describe "Herdr owned session backend"
  (around [run-spec]
    (with-redefs [mailbox/read-companion (constantly nil)
                  mailbox/write-companion! (fn [_ identity] identity)]
      (run-spec)))
  (it "binds the full identity via explicit session selectors"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
        (should= "w1:p1" (companion/bind! (herdr/herdr owned) "uml-viewer-A" "w1:p1"))
        (should (companion/probe! (herdr/herdr owned) "uml-viewer-A")))))
  (it "rejects A/B selectors even though both sessions use w1:p1"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state)]
        (should-throw clojure.lang.ExceptionInfo (companion/bind! (herdr/herdr owned) "uml-viewer-B" "w1:p1"))
        (should (empty? @calls)))))
  (it "refuses cleanup when terminal, shell creation or server creation identity changes"
    (doseq [change [{:terminal "term_B"} {:shell-start "reused"} {:server-start "reused"}]]
      (let [calls (atom []) state (atom change)]
        (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
          (should-throw clojure.lang.ExceptionInfo (companion/cleanup! (herdr/herdr owned) "uml-viewer-A"))
          (should-not (some #(= "close" (nth % 4 nil)) @calls))
          (should-not (some #(= "stop" (nth % 4 nil)) @calls))))))
  (it "closes and confirms only A while leaving B in the inventory"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
        (should= {:released? true} (companion/cleanup! (herdr/herdr owned) "uml-viewer-A"))
        (should (:deleted @state))
        (should (every? #(= "uml-viewer-A" (nth % 2)) @calls)))))
  (it "retains ambiguous cleanup after failure or a false successful close"
    (doseq [change [{:fail ["workspace" "close"]} {:ignore-close true} {:fail ["session" "stop"]}]]
      (let [calls (atom []) state (atom change)]
        (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
          (should-throw clojure.lang.ExceptionInfo (companion/cleanup! (herdr/herdr owned) "uml-viewer-A"))
          (should-not (:deleted @state))))))
  (it "never treats malformed or timed out probes as absence"
    (doseq [result [{:exit 0 :out "not JSON"} {:exit nil :out "" :timeout? true :err "timeout"}]]
      (with-redefs [companion/process! (constantly result)]
        (should-throw clojure.lang.ExceptionInfo (companion/probe! (herdr/herdr owned) "uml-viewer-A")))))
  (it "requires fresh consumption acknowledgement and a real Claude occupant"
    (doseq [initial [{:no-agent true} {}]]
      (let [calls (atom []) state (atom initial)]
        (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
          (should-throw clojure.lang.ExceptionInfo
            (companion/wake! (herdr/herdr owned) "w1:p1" ["-l" "mail token-1"]
                             {:token "token-1" :acknowledged? (constantly false) :timeout-ms 0}))
          (should= (not (:no-agent initial)) (boolean (:prompted @state)))))))
  (it "returns success only after this prompt was consumed"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
        (should= {:status :acknowledged :token "token-2"}
                 (companion/wake! (herdr/herdr owned) "w1:p1" ["-l" "mail token-2"]
                                  {:token "token-2" :acknowledged? (fn [_] (:prompted @state))})))))
  (it "rejects preexisting acknowledgements and missing callback before delivery"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
        (doseq [opts [{} {:token "token" :acknowledged? (constantly true)}]]
          (should-throw clojure.lang.ExceptionInfo
            (companion/wake! (herdr/herdr owned) "w1:p1" ["-l" "mail token"] opts)))
        (should-not (:prompted @state)))))
  (it "persists observed provisional identity before run and returns original argv"
    (let [calls (atom []) state (atom {}) checkpoints (atom [])
          run (fake-cli calls state)
          original ["claude.exe" "space $ & ' 中文" ""]]
      (with-redefs [companion/process!
                    (fn [argv]
                      (cond
                        (= "pwsh.exe" (first argv)) (response {:pid 41 :start "server-start" :name "herdr"})
                        (= ["session" "list"] (subvec argv 3 5)) (response {:sessions []})
                        (= ["workspace" "create"] (subvec argv 3 5))
                        (response {:result {:workspace {:workspace_id "w1"}
                                            :root_pane {:pane_id "w1:p1" :terminal_id "term_A"}}})
                        (= ["pane" "run"] (subvec argv 3 5))
                        (do (should (:provisional (last @checkpoints)))
                            (should= 42 (:shell-pid (last @checkpoints)))
                            (should= "shell-start" (:owner-start (last @checkpoints)))
                            (swap! calls conj argv) {:exit 0 :out ""})
                        :else (run argv)))
                    herdr/process-owner! (fake-owner state)
                    herdr/runner-state (fn [identity] {:token (:runner-token identity) :phase "started"})
                    recovery/start! (constantly {:phase :running})
                    mailbox/write-companion! (fn [_ identity] (swap! checkpoints conj identity))]
        (let [identity (companion/create! (herdr/herdr {:provisional true}) "uml-viewer-A" (:cwd owned) original)]
          (should= original (:command identity))
          (should= false (:provisional identity))
          (should= "term_A" (:terminal-id identity))
          (should (string? (:runner-token identity)))))))
  (it "resumes cleanup after workspace closure only under the same server identity"
    (let [calls (atom []) state (atom {:fail ["session" "stop"]}) checkpoint (atom nil)]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)
                    mailbox/read-companion (fn [_] @checkpoint)
                    mailbox/write-companion! (fn [_ identity] (reset! checkpoint identity))]
        (should-throw clojure.lang.ExceptionInfo (companion/cleanup! (herdr/herdr owned) "uml-viewer-A"))
        (should= :server-stopping (:cleanup-phase @checkpoint))
        (swap! state dissoc :fail)
        (should= {:released? true} (companion/cleanup! (herdr/herdr owned) "uml-viewer-A"))
        (should= 1 (count (filter #(= ["workspace" "close"] (subvec % 3 5)) @calls))))))
  (it "ignores stale runner state from a different launch token"
    (let [file (java.io.File/createTempFile "herdr-runner-spec" ".json")
          identity {:runner-state (.getAbsolutePath file) :runner-token "current"}]
      (try
        (spit file (json/write-str {:token "stale" :phase "started"}))
        (should-be-nil (herdr/runner-state identity))
        (spit file "broken JSON")
        (should-be-nil (herdr/runner-state identity))
        (spit file (json/write-str {:token "current" :phase "exited" :exit 37}))
        (should= 37 (:exit (herdr/runner-state identity)))
        (finally (.delete file)))))
  (it "explicitly refuses automatic recovery"
    (let [calls (atom []) state (atom {})]
      (with-redefs [companion/process! (fake-cli calls state) herdr/process-owner! (fake-owner state)]
        (should= {:enabled? false} (companion/recovery! (herdr/herdr owned) "uml-viewer-A" nil nil nil false))
        (should-throw clojure.lang.ExceptionInfo
          (companion/recovery! (herdr/herdr owned) "uml-viewer-A" nil nil nil true))))))

(def relaunch-identity
  (merge owned {:runner-token "gen-token" :runner-state "C:/tmp/uml-viewer-A-runner-1.edn"}))

(defn fake-relaunch-cli [calls state]
  (let [run (fake-cli calls state)]
    (fn [argv]
      (cond
        (= "pwsh.exe" (first argv))
        (do (swap! calls conj argv) {:exit (or (:reset-exit @state) 0) :out "" :err ""})
        (= ["pane" "send-keys"] (subvec argv 3 5))
        (do (swap! calls conj argv) {:exit 0 :out "" :err ""})
        (= ["pane" "run"] (subvec argv 3 5))
        (do (swap! calls conj argv) {:exit 0 :out "" :err ""})
        (= ["pane" "process-info"] (subvec argv 3 5))
        (do (swap! calls conj argv)
            (response {:result {:process_info {:pane_id "w1:p1" :shell_pid 42
                                                :foreground_processes (:foreground @state [{:pid 42 :name "pwsh"}])}}}))
        :else (run argv)))))

(defn decoded [argv]
  (String. (.decode (java.util.Base64/getDecoder) ^String (last argv)) "UTF-16LE"))

(defn- cli-op [argv] (vec (take 2 (drop 3 argv))))

(describe "Herdr recovery relaunch"
  (defn launch [calls state]
    (with-redefs [companion/process! (fake-relaunch-cli calls state)
                  herdr/process-owner! (fake-owner state)
                  recovery/write-state! (fn [_ _] nil)
                  recovery/java-command (fn [_ _] ["java" "-cp" "x" "runner" "manifest"])]
      (herdr/relaunch! relaunch-identity)))
  (it "resets killed-TUI terminal modes and the input line before submitting"
    (let [calls (atom []) state (atom {})]
      (launch calls state)
      (let [reset-idx (first (keep-indexed #(when (= "pwsh.exe" (first %2)) %1) @calls))
            keys-idx (first (keep-indexed #(when (= ["pane" "send-keys"] (cli-op %2)) %1) @calls))
            run-idx (first (keep-indexed #(when (= ["pane" "run"] (cli-op %2)) %1) @calls))]
        (should (some? reset-idx))
        (should (some? keys-idx))
        (should (some? run-idx))
        (should (< reset-idx keys-idx run-idx))
        (let [script (decoded (nth @calls reset-idx))]
          (should (str/includes? script "AttachConsole(42"))
          (should (str/includes? script "CreateFile(\"CONOUT$\""))
          (should (str/includes? script "[?1049l"))
          (should (str/includes? script "[?2004l"))
          (should (str/includes? script "[<u")))
        (should= ["herdr" "--session" "uml-viewer-A" "pane" "send-keys" "w1:p1" "ctrl+c"]
                 (nth @calls keys-idx))
        (let [text (last (nth @calls run-idx))]
          (should (str/starts-with? text "& "))
          (should (str/includes? text "'runner'"))))))
  (it "fails closed without submitting when the terminal reset fails"
    (let [calls (atom []) state (atom {:reset-exit 3})]
      (should-throw clojure.lang.ExceptionInfo (launch calls state))
      (should-not (some #(= ["pane" "run"] (cli-op %)) @calls))
      (should-not (some #(= ["pane" "send-keys"] (cli-op %)) @calls))))
  (it "skips the ConPTY reset off Windows"
    (let [calls (atom []) state (atom {})]
      (with-redefs [uml-viewer.adapters.herdr/windows? (constantly false)]
        (launch calls state))
      (should-not (some #(= "pwsh.exe" (first %)) @calls))
      (should-not (some #(= ["pane" "send-keys"] (cli-op %)) @calls))
      (should (some #(= ["pane" "run"] (cli-op %)) @calls))))
  (it "refuses submission when the pane foreground is unknown or occupied"
    (doseq [foreground [nil [{:pid 42 :name "pwsh"} {:pid 7 :name "claude"}]]]
      (let [calls (atom []) state (atom {:foreground foreground})]
        (should-throw clojure.lang.ExceptionInfo (launch calls state))
        (should-not (some #(= "pwsh.exe" (first %)) @calls))
        (should-not (some #(= ["pane" "run"] (cli-op %)) @calls))))))
