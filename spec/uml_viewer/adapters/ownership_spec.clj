(ns uml-viewer.adapters.ownership-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [quil.core :as q]
            [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.domain.mailbox :as mailbox]))

(describe "Recorded companion ownership"
  (it "rebinds a legacy project on restart and closes its companion using startup root after JVM cwd changes"
    (let [a (str (System/getProperty "java.io.tmpdir") "/ownership-restart-a-" (System/nanoTime))
          b (str (System/getProperty "java.io.tmpdir") "/ownership-restart-b-" (System/nanoTime))
          b-record {:backend :tmux :cwd b :session "B" :pane "%2"}
          live (atom #{"A" "B"})
          opened (atom 0)
          previous-root @sketch/!project-root]
      (.mkdirs (io/file a ".metrics"))
      (mailbox/write-companion! a {:cwd a :session "A" :pane "%1"})
      (mailbox/write-companion! b b-record)
      (try
        (with-redefs [sketch/windows? (fn [] true)
                      q/sketch (fn [& _] (swap! opened inc))
                      sketch/tmux! (fn [& args]
                                     (case (first args)
                                       "has-session" {:exit (if (contains? @live (last args)) 0 1)}
                                       "display-message" {:exit 0 :out "UML|A|$1|100|%1"}
                                       "kill-session" (do (swap! live disj (subs (last args) 1)) {:exit 0})
                                       "set-hook" {:exit 0}
                                       (throw (ex-info "Restart must not create replacement" {:args args}))))]
          (sketch/start! (.getPath (io/file a "diagram.edn")) :source true)
          (should= 1 @opened)
          (should= :tmux (:backend (mailbox/read-companion a)))
          (should= "$1" (:session-id (mailbox/read-companion a)))
          (should= #{"A" "B"} @live)
          ;; The default cleanup root is captured during bind, independent of JVM cwd.
          (sketch/shutdown-children!)
          (should= #{"B"} @live)
          (should= {} (mailbox/read-companion a))
          (should= b-record (mailbox/read-companion b)))
        (finally (reset! sketch/!project-root previous-root)))))

  (it "does not execute commands for missing unknown or cross-project records and keeps FIFO mail"
    (let [a (str (System/getProperty "java.io.tmpdir") "/ownership-invalid-" (System/nanoTime))]
      (doseq [record [nil {:backend :unknown :cwd a :session "A" :pane "%1"}
                      {:backend :tmux :cwd (str a "-other") :session "B" :pane "%2"}
                      {:session "A" :pane "%1"}]]
        (mailbox/write-companion! a (or record {}))
        (with-redefs [sketch/tmux! (fn [& _] (throw (Exception. "Must not run")))]
          (should-not (:woke? (sketch/request-agent! a :regen {})))
          (sketch/shutdown-children! a)
          (should= (or record {}) (mailbox/read-companion a))))
      (should= [1 2 3 4] (mapv :id (:queue (edn/read-string (slurp (mailbox/to-agent a))))))))

  (it "retains provisional ownership after fresh creation fails and refuses a second creation"
    (let [root (str (System/getProperty "java.io.tmpdir") "/ownership-partial-" (System/nanoTime))
          creates (atom 0)]
      (with-redefs [sketch/windows? (fn [] false)
                    sketch/tmux! (fn [& args]
                                   (case (first args)
                                     "new-session" (do (swap! creates inc) {:exit 7 :err "partial creation"})
                                     "has-session" {:exit 1}
                                     (throw (ex-info "Unexpected command" {:args args}))))]
        (should-throw clojure.lang.ExceptionInfo (sketch/open-in-terminal! root))
        (should= true (:provisional (mailbox/read-companion root)))
        (should-throw clojure.lang.ExceptionInfo (sketch/open-in-terminal! root))
        (should= 1 @creates))))

  (it "routes a recorded tmux project by its backend even on Windows and preserves another project's mail"
    (let [a (str (System/getProperty "java.io.tmpdir") "/ownership-a-" (System/nanoTime))
          b (str (System/getProperty "java.io.tmpdir") "/ownership-b-" (System/nanoTime))
          delivered (atom [])]
      (mailbox/write-companion! a {:backend :tmux :cwd a :session "A" :pane "%1"})
      (mailbox/write-companion! b {:backend :tmux :cwd b :session "B" :pane "%2"})
      (mailbox/write-command! (mailbox/to-agent b) :regen {})
      (with-redefs [sketch/windows? (fn [] true)
                    sketch/tmux! (fn [& args]
                                   (case (first args)
                                     "has-session" {:exit 0}
                                     "display-message" {:exit 0 :out "UML|A|$1|100|%1"}
                                     "send-keys" (do (swap! delivered conj (nth args 2)) {:exit 0})
                                     (throw (ex-info "Unexpected operation" {:args args}))))]
        (should (:woke? (sketch/request-agent! a :regen {})))
        (should= ["%1" "%1" "%1"] @delivered)
        (should= [{:id 1 :op :regen}] (:queue (edn/read-string (slurp (mailbox/to-agent b)))))
        (should= "B" (:session (mailbox/read-companion b))))))

  (it "retains ownership when kill succeeds but release is not confirmed"
    (let [root (str (System/getProperty "java.io.tmpdir") "/ownership-unreleased-" (System/nanoTime))
          record {:backend :tmux :cwd root :session "A" :pane "%1"}]
      (mailbox/write-companion! root record)
      (with-redefs [sketch/tmux! (fn [& args]
                                 (case (first args)
                                   "display-message" {:exit 0 :out "UML|A|$1|100|%1"}
                                   {:exit 0}))]
        (sketch/shutdown-children! root)
        (should= record (mailbox/read-companion root)))))

  (it "keeps queued mail and both owners when a pane resolves to another session"
    (let [root (str (System/getProperty "java.io.tmpdir") "/ownership-conflict-" (System/nanoTime))
          delivered (atom false)
          record {:backend :tmux :cwd root :session "A" :pane "%1"}]
      (mailbox/write-companion! root record)
      (with-redefs [sketch/tmux! (fn [& args]
                                 (case (first args)
                                   "has-session" {:exit 0}
                                   "display-message" {:exit 0 :out "UML|B|$2|100|%1"}
                                   (do (reset! delivered true) {:exit 0})))]
        (should-not (:woke? (sketch/request-agent! root :regen {})))
        (sketch/shutdown-children! root)
        (should-not @delivered)
        (should= record (mailbox/read-companion root))
        (should= :regen (:op (first (:queue (edn/read-string (slurp (mailbox/to-agent root)))))))))))
