(ns uml-viewer.adapters.ownership-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [quil.core :as q]
            [uml-viewer.application.document :as document]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.domain.mailbox :as mailbox]))

(describe "Recorded companion ownership"
  (it "keeps startup mail and view ownership when display points into another project's metrics directory"
    (let [a (str (System/getProperty "java.io.tmpdir") "/root-mail-a-" (System/nanoTime))
          b (str (System/getProperty "java.io.tmpdir") "/root-mail-b-" (System/nanoTime))
          diagram (io/file b "external.edn")]
      (.mkdirs (io/file b ".metrics"))
      (spit diagram "{:layers []}")
      (mailbox/write-command! (mailbox/to-viewer a) :display {:path (.getAbsolutePath diagram)})
      (mailbox/write-command! (mailbox/to-viewer b) :quit-for-restart {})
      (let [state (document/poll-mail (assoc (document/waiting-state "waiting.edn" a) :project-root a :mail-seen 0))]
        (should= a (:project-root state))
        (should= (.getAbsolutePath diagram) (:path state))
        (should-not (:quit-for-restart state))
        (document/save-session! (assoc state :cam-x 42))
        (should= 42 (:cam-x (mailbox/read-session a)))
        (should-be-nil (mailbox/read-session b))
        (should= a (:project-root (document/restart-state (.getAbsolutePath diagram) a)))
        (should= 42 (:cam-x (document/restart-state (.getAbsolutePath diagram) a)))
        (should= 1 (count (:queue (edn/read-string (slurp (mailbox/to-viewer b)))))))))

  (it "refuses explicit and unmarked legacy ownership for wake restart creation and cleanup"
    (doseq [backend [:tmux :psmux nil]]
      (let [root (str (System/getProperty "java.io.tmpdir") "/legacy-owner-" (System/nanoTime))
            other (str root "-other")
            record (cond-> {:cwd root :session "A" :pane "%1" :window-id "99"
                            :session-id "$1" :server-pid "100" :owner-start "123"}
                     backend (assoc :backend backend))
            other-record {:backend :herdr :cwd other :session "B"}
            calls (atom [])]
        (mailbox/write-companion! root record)
        (mailbox/write-companion! other other-record)
        (mailbox/write-command! (mailbox/to-agent root) :context {:context :real})
        (mailbox/write-command! (mailbox/to-viewer root) :display {:path "diagram.edn"})
        (mailbox/write-command! (mailbox/to-agent other) :regen {})
        (mailbox/write-session! root {:path "diagram.edn" :cam-x 42})
        (let [owner-before (slurp (mailbox/companion-file root))
              session-before (slurp (mailbox/session-file root))
              viewer-before (slurp (mailbox/to-viewer root))
              other-before (slurp (mailbox/to-agent other))]
          (with-redefs [companion/process! (fn [argv] (swap! calls conj argv) (throw (AssertionError. "Must not execute")))
                        q/sketch (fn [& _] (throw (AssertionError. "Must not open viewer")))]
            (let [e (try (sketch/open-in-terminal! root) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (should e)
              (should= :legacy-ownership (:reason (ex-data e)))
              (should-contain "old release" (.getMessage e))
              (should-contain "Do not use --restart to migrate" (.getMessage e)))
            (should-throw clojure.lang.ExceptionInfo (sketch/start! "diagram.edn" :source true root))
            (should-not (:woke? (sketch/request-agent! root :regen {})))
            (sketch/shutdown-children! root)
            (should= [] @calls)
            (should= owner-before (slurp (mailbox/companion-file root)))
            (should= session-before (slurp (mailbox/session-file root)))
            (should= viewer-before (slurp (mailbox/to-viewer root)))
            (should= other-before (slurp (mailbox/to-agent other)))
            (should= other-record (mailbox/read-companion other))
            (should= [{:id 1 :op :context :context :real} {:id 2 :op :regen}]
                     (:queue (edn/read-string (slurp (mailbox/to-agent root))))))))))

  (it "does not execute commands for missing unknown cross-project or wrong-role records and keeps FIFO mail"
    (let [root (str (System/getProperty "java.io.tmpdir") "/ownership-invalid-" (System/nanoTime))]
      (doseq [record [nil {:backend :unknown :cwd root :session "A" :pane "%1"}
                      {:backend :herdr :cwd (str root "-other") :session "B" :pane "%2"}
                      {:backend :herdr :cwd root :session "A" :pane "%1" :owner-role :launcher}
                      {:backend :herdr :cwd root :session "A" :pane "%1" :owner-role :jvm}]]
        (mailbox/write-companion! root (or record {}))
        (with-redefs [companion/process! (fn [& _] (throw (AssertionError. "Must not execute")))]
          (should-not (:woke? (sketch/request-agent! root :regen {})))
          (sketch/shutdown-children! root)
          (should= (or record {}) (mailbox/read-companion root))))
      (should= [1 2 3 4 5] (mapv :id (:queue (edn/read-string (slurp (mailbox/to-agent root))))))))

  (it "preserves corrupt present ownership instead of creating fresh resources"
    (doseq [contents ["{" "[:invalid]" "nil"]]
      (let [root (str (System/getProperty "java.io.tmpdir") "/corrupt-owner-" (System/nanoTime))
            file (mailbox/companion-file root)
            touched (atom false)]
        (io/make-parents file)
        (spit file contents)
        (with-redefs [companion/process! (fn [& _] (reset! touched true) (throw (AssertionError. "Must not execute")))]
          (should-throw clojure.lang.ExceptionInfo (sketch/open-in-terminal! root))
          (should-not @touched)
          (should= contents (slurp file)))))))
