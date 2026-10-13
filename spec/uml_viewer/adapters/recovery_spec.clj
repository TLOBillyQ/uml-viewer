(ns uml-viewer.adapters.recovery-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [uml-viewer.adapters.recovery :as recovery]))

(defn fixture []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-contract" (make-array java.nio.file.attribute.FileAttribute 0)))
        identity {:cwd (.getCanonicalPath root) :session "uml-viewer-A"
                  :runner-token "initial" :runner-state (str (io/file root "runner.edn"))}]
    (recovery/register! identity {:pid 10 :start "start"})
    {:root root :identity identity}))
(defn remove-project [root] (doseq [f (reverse (file-seq root))] (io/delete-file f true)))
(defn exit! [identity code]
  (let [state (recovery/status identity)]
    (recovery/write-state! (:runner-state state)
                           {:token (:runner-token state) :phase :exited :child {:pid 20 :start "child"} :exit code})))
(defn- windows? [] (.startsWith (System/getProperty "os.name") "Windows"))
(defn- shell-argv [script]
  (if (windows?) ["cmd.exe" "/c" script] ["/bin/sh" "-c" script]))
(defn- dead-pid []
  (let [p (.start (ProcessBuilder. (into-array String (shell-argv "exit 0"))))]
    (.waitFor p)
    (.pid p)))
(defn- await-until [deadline-ms f]
  (loop []
    (or (f) (when (< (System/currentTimeMillis) deadline-ms) (Thread/sleep 50) (recur)))))
(defn- controller-log [cwd]
  (let [file (io/file cwd ".uml-viewer" "recovery.log")]
    (try (slurp file) (catch Exception _ "no controller log"))))
(defn- controller-argv
  "The production controller argv (recovery/java-command), with the stub init
  inserted ahead of clojure.main's -m so only the external Herdr boundary is
  replaced in the child JVM."
  [manifest driver]
  (let [[java cp-flag cp main-class & opts] (recovery/java-command "controller" manifest)]
    (into [java cp-flag cp main-class "-i" driver] opts)))
