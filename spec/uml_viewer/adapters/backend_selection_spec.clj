(ns uml-viewer.adapters.backend-selection-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.mailbox :as mailbox]
            [uml-viewer.adapters.companion :as companion])
  (:import [java.util.concurrent TimeUnit]))

(describe "read-only companion backend preflight"
  (with root (str (System/getProperty "java.io.tmpdir") "/backend-selection-" (System/nanoTime)))
  (it "defaults fresh launches to verified Herdr on native Windows and macOS"
    (doseq [os ["Mac OS X" "Windows 11"]]
      (let [calls (atom [])
            run (fn [argv] (swap! calls conj argv)
                  {:exit 0 :out (if (= ["herdr" "--version"] argv)
                                  "herdr 0.9.3"
                                  "{\"$schema\":\"fixture\",\"protocol\":22,\"schema_version\":1}")})]
        (should= :herdr (companion/select-backend! @root nil false os run))
        (should= [["herdr" "--version"] ["herdr" "api" "schema" "--json"]] @calls)
        (should-not (.exists (mailbox/companion-file @root))))))
  (it "refuses explicit or unmarked legacy ownership before preflight and retains its bytes"
    (doseq [backend [:tmux :psmux nil]
            restart? [false true]]
      (let [record (cond-> {:cwd @root :session "saved" :pane "%1"}
                     backend (assoc :backend backend))
            run (fn [& _] (throw (AssertionError. "command executed")))]
        (mailbox/write-companion! @root record)
        (let [before (slurp (mailbox/companion-file @root))
              e (try (companion/select-backend! @root nil restart? "Windows" run) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (should e)
          (should= :legacy-ownership (:reason (ex-data e)))
          (should= before (slurp (mailbox/companion-file @root)))))))
  (it "refuses retired backend requests and unsupported Linux before commands"
    (let [run (fn [& _] (throw (AssertionError. "command executed")))]
      (doseq [backend [:tmux :psmux :unknown]]
        (should-throw clojure.lang.ExceptionInfo (companion/select-backend! @root backend false "Windows" run)))
      (should-throw clojure.lang.ExceptionInfo (companion/select-backend! @root nil false "Linux" run))
      (should-not (.exists (mailbox/companion-file @root)))))
  (it "rejects missing restart and corrupt or unknown records without external commands"
    (let [run (fn [& _] (throw (AssertionError. "command executed")))]
      (should-throw (companion/select-backend! @root nil true "Mac OS X" run))
      (mailbox/write-companion! @root {:backend :other})
      (should-throw (companion/select-backend! @root nil false "Mac OS X" run))
      (spit (mailbox/companion-file @root) "[]")
      (should-throw (companion/select-backend! @root nil false "Windows" run))))
  (it "accepts only versions >= 0.9.3 with the bundled schema without creating resources"
    (doseq [os ["Mac OS X" "Windows 11"]
            version [{:exit nil :out ""} {:exit 1 :out "herdr 0.9.3"}
                     {:exit 0 :out "herdr 0.9.2"} {:exit 0 :out "herdr 0.8.9"}
                     {:exit 0 :out "herdr abc"} {:exit 0 :out "herdr 0.9.4-rc.1"}
                     {:exit 0 :out "herdr v0.9.4"}
                     {:exit 0 :out "herdr 0.9.4"} {:exit 0 :out "herdr 0.9.3"}
                     {:exit 0 :out "herdr 0.10.0"} {:exit 0 :out "herdr 1.0.0"}]
            protocol [21 22]]
      (let [calls (atom [])
            supported? (and (= 0 (:exit version))
                            (companion/herdr-version-supported?
                              (second (re-matches #"herdr (\S+)" (:out version)))))
            run (fn [argv] (swap! calls conj argv)
                  (if (= ["herdr" "--version"] argv) version
                    {:exit 0 :out (str "{\"$schema\":\"test\",\"protocol\":" protocol ",\"schema_version\":1}")}))]
        (if (and supported? (= protocol 22))
          (should= :herdr (companion/select-backend! @root :herdr false os run))
          (should-throw clojure.lang.ExceptionInfo
                        (companion/select-backend! @root :herdr false os run)))
        (should= (if supported?
                   [["herdr" "--version"] ["herdr" "api" "schema" "--json"]]
                   [["herdr" "--version"]]) @calls)
        (should-not (.exists (mailbox/companion-file @root)))))))

(describe "ownership preflight validity"
  (it "rejects incomplete and provisional Herdr ownership before any commands"
    (let [root (str (System/getProperty "java.io.tmpdir") "/incomplete-owner-" (System/nanoTime))
          run (fn [& _] (throw (AssertionError. "command executed")))]
      (doseq [record [{:backend :herdr :session "saved" :pane "%1" :cwd root}
                      {:backend :herdr :session "saved" :pane "%1" :cwd root :provisional true}]]
        (mailbox/write-companion! root record)
        (should-throw clojure.lang.ExceptionInfo (companion/select-backend! root nil true "Windows" run))
        (should= record (mailbox/read-companion root)))))
  (it "treats uppercase option-like argv literally"
    (should= {:args ["--BACKEND" "custom.edn" "--restart"] :backend :herdr}
             (companion/selection-args ["--BACKEND" "custom.edn" "--backend" "herdr" "--restart"]))))

(describe "foreground preflight process"
  (it "exits promptly after subprocess stream readers complete"
    (let [dir (.toFile (java.nio.file.Files/createTempDirectory "companion-preflight-"
                                                             (make-array java.nio.file.attribute.FileAttribute 0)))
          driver (java.io.File. dir "driver.clj")
          log (java.io.File. dir "output.txt")
          windows? (.startsWith (System/getProperty "os.name") "Windows")
          shell (if windows? ["cmd.exe" "/c" "echo stream-reader-probe"]
                             ["/bin/sh" "-c" "echo stream-reader-probe"])
          java (str (System/getProperty "java.home") "/bin/java" (when windows? ".exe"))]
      (spit driver
            (str "(require '[uml-viewer.adapters.companion :as companion])\n"
                 "(let [run companion/process!]\n"
                 "  (with-redefs [companion/process!\n"
                 "                (fn [argv]\n"
                 "                  (let [result (run " (pr-str shell) " 10000 nil)]\n"
                 "                    (assert (= 0 (:exit result)))\n"
                 "                    (assert (= \"stream-reader-probe\" (:out result)))\n"
                 "                    (assoc result :out\n"
                 "                      (if (= \"--version\" (second argv))\n"
                 "                        \"herdr 0.9.3\"\n"
                 "                        \"{\\\"$schema\\\":\\\"fixture\\\",\\\"protocol\\\":22,}\"))))]\n"
                 "    (companion/-main " (pr-str (.getPath dir)) " \"Windows\")))"))
      (let [p (.start (doto (ProcessBuilder.
                             (into-array String [java "-cp" (System/getProperty "java.class.path")
                                                 "clojure.main" (.getPath driver)]))
                       (.redirectErrorStream true)
                       (.redirectOutput log)))]
        (try
          (should (.waitFor p 15000 TimeUnit/MILLISECONDS))
          (should= "herdr" (.trim (slurp log)))
          (should= 0 (.exitValue p))
          (finally
            (when (.isAlive p) (.destroy p) (.waitFor p 5000 TimeUnit/MILLISECONDS))
            (doseq [file (reverse (file-seq dir))] (.delete file))))))))

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
