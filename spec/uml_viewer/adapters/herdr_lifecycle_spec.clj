(ns uml-viewer.adapters.herdr-lifecycle-spec
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [clojure.java.io :as io]
            [quil.core :as q]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.herdr :as herdr]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.mailbox :as mailbox]))

(defn lifecycle-project [suffix]
  (let [root (.getCanonicalPath (io/file "target" (str "herdr-fifo-" suffix "-" (System/nanoTime))))
        diagram (io/file root "diagram.edn")
        record {:backend :herdr :owner-role :companion :cwd root
                :session (str "uml-viewer-" suffix) :socket (.getAbsolutePath (io/file root "herdr.sock"))
                :workspace "w1" :pane "w1:p1" :terminal-id (str "term_" suffix)
                :shell-pid 42 :owner-start (if (companion/windows?) "shell-start" "Sun Oct 11 12:00:00 2026")
                :server-pid 41 :server-start (if (companion/windows?) "server-start" "Sun Oct 11 12:00:00 2026")
                :command ["claude"]}]
    (.mkdirs (io/file root ".metrics"))
    (spit diagram (pr-str {:title suffix :hierarchical true
                          :classes [{:id :demo.alpha :ns "demo.alpha" :name "Alpha"}]
                          :edges [] :order [:demo.alpha]
                          :proposals [{:id :saved :name "Saved" :layers []}]}))
    (mailbox/write-companion! root record)
    {:root root :diagram diagram :record record}))

(defn lifecycle-process [projects consume failure]
  (fn [argv]
    (if (= "herdr" (first argv))
      (let [[_ _ session & args] argv
            {:keys [root record]} (or (get projects session)
                                     (throw (ex-info "Unowned session" {:session session})))
            value (case (vec (take 2 args))
                    ["status" "server"] {:session session :socket (:socket record) :running true
                                            :version "0.9.3" :protocol 22 :compatible true :endpoint_compatible true}
                    ["api" "snapshot"] {:result {:snapshot {:version "0.9.3" :protocol 22
                                                                :workspaces [{:workspace_id "w1"}]
                                                                :panes [{:pane_id "w1:p1" :workspace_id "w1"
                                                                         :terminal_id (:terminal-id record) :cwd root}]
                                                                :agents [{:pane_id "w1:p1" :terminal_id (:terminal-id record) :agent "claude"}]}}}
                    ["pane" "process-info"] {:result {:process_info {:pane_id "w1:p1" :shell_pid 42}}}
                    ["agent" "prompt"] (when-not @failure (consume (get projects session)))
                    (throw (ex-info "Unexpected lifecycle operation" {:argv argv})))]
        (if (and @failure (= ["agent" "prompt"] (vec (take 2 args))))
          {:exit 1 :out "" :err @failure}
          {:exit 0 :out (json/write-str value) :err ""}))
      ;; The OS process-creation boundary is controlled along with the Herdr CLI.
      (let [script (when (= "pwsh.exe" (first argv))
                     (String. (.decode (java.util.Base64/getDecoder) (last argv)) "UTF-16LE"))
            server? (if script (str/includes? script "GetProcessById(41)") (= "41" (nth argv 2 nil)))]
        (if script
          {:exit 0 :out (json/write-str {:pid (if server? 41 42)
                                       :start (if server? "server-start" "shell-start") :name "pwsh"})}
          {:exit 0 :out (str "Sun Oct 11 12:00:00 2026 " (if server? "herdr" "sh"))})))))

