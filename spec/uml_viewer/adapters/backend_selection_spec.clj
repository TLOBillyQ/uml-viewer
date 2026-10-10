(ns uml-viewer.adapters.backend-selection-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.adapters.companion :as companion]))

(describe "read-only companion backend preflight"
  (with root (str (System/getProperty "java.io.tmpdir") "/backend-selection-" (System/nanoTime)))
  (it "uses platform fresh defaults and preserves explicit legacy selections"
    (let [calls (atom [])
          run (fn [argv] (swap! calls conj argv)
                {:exit 0 :out (if (= "psmux.exe" (first argv)) "tmux 3.3.8\npsmux 3.3.8" "tmux 3.5")})]
      (should= :tmux (companion/select-backend! @root nil false "Mac OS X" run))
      (should= :psmux (companion/select-backend! @root nil false "Windows 11" run))
      (should= :tmux (companion/select-backend! @root :tmux false "Linux" run))
      (should-throw clojure.lang.ExceptionInfo (companion/select-backend! @root :unknown false "Windows" run))
      (should-not (.exists (mailbox/companion-file @root)))))
  (it "routes restart by persisted provenance and refuses a conflicting request without commands"
    (let [record {:cwd @root :session "saved" :pane "%1"}
          calls (atom [])
          run (fn [argv] (swap! calls conj argv) {:exit 0 :out "tmux 3.5"})]
      (mailbox/write-companion! @root record)
      (should= :tmux (companion/select-backend! @root nil true "Windows" run))
      (reset! calls [])
      (should-throw clojure.lang.ExceptionInfo
                    (companion/select-backend! @root :psmux true "Windows" run))
      (should= [] @calls)
      (should= record (mailbox/read-companion @root))))
  (it "rejects missing restart and corrupt or unknown records without external commands"
    (let [run (fn [& _] (throw (AssertionError. "command executed")))]
      (should-throw (companion/select-backend! @root nil true "Mac OS X" run))
      (mailbox/write-companion! @root {:backend :other})
      (should-throw (companion/select-backend! @root nil false "Mac OS X" run))
      (spit (mailbox/companion-file @root) "[]")
      (should-throw (companion/select-backend! @root nil false "Windows" run))))
  (it "checks only local version and bundled schema on both platforms and never pretends adapter availability"
    (doseq [os ["Mac OS X" "Windows 11"]
            version [{:exit nil :out ""} {:exit 1 :out "herdr 0.9.3"}
                     {:exit 0 :out "herdr 0.9.4"} {:exit 0 :out "herdr 0.9.3"}]
            protocol [21 22]]
      (let [calls (atom [])
            run (fn [argv] (swap! calls conj argv)
                  (if (= ["herdr" "--version"] argv) version
                    {:exit 0 :out (str "{\"$schema\":\"test\",\"protocol\":" protocol ",\"schema_version\":1}")}))]
        (try
          (companion/select-backend! @root :herdr false os run)
          (should false)
          (catch clojure.lang.ExceptionInfo e
            (should (re-find (if (and (= version {:exit 0 :out "herdr 0.9.3"}) (= protocol 22))
                              #"issue #17" #"Herdr 0.9.3.*https://herdr.dev") (.getMessage e)))))
        (should= (if (= version {:exit 0 :out "herdr 0.9.3"})
                   [["herdr" "--version"] ["herdr" "api" "schema" "--json"]]
                   [["herdr" "--version"]]) @calls)
        (should-not (.exists (mailbox/companion-file @root)))))))

(describe "ownership preflight validity"
  (it "rejects incomplete psmux and provisional ownership before any commands"
    (let [root (str (System/getProperty "java.io.tmpdir") "/incomplete-owner-" (System/nanoTime))
          run (fn [& _] (throw (AssertionError. "command executed")))]
      (doseq [record [{:backend :psmux :session "saved" :pane "%1" :cwd root}
                      {:backend :tmux :session "saved" :pane "%1" :cwd root :provisional true}]]
        (mailbox/write-companion! root record)
        (should-throw clojure.lang.ExceptionInfo (companion/select-backend! root nil true "Windows" run))
        (should= record (mailbox/read-companion root)))))
  (it "treats uppercase option-like argv literally"
    (should= {:args ["--BACKEND" "custom.edn" "--restart"] :backend :tmux}
             (companion/selection-args ["--BACKEND" "custom.edn" "--backend" "tmux" "--restart"]))))

(describe "Herdr schema failures"
  (it "rejects missing malformed or failed bundled schema without further commands"
    (doseq [schema [{:exit nil :out ""} {:exit 1 :out "{\"$schema\":\"fixture\",\"protocol\":22,"}
                    {:exit 0 :out "not json"} {:exit 0 :out "{\"$schema\":\"fixture\",\"protocol\":21,"}]]
      (let [root (str (System/getProperty "java.io.tmpdir") "/schema-failure-" (System/nanoTime))
            calls (atom [])
            run (fn [argv] (swap! calls conj argv)
                  (if (= "--version" (second argv)) {:exit 0 :out "herdr 0.9.3"} schema))]
        (should-throw clojure.lang.ExceptionInfo (companion/select-backend! root :herdr false "Mac OS X" run))
        (should= [["herdr" "--version"] ["herdr" "api" "schema" "--json"]] @calls)
        (should-not (.exists (mailbox/companion-file root)))))))