;; Only the external Herdr boundary is replaced: relaunch! crosses the real
;; subprocess boundary through companion/process!, then fails like a failed
;; relay. Registration, journal, gate, cancellation and process exit are
;; production code, launched through the production controller argv.
(def controller-stub
  "(require '[uml-viewer.adapters.companion :as companion]
            '[uml-viewer.adapters.herdr :as herdr])
(alter-var-root #'herdr/relaunch!
                (fn [_] (fn [_] (companion/process! %s) (throw (ex-info \"Relay failed\" {})))))")

(describe "Project recovery controller contract"
  (it "persists a finite first retry deadline for a verified abnormal exit"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-contract" (make-array java.nio.file.attribute.FileAttribute 0)))
          identity {:cwd (.getCanonicalPath root) :session "uml-viewer-A"
                    :runner-token "initial" :runner-state (str (io/file root "runner.edn"))}
          launched (atom [])]
      (try
        (recovery/register! identity {:token "controller" :pid 10 :start "start"})
        (recovery/write-state! (:runner-state identity)
                               {:token "initial" :phase :exited :child {:pid 20 :start "child"} :exit 37})
        (recovery/step! identity 1000 (constantly nil) #(swap! launched conj %))
        (should= :backoff (:phase (recovery/status identity)))
        (should= 2000 (:deadline (recovery/status identity)))
        (should= 0 (:attempts (recovery/status identity)))
        (should= [] @launched)
        (recovery/step! identity 1999 (constantly nil) #(swap! launched conj %))
        (should= [] @launched)
        (recovery/step! identity 2000 (constantly nil) #(swap! launched conj %))
        (should= 1 (count @launched))
        (should= 1 (:attempts (recovery/status identity)))
        (should= 1 (:generation (recovery/status identity)))
        (should-not= "initial" (:runner-token (first @launched)))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))
  (it "exhausts exactly three retries at one, two and four seconds"
    (let [{:keys [root identity]} (fixture) launches (atom [])]
      (try
        (doseq [[observed due expected] [[0 1000 1] [1100 3100 2] [3200 7200 3]]]
          (exit! identity 37)
          (recovery/step! identity observed (constantly nil) #(swap! launches conj %))
          (should= due (:deadline (recovery/status identity)))
          (recovery/step! identity (dec due) (constantly nil) #(swap! launches conj %))
          (should= (dec expected) (count @launches))
          (recovery/step! identity due (constantly nil) #(swap! launches conj %))
          (should= expected (count @launches)))
        (exit! identity 37)
        (recovery/step! identity 7300 (constantly nil) #(swap! launches conj %))
        (should= :failed (:phase (recovery/status identity)))
        (should= "Recovery exhausted after three retries" (:reason (recovery/status identity)))
        (recovery/step! identity 100000 (constantly nil) #(swap! launches conj %))
        (should= 3 (count @launches))
        (finally (remove-project root)))))
  (it "resets only after sixty seconds of verified continuous child uptime"
    (let [{:keys [root identity]} (fixture) child {:pid 20 :start "child"}]
      (try
        (exit! identity 37)
        (recovery/step! identity 0 (constantly nil) identity)
        (recovery/step! identity 1000 (constantly nil) identity)
        (let [state (recovery/status identity)]
          (recovery/write-state! (:runner-state state) {:token (:runner-token state) :phase :started :child child}))
        (recovery/step! identity 1100 (constantly child) identity)
        (recovery/step! identity 61099 (constantly child) identity)
        (should= 1 (:attempts (recovery/status identity)))
        (recovery/step! identity 61100 (constantly child) identity)
        (should= 0 (:attempts (recovery/status identity)))
        (exit! identity 37)
        (recovery/step! identity 61200 (constantly nil) identity)
        (should= 62200 (:deadline (recovery/status identity)))
        (finally (remove-project root)))))
  (it "never recovers normal, stale, unknown or unverifiable exits"
    (doseq [event [{:token "initial" :phase :exited :exit 0 :child {:pid 20 :start "child"}}
                   {:token "stale" :phase :exited :exit 37 :child {:pid 20 :start "child"}}
                   {:token "initial" :phase :exited :exit 37}]]
      (let [{:keys [root identity]} (fixture) launches (atom [])]
        (try
          (recovery/write-state! (:runner-state identity) event)
          (recovery/step! identity 0 (constantly nil) #(swap! launches conj %))
          (should (#{:normal :failed} (:phase (recovery/status identity))))
          (recovery/step! identity 99999 (constantly nil) #(swap! launches conj %))
          (should= [] @launches)
          (finally (remove-project root))))))
  (it "allows a bounded grace for the runner to record a child exit"
    (let [{:keys [root identity]} (fixture) launches (atom []) child {:pid 20 :start "child"}]
      (try
        ;; The runner records :started; the child then dies before the
        ;; runner's waitFor/write lands. The controller must wait for the
        ;; exit record instead of failing on the first observation.
        (recovery/write-state! (:runner-state identity) {:token "initial" :phase :started :child child})
        (recovery/step! identity 0 (constantly nil) #(swap! launches conj %))
        (should= :running (:phase (recovery/status identity)))
        (should= 0 (:child-gone-since (recovery/status identity)))
        ;; The exit record lands within the grace window and is recovered.
        (recovery/write-state! (:runner-state identity) {:token "initial" :phase :exited :child child :exit 37})
        (recovery/step! identity 100 (constantly nil) #(swap! launches conj %))
        (should= :backoff (:phase (recovery/status identity)))
        (should= 1100 (:deadline (recovery/status identity)))
        (finally (remove-project root)))))
  (it "fails only when the exit record never arrives within the grace"
    (let [{:keys [root identity]} (fixture) launches (atom []) child {:pid 20 :start "child"}]
      (try
        (recovery/write-state! (:runner-state identity) {:token "initial" :phase :started :child child})
        (recovery/step! identity 1000 (constantly nil) #(swap! launches conj %))
        (should= :running (:phase (recovery/status identity)))
        (recovery/step! identity 2999 (constantly nil) #(swap! launches conj %))
        (should= :running (:phase (recovery/status identity)))
        (recovery/step! identity 3000 (constantly nil) #(swap! launches conj %))
        (should= :running (:phase (recovery/status identity)))
        (recovery/step! identity 3001 (constantly nil) #(swap! launches conj %))
        (should= :failed (:phase (recovery/status identity)))
        (should= "Runner child disappeared without a matching exit; recovery unsupported"
                 (:reason (recovery/status identity)))
        (should= [] @launches)
        (finally (remove-project root)))))
  (it "clears the grace marker when the child is verified alive again"
    (let [{:keys [root identity]} (fixture) child {:pid 20 :start "child"}]
      (try
        (recovery/write-state! (:runner-state identity) {:token "initial" :phase :started :child child})
        (recovery/step! identity 0 (constantly nil) identity)
        (should= 0 (:child-gone-since (recovery/status identity)))
        (recovery/step! identity 100 (constantly child) identity)
        (should (nil? (:child-gone-since (recovery/status identity))))
        (should= :running (:phase (recovery/status identity)))
        (finally (remove-project root)))))
  (it "durable cancellation beats a due retry and never affects another project"
    (let [a (fixture) b (fixture) launches (atom [])]
      (try
        (doseq [p [a b]] (exit! (:identity p) 37) (recovery/step! (:identity p) 0 (constantly nil) #(swap! launches conj %)))
        (recovery/write-state! (:control-file (recovery/reference (:identity a))) {:cancel "controller"})
        (recovery/step! (:identity a) 1000 (constantly nil) #(swap! launches conj %))
        (should= :cancelled (:phase (recovery/status (:identity a))))
        (should= :backoff (:phase (recovery/status (:identity b))))
        (should= [] @launches)
        (finally (doseq [p [a b]] (remove-project (:root p)))))))
  (it "verifies only the exact original controller identity"
    (let [{:keys [root identity]} (fixture)]
      (try
        (with-redefs [recovery/process-identity (fn [pid] (when (= pid 10) {:pid 10 :start "start"}))]
          (should= :running (:phase (recovery/verify! identity))))
        (doseq [state [{:controller {:pid 11 :start "start"}} {:controller {:pid 10 :start "other"}} {:controller {:pid nil :start "start"}}]]
          (recovery/write-state! (:state-file (recovery/reference identity))
                                 (merge (dissoc (recovery/status identity) :controller) state))
          (should-throw clojure.lang.ExceptionInfo (recovery/verify! identity)))
        (finally (remove-project root)))))
  (it "does not replay an uncertain submission after controller rebind"
    (let [{:keys [root identity]} (fixture) launches (atom 0)]
      (try
        (exit! identity 37)
        (recovery/step! identity 0 (constantly nil) identity)
        (recovery/step! identity 1000 (constantly nil) (fn [_] (swap! launches inc) (throw (ex-info "timeout" {}))))
        (should= :failed (:phase (recovery/status identity)))
        (should= 1 (:attempts (recovery/status identity)))
        (recovery/step! identity 999999 (constantly nil) (fn [_] (swap! launches inc)))
        (should= 1 @launches)
        (finally (remove-project root))))))

(describe "read-state transient read retry"
  (it "retries a transient sharing violation while the controller replaces the journal"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-read" (make-array java.nio.file.attribute.FileAttribute 0)))
          target (io/file root "state.edn")
          calls (atom 0)]
      (try
        (spit target "{:phase :running}")
        (with-redefs [clojure.core/slurp (fn [path]
                                           (if (< (swap! calls inc) 3)
                                             (throw (java.io.FileNotFoundException. (str path " (being used by another process)")))
                                             "{:phase :running}"))]
          (should= {:phase :running} (recovery/read-state (str target)))
          (should= 3 @calls))
        (finally (remove-project root)))))
  (it "still fails when the violation outlasts the retry budget"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-read" (make-array java.nio.file.attribute.FileAttribute 0)))
          target (io/file root "state.edn")]
      (try
        (spit target "{:phase :running}")
        (with-redefs [clojure.core/slurp (fn [path] (throw (java.io.FileNotFoundException. (str path " (being used by another process)"))))]
          (should-throw java.io.FileNotFoundException (recovery/read-state (str target))))
        (finally (remove-project root)))))
  (it "does not retry a malformed journal"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-read" (make-array java.nio.file.attribute.FileAttribute 0)))
          target (io/file root "state.edn")]
      (try
        (spit target "{:phase")
        (should-throw RuntimeException (recovery/read-state (str target)))
        (finally (remove-project root))))))

(describe "step! unchanged journal"
  (it "does not rewrite the journal when the step changes nothing"
    (let [{:keys [root identity]} (fixture)]
      (try
        (let [file (io/file (:state-file (recovery/reference identity)))]
          (recovery/step! identity 0 (constantly nil) identity)
          (let [mtime (.lastModified file)]
            (should (.isFile file))
            (Thread/sleep 50)
            (recovery/step! identity 50 (constantly nil) identity)
            (should= mtime (.lastModified file))))
        (finally (remove-project root))))))

(describe "retire-completed!"
  (it "removes a completed lifecycle journal so a fresh launch can register"
    (doseq [phase [:cancelled :failed :normal]]
      (let [{:keys [root identity]} (fixture)]
        (try
          (let [ref (recovery/reference identity)
                state-file (io/file (:state-file ref))
                control-file (io/file (:control-file ref))]
            (recovery/write-state! (:state-file ref) (assoc (recovery/status identity) :phase phase))
            (spit control-file "{:cancel \"controller\"}")
            (should= true (recovery/retire-completed! identity))
            (should-not (.isFile state-file))
            (should-not (.isFile control-file))
            (should (nil? (recovery/status identity))))
          (finally (remove-project root))))))
  (it "refuses to retire a live journal"
    (doseq [phase [:running :backoff :submitting]]
      (let [{:keys [root identity]} (fixture)]
        (try
          (recovery/write-state! (:state-file (recovery/reference identity))
                                 (assoc (recovery/status identity) :phase phase))
          (should-throw clojure.lang.ExceptionInfo (recovery/retire-completed! identity))
          (should= phase (:phase (recovery/status identity)))
          (finally (remove-project root))))))
  (it "leaves an absent journal alone"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-retire" (make-array java.nio.file.attribute.FileAttribute 0)))
          identity {:cwd (.getCanonicalPath root) :session "uml-viewer-A"
                    :runner-token "initial" :runner-state (str (io/file root "runner.edn"))}]
      (try
        (should (nil? (recovery/retire-completed! identity)))
        (finally (remove-project root))))))

(describe "write-state! atomic replacement"
  (it "retries while a reader holds the target file"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-write" (make-array java.nio.file.attribute.FileAttribute 0)))
          target (io/file root "state.edn")]
      (try
        (spit target "{:old true}")
        (with-open [reader (io/input-stream target)]
          (let [closer (future (Thread/sleep 300) (.close reader))]
            (recovery/write-state! target {:new true})
            @closer))
        (should= {:new true} (recovery/read-state (str target)))
        (finally (remove-project root))))))

(describe "detached controller process"
  (it "ends with its terminal phase so the viewer's bounded cancellation is acknowledged"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "recovery-exit" (make-array java.nio.file.attribute.FileAttribute 0)))
          cwd (.getCanonicalPath root)
          ;; The registered supervisor an owned companion records (herdr/create!).
          supervisor {:token (str (java.util.UUID/randomUUID))
                      :manifest (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-launch.edn"))
                      :state-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery.edn"))
                      :control-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-control.edn"))
                      :gate-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-gate.lock"))
                      :lock-file (.getAbsolutePath (io/file cwd ".uml-viewer" "recovery-controller.lock"))}
          identity {:cwd cwd :session "uml-viewer-exit" :runner-token "initial"
                    :runner-state (.getAbsolutePath (io/file cwd ".uml-viewer" "uml-viewer-exit-runner-1.edn"))
                    :supervisor supervisor}
          driver (io/file root "controller-stub.clj")
          process (atom nil)]
      (try
        (spit driver (format controller-stub (pr-str (shell-argv "exit 1"))))
        (recovery/write-state! (:manifest supervisor) identity)
        (let [builder (doto (ProcessBuilder. ^java.util.List
                                             (controller-argv (:manifest supervisor)
                                                              (.getCanonicalPath driver)))
                        (.directory root)
                        (.redirectErrorStream true)
                        (.redirectOutput (io/file cwd ".uml-viewer" "recovery.log"))
                        (.redirectInput (java.lang.ProcessBuilder$Redirect/from
                                         (io/file (if (windows?) "NUL" "/dev/null")))))]
          (reset! process (.start builder)))
        ;; The controller registers, then observes a verified abnormal exit and
        ;; submits one retry that fails after crossing the subprocess boundary.
        (let [registered (await-until (+ (System/currentTimeMillis) 30000) #(recovery/status identity))]
          (when-not registered (println "controller log:" (controller-log cwd)))
          (should registered))
        (recovery/write-state! (:runner-state identity)
                               {:token "initial" :phase :exited :child {:pid (dead-pid) :start "child"} :exit 37})
        (let [terminal (await-until (+ (System/currentTimeMillis) 30000)
                                    #(when (#{:failed :normal :cancelled} (:phase (recovery/status identity)))
                                       (:phase (recovery/status identity))))]
          (when-not terminal (println "controller log:" (controller-log cwd)))
          (should terminal))
        (should= :failed (:phase (recovery/status identity)))
        (should= 1 (:attempts (recovery/status identity)))
        (should= "Recovery submission uncertain: Relay failed" (:reason (recovery/status identity)))
        ;; Closing the viewer runs cancel!, which grants the controller ten
        ;; seconds to acknowledge and exit before companion resources are closed.
        (should= {:enabled? false} (recovery/cancel! identity))
        (should (.waitFor ^Process @process 5000 java.util.concurrent.TimeUnit/MILLISECONDS))
        (should (zero? (.exitValue ^Process @process)))
        (finally
          (when-let [^Process p @process]
            (when (.isAlive p)
              (.destroy p)
              (.waitFor p 5000 java.util.concurrent.TimeUnit/MILLISECONDS)))
          (remove-project root))))))