(describe "Herdr companion lifecycle"
  ;; A mutated startup branch can bypass create!/open-herdr-companion! mocks.
  ;; Per-example CLI fakes below may override this fail-closed outer boundary.
  (around [run-spec]
    (let [blocked (fn [& args]
                    (throw (AssertionError. (str "Unexpected lifecycle process: " (pr-str args)))))]
      (with-redefs [companion/process! blocked
                    sketch/run-command blocked]
        (run-spec))))
  (it "routes FIFO mail through the recorded backend and returns display only to its project"
    (let [a (lifecycle-project "A") b (lifecycle-project "B")
          projects (into {} (map (fn [p] [(get-in p [:record :session]) p]) [a b]))
          failure (atom "pane_not_found: owned agent unavailable")
          contexts (atom {}) handled (atom [])
          consume (fn [{:keys [root diagram]}]
                    (loop []
                      (when-let [cmd (mailbox/take-command! (mailbox/to-agent root))]
                        (when (= :context (:op cmd))
                          (swap! contexts assoc root (select-keys cmd [:context :proposal-id :name])))
                        (swap! handled conj [root (:op cmd) (get @contexts root)])
                        (when (#{:regen :refresh-crap :refresh-mutate} (:op cmd))
                          (mailbox/write-command! (mailbox/to-viewer root) :display {:path "diagram.edn"}))
                        (recur))))
          run (lifecycle-process projects consume failure)
          state-a (assoc (document/waiting-state (.getPath (:diagram a)) (:root a)) :project-root (:root a))
          state-b (assoc (document/waiting-state (.getPath (:diagram b)) (:root b)) :project-root (:root b))]
      (try
        (with-redefs [companion/process! run]
          (let [diagnostic (java.io.StringWriter.)]
            (binding [*err* diagnostic]
              (sketch/mail-context! {:path (.getPath (:diagram a)) :project-root (:root a)
                                    :proposal-id :saved :doc {:proposals [{:id :saved :name "Saved"}]}})
              (should-not (:woke? (sketch/request-regen! (:root a)))))
            (should (str/includes? (str diagnostic) "pane_not_found")))
          (should= [:context :regen] (mapv :op (mailbox/pending (mailbox/to-agent (:root a)) 0)))
          (should= [] (mailbox/pending (mailbox/to-agent (:root b)) 0))
          (should= (:record a) (mailbox/read-companion (:root a)))
          (reset! failure nil)
          (should (sketch/notify-agent! (:root a)))
          (should= [[(:root a) :context {:context :proposal :proposal-id :saved :name "Saved"}]
                    [(:root a) :regen {:context :proposal :proposal-id :saved :name "Saved"}]] @handled)
          (should= [] (mailbox/pending (mailbox/to-agent (:root a)) 0))
          (let [shown (document/poll-mail state-a)]
            (should-not (:waiting shown))
            (should= "A" (get-in shown [:scene :diagram :title]))
            (should= [:saved] (mapv :id (get-in shown [:doc :proposals])))
            (should= [] (mailbox/pending (mailbox/to-viewer (:root a)) 0))
            (let [diagram (:diagram a)]
              (spit diagram (pr-str (assoc (:doc shown) :title "Regenerated")))
              (should (.setLastModified diagram (+ 2000 (:mtime shown))))
              (let [reloaded (document/maybe-reload shown)]
                (should= "Regenerated" (get-in reloaded [:scene :diagram :title]))
                (spit (io/file (:root a) ".metrics" "crap.edn")
                      (pr-str {:entries [{:name "run" :namespace "demo.alpha"
                                          :complexity 1 :coverage 100.0 :crap 1.0}]}))
                (let [painted (document/maybe-reload reloaded)]
                  (should= 1.0 (get-in painted [:doc :classes 0 :crap :mu]))
                  (should-be-nil (get-in painted [:doc :classes 0 :mutation]))))))
          (should (:waiting (document/poll-mail state-b)))
          (should (:woke? (sketch/request-agent! (:root a) :refresh-crap {:target {:id :demo.alpha}})))
          (should= {:context :proposal :proposal-id :saved :name "Saved"} (last (last @handled)))
          (sketch/mail-context! {:path (.getPath (:diagram a)) :project-root (:root a)})
          (should (:woke? (sketch/request-agent! (:root a) :refresh-mutate {:target {:id :demo.alpha}})))
          (should= {:context :real} (last (last @handled)))
          (should (:woke? (sketch/request-regen! (:root b))))
          (should= "B" (get-in (document/poll-mail state-b) [:scene :diagram :title]))
          (should= (:record b) (mailbox/read-companion (:root b))))
        (finally
          (doseq [p [a b] f (reverse (file-seq (io/file (:root p))))] (io/delete-file f true))))))

  (it "retains queued mail and diagnoses missing, unknown or ambiguous ownership without running a process"
    (doseq [change [:missing :unknown :ambiguous]]
      (let [{:keys [root record]} (lifecycle-project "invalid")
            diagnostic (java.io.StringWriter.)]
        (try
          (case change
            :missing (io/delete-file (mailbox/companion-file root))
            :unknown (mailbox/write-companion! root (assoc record :backend :unknown))
            :ambiguous (mailbox/write-companion! root (dissoc record :pane)))
          (with-redefs [companion/process! (fn [& _] (throw (AssertionError. "must not guess a target")))]
            (binding [*err* diagnostic]
              (should-not (:woke? (sketch/request-regen! root)))))
          (should (str/includes? (str diagnostic) "ownership is missing, ambiguous"))
          (should= [{:id 1 :op :regen}] (mailbox/pending (mailbox/to-agent root) 0))
          (finally (doseq [f (reverse (file-seq (io/file root)))] (io/delete-file f true)))))))

  (it "confirms FIFO consumption instead of treating delivery or echo as success"
    (let [root (str (System/getProperty "java.io.tmpdir") "/herdr-mail-" (System/nanoTime))
          record {:backend :herdr :owner-role :companion :cwd root :session "owned"
                  :socket "owned.sock" :workspace "w1" :pane "w1:p1"
                  :terminal-id "term_owned" :shell-pid 123 :owner-start "456"
                  :server-pid 789 :server-start "101"}
          consumed (atom [])]
      (mailbox/write-companion! root record)
      (mailbox/write-command! (mailbox/to-agent root) :context {:context :real})
      (with-redefs [herdr/herdr identity
                    companion/wake! (fn [_ _ _ {:keys [token acknowledged?]}]
                                      (should-not (acknowledged? token))
                                      (loop []
                                        (when-let [cmd (mailbox/take-command! (mailbox/to-agent root))]
                                          (swap! consumed conj (:op cmd))
                                          (recur)))
                                      (should (acknowledged? token)))]
        (should (:woke? (sketch/request-agent! root :regen {})))
        (should= [:context :regen] @consumed))
      (with-redefs [herdr/herdr identity
                    companion/wake! (fn [& _] (throw (ex-info "agent not ready" {})))]
        (should-not (:woke? (sketch/request-agent! root :regen {})))
        (should= [:regen] (mapv :op (:queue (mailbox/read-mailbox (mailbox/to-agent root))))))))

  (it "opens the Windows full session client with nesting disabled only in its child environment"
    (let [calls (atom [])]
      (with-redefs [companion/windows? (constantly true)
                    companion/process! (fn [argv timeout env-remove]
                                         (swap! calls conj [argv timeout env-remove])
                                         {:exit 0 :out ""})]
        (should= :windows-terminal (:terminal (sketch/open-herdr-window! "uml-viewer-owned")))
        (let [[argv _ removed] (first @calls)
              script (String. (.decode (java.util.Base64/getDecoder) (last argv)) "UTF-16LE")]
          (should= "wt.exe" (first argv))
          (should (clojure.string/includes? script "session attach 'uml-viewer-owned'"))
          (should (clojure.string/includes? script "$env:HERDR_ENV=$null"))
          (should= ["HERDR_ENV" "NO_COLOR"] removed)))))
  (it "retains a single owned companion when terminal connection fails"
    (let [root (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir") (str "herdr-lifecycle-" (System/nanoTime))))
          creates (atom 0)
          observed {:socket "owned.sock" :workspace "w1" :pane "w1:p1"
                    :terminal-id "term_owned" :shell-pid 123 :owner-start "456" :server-pid 789 :server-start "101"}]
      (with-redefs [sketch/claude-executable (fn [] "claude.exe")
                    herdr/herdr (fn [info] info)
                    companion/create! (fn [info session cwd command]
                                        (swap! creates inc)
                                        (should (:provisional (mailbox/read-companion root)))
                                        (merge (dissoc info :provisional) observed))
                    sketch/open-herdr-window! (fn [session] {:terminal :none :session session})]
        (should= :none (:terminal (sketch/open-herdr-companion! root)))
        (let [record (mailbox/read-companion root)]
          (should= :herdr (:backend record))
          (should (<= (count (:session record)) 64))
          (should-not (:provisional record))
          (should= root (:cwd record))
          (should-throw clojure.lang.ExceptionInfo (sketch/open-herdr-companion! root))
          (should= record (mailbox/read-companion root)))
        (should= 1 @creates))))

  (it "keeps diagnostic ownership after partial creation and refuses replacement"
    (let [root (str (System/getProperty "java.io.tmpdir") "/herdr-partial-" (System/nanoTime))
          creates (atom 0)]
      (with-redefs [sketch/claude-executable (fn [] "claude.exe")
                    herdr/herdr identity
                    companion/create! (fn [& _] (swap! creates inc) (throw (ex-info "launch failed" {})))]
        (should-throw clojure.lang.ExceptionInfo (sketch/open-herdr-companion! root))
        (should (:provisional (mailbox/read-companion root)))
        (should-throw clojure.lang.ExceptionInfo (sketch/open-herdr-companion! root))
        (should= 1 @creates))))

  (it "consumes an early display written during companion startup"
    (let [root (.getCanonicalPath (io/file (System/getProperty "java.io.tmpdir") (str "herdr-display-" (System/nanoTime))))
          diagram (io/file root "diagram.edn")
          opts (atom nil)]
      (io/make-parents diagram)
      (spit diagram "{:layers []}")
      (mailbox/write-command! (mailbox/to-viewer root) :display {:path "stale.edn"})
      (with-redefs [sketch/open-herdr-companion!
                    (fn [_] (mailbox/write-command! (mailbox/to-viewer root) :display {:path (.getPath diagram)}))
                    q/sketch (fn [& args] (reset! opts (apply hash-map args)))
                    uml-viewer.adapters.sketch/install-shutdown-hook! (fn [])
                    sketch/setup (fn [_ _] (document/waiting-state "waiting.edn" root))]
        (sketch/start! "waiting.edn" :source false root :herdr)
        (let [state (document/poll-mail ((:setup @opts)))]
          (should= (.getPath diagram) (:path state))
          (should-not (:waiting state))
          (should= [] (:queue (mailbox/read-mailbox (mailbox/to-viewer root))))))))

  (it "clears only confirmed ownership and leaves another project untouched"
    (let [root (str (System/getProperty "java.io.tmpdir") "/herdr-close-" (System/nanoTime))
          other (str root "-other")
          record {:backend :herdr :owner-role :companion :cwd root :session "owned"
                  :socket "owned.sock" :workspace "w1" :pane "w1:p1"
                  :terminal-id "term_owned" :shell-pid 123 :owner-start "456" :server-pid 789 :server-start "101"}
          closed (atom [])]
      (mailbox/write-companion! root record)
      (mailbox/write-companion! other (assoc record :cwd other :session "other"))
      (with-redefs [herdr/herdr identity
                    companion/cleanup! (fn [_ _] (throw (ex-info "cannot confirm release" {})))]
        (sketch/shutdown-children! root)
        (should= record (mailbox/read-companion root)))
      (with-redefs [herdr/herdr identity
                    companion/cleanup! (fn [info session] (swap! closed conj [(:cwd info) session]))]
        (sketch/shutdown-children! root)
        (sketch/shutdown-children! root)
        (should= [[root "owned"]] @closed)
        (should= {} (mailbox/read-companion root))
        (should= "other" (:session (mailbox/read-companion other)))))))
