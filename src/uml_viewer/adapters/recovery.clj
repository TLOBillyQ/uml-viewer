(ns uml-viewer.adapters.recovery
  "Durable project controller. The Viewer owns only immutable references."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.domain.mailbox :as mailbox])
  (:import [java.nio.file Files]
           [java.io RandomAccessFile]
           [java.lang ProcessHandle ProcessBuilder ProcessBuilder$Redirect]))

(def backoff-ms [1000 2000 4000])
(def reset-ms 60000)
;; The runner records a child exit only after waitFor returns; the controller
;; must not verdict a missing record before this bounded grace elapses.
(def gone-grace-ms 2000)
(defn clock [] (quot (System/nanoTime) 1000000))

(defn write-state!
  "Atomic replacement with a unique temporary file; no shared .tmp writer."
  [path value]
  (io/make-parents path)
  (let [file (io/file path)
        tmp (Files/createTempFile (.toPath (.getParentFile file)) "recovery-" ".tmp"
                                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile tmp) (pr-str value))
      (mailbox/move-with-retry! tmp (.toPath file))
      value
      (finally (Files/deleteIfExists tmp)))))

(defn- slurp-with-retry
  "Bounded retry past transient Windows sharing violations: the controller
  replaces the journal atomically while the viewer polls it, and one
  transient open failure must not throw in the draw loop."
  [path]
  (let [deadline (+ (System/nanoTime) (* 2000 1000000))]
    (loop []
      (let [result (try
                     (slurp path)
                     (catch java.io.IOException e
                       (if (< (System/nanoTime) deadline)
                         :retry
                         (throw e))))]
        (if (= :retry result)
          (do (Thread/sleep 25)
              (recur))
          result)))))

(defn read-state [path]
  (when (and path (.isFile (io/file path)))
    (edn/read-string (slurp-with-retry path))))

(defn reference [identity]
  (or (:supervisor identity)
      {:token "controller" :state-file (str (io/file (:cwd identity) ".uml-viewer" "recovery.edn"))
       :control-file (str (io/file (:cwd identity) ".uml-viewer" "recovery-control.edn"))
       :gate-file (str (io/file (:cwd identity) ".uml-viewer" "recovery-gate.lock"))}))

(defn status [identity] (read-state (:state-file (reference identity))))
(defn- save! [identity state] (write-state! (:state-file (reference identity)) state))
(defn process-identity [pid]
  (let [handle (.orElse (ProcessHandle/of (long pid)) nil)]
    (when (and handle (.isAlive handle))
      (when-let [start (.orElse (.startInstant (.info handle)) nil)]
        {:pid (.pid handle) :start (str start)}))))

(defn register! [identity controller]
  (when (status identity)
    (throw (ex-info "Recovery journal already exists; refusing replacement" {})))
  (save! identity {:token (:token (reference identity)) :session (:session identity) :cwd (:cwd identity)
                   :controller controller :phase :running :generation 0 :attempts 0 :submitted-at (clock)
                   :runner-token (:runner-token identity) :runner-state (:runner-state identity)}))

(defn- matching! [identity state]
  (when-not (and (= (:token (reference identity)) (:token state))
                 (= (:cwd identity) (:cwd state)) (= (:session identity) (:session state)))
    (throw (ex-info "Recovery controller identity is missing or changed" {:state state})))
  state)

(defn- runner [state]
  (let [file (:runner-state state)]
    (when (.isFile (io/file file))
      (try (or (read-state file) nil)
           (catch Exception _ (json/read-str (slurp file) :key-fn keyword))))))

(defn- stopped [state reason] (assoc state :phase :failed :reason reason))
(defn- absent-child? [child owner]
  (and (map? child) (pos-int? (:pid child)) (string? (:start child))
       (nil? (owner (:pid child)))))

(defn step!
  "One controller transaction at the external clock/process boundary. launch! must
  verify pane ownership/foreground and submit once. An ambiguous submission stops."
  [identity now owner launch!]
  (let [state (matching! identity (status identity))
        control (read-state (:control-file (reference identity)))
        result
        (cond
          (= (:token state) (:cancel control)) (assoc state :phase :cancelled :reason "Viewer closed; recovery revoked")
          (#{:failed :cancelled :normal} (:phase state)) state
          (= :backoff (:phase state))
          (if (< now (:deadline state)) state
            (let [generation (inc (:generation state))
                  token (str (java.util.UUID/randomUUID))
                  pending (-> state (assoc :phase :submitting :generation generation :runner-token token
                                            :runner-state (str (io/file (:cwd identity) ".uml-viewer"
                                                                       (str (:session identity) "-runner-" generation ".edn")))
                                            :submitted-at now)
                              (update :attempts inc) (dissoc :deadline :uptime-since :child))]
              ;; Journal before the external side effect. Never replay :submitting.
              (save! identity pending)
              (try (launch! (assoc identity :runner-token token :runner-state (:runner-state pending)))
                   (assoc pending :phase :running)
                   (catch Exception e (stopped pending (str "Recovery submission uncertain: " (.getMessage e)))))))
          (= :submitting (:phase state)) (stopped state "Unacknowledged recovery submission; refusing duplicate")
          :else
          (let [event (try (runner state) (catch Exception _ nil))
                phase (some-> (:phase event) keyword)
                child (:child event)]
            (cond
              (nil? event) (if (> (- now (or (:submitted-at state) now)) 30000)
                            (stopped state "Runner acknowledgement missing; exit cause unknown") state)
              (not= (:runner-token state) (:token event)) (stopped state "Runner generation changed; exit cause unknown")
              (= :started phase)
              (if (and child (= child (owner (:pid child))))
                (let [since (or (:uptime-since state) now)]
                  (cond-> (assoc (dissoc state :child-gone-since) :child child :uptime-since since)
                    (>= (- now since) reset-ms) (assoc :attempts 0)))
                (let [gone-since (or (:child-gone-since state) now)]
                  (if (> (- now gone-since) gone-grace-ms)
                    (stopped state "Runner child disappeared without a matching exit; recovery unsupported")
                    (assoc state :child-gone-since gone-since))))
              (= :exited phase)
              (if (and (integer? (:exit event)) (absent-child? child owner)
                       (or (nil? (:child state)) (= child (:child state))))
                (cond
                  (zero? (:exit event)) (assoc state :phase :normal :child child :exit 0 :reason "Companion exited normally")
                  (>= (:attempts state) (count backoff-ms)) (assoc (stopped state "Recovery exhausted after three retries") :exit (:exit event))
                  :else (assoc state :phase :backoff :child child :exit (:exit event)
                                     :deadline (+ now (nth backoff-ms (:attempts state)))))
                (stopped state "Unverified child exit; recovery unsupported"))
              :else (stopped state "Runner exit cause unknown; recovery unsupported"))))]
    ;; Rewriting an unchanged journal only widens the reader/writer race.
    (when (not= result state) (save! identity result))
    result))

(defn with-gate! [identity f]
  (let [file (:gate-file (reference identity))]
    (io/make-parents file)
    (with-open [raf (RandomAccessFile. file "rw") channel (.getChannel raf) lock (.lock channel)] (f))))

(defn verify!
  "Verify the original registered controller. Completed failed/normal/cancelled
  lifecycle remains valid history; a live phase requires the original PID/start."
  [identity]
  (let [state (matching! identity (status identity))
        controller (:controller state)]
    (when-not (and (map? controller) (pos-int? (:pid controller)) (string? (:start controller)))
      (throw (ex-info "Recovery process identity is incomplete" {})))
    (when-not (or (#{:cancelled :failed :normal} (:phase state))
                  (= controller (process-identity (:pid controller))))
      (throw (ex-info "Recovery controller disappeared; refusing replacement" {:state state})))
    state))

(defn cancel! [identity]
  (verify! identity)
  (with-gate! identity
    #(write-state! (:control-file (reference identity)) {:cancel (:token (reference identity))}))
  ;; The detached controller acknowledges and exits before resources are closed.
  (let [deadline (+ (clock) 10000)]
    (loop []
      (let [state (verify! identity)]
        (if (and (#{:cancelled :failed :normal} (:phase state))
                 (nil? (process-identity (get-in state [:controller :pid]))))
          {:enabled? false}
          (if (< (clock) deadline) (do (Thread/sleep 25) (recur))
            (throw (ex-info "Recovery cancellation was not acknowledged; resources retained" {}))))))))

(defn java-command [mode manifest]
  [(str (io/file (System/getProperty "java.home") "bin" (if (companion/windows?) "java.exe" "java")))
   "-cp" (str/join java.io.File/pathSeparator
                    (map #(.getCanonicalPath (io/file %)) (str/split (System/getProperty "java.class.path")
                                                                  (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))
   "clojure.main" "-m" "uml-viewer.adapters.recovery" mode manifest])

(defn retire-completed!
  "A completed lifecycle (cancelled, failed or normal) is history: a fresh
  launch retires the journal and any stale cancellation. A live journal
  still refuses replacement."
  [identity]
  (when-let [existing (status identity)]
    (when-not (#{:cancelled :failed :normal} (:phase existing))
      (throw (ex-info "Recovery registration already exists" {})))
    (let [ref (reference identity)]
      (Files/deleteIfExists (.toPath (io/file (:state-file ref))))
      (Files/deleteIfExists (.toPath (io/file (:control-file ref)))))
    true))

(defn start! [identity]
  (let [ref (reference identity) manifest (:manifest ref)]
    (retire-completed! identity)
    (write-state! manifest identity)
    (let [builder (ProcessBuilder. ^java.util.List (java-command "controller" manifest))]
      (.directory builder (io/file (:cwd identity)))
      (.redirectErrorStream builder true)
      (.redirectOutput builder (io/file (:cwd identity) ".uml-viewer" "recovery.log"))
      (.redirectInput builder (ProcessBuilder$Redirect/from (io/file (if (companion/windows?) "NUL" "/dev/null"))))
      (.start builder))
    (let [deadline (+ (clock) 30000)]
      (loop []
        (if (status identity) (verify! identity)
          (if (< (clock) deadline) (do (Thread/sleep 25) (recur))
            (throw (ex-info "Detached recovery controller did not register; ownership retained" {}))))))))

(defn run-child! [identity]
  (let [argv (:command identity)]
    (when (and (companion/windows?) (not (str/ends-with? (str/lower-case (first argv)) ".exe")))
      (throw (ex-info "Recovery needs direct claude.exe or node.exe + official cli.js; wrappers cannot prove child identity" {})))
    (with-open [raf (RandomAccessFile. (str (:runner-state identity) ".lock") "rw")
                channel (.getChannel raf) lock (.tryLock channel)]
      (when-not lock (throw (ex-info "Runner generation already executing" {})))
      (let [builder (doto (ProcessBuilder. ^java.util.List argv) (.directory (io/file (:cwd identity))) (.inheritIO))
            _ (.remove (.environment builder) "NO_COLOR")
            child (.start builder)
            owner {:pid (.pid child) :start (str (.orElse (.startInstant (.info child)) nil))}]
        (write-state! (:runner-state identity) {:token (:runner-token identity) :phase :started :child owner})
        (let [exit (.waitFor child)]
          (write-state! (:runner-state identity) {:token (:runner-token identity) :phase :exited :child owner :exit exit :at (clock)})
          exit)))))

(defn -main [mode manifest]
  (let [identity (read-state manifest)]
    (case mode
      "runner" (System/exit (int (run-child! identity)))
      "controller"
      (do
        (with-open [raf (RandomAccessFile. (:lock-file (reference identity)) "rw")
                    channel (.getChannel raf) lock (.tryLock channel)]
          (when-not lock (throw (ex-info "Project recovery controller already running" {})))
          (register! identity (process-identity (.pid (ProcessHandle/current))))
          (loop []
            (let [state (try
                          (with-gate! identity
                            #(step! identity (clock) process-identity
                                    (requiring-resolve 'uml-viewer.adapters.herdr/relaunch!)))
                          (catch Exception e
                            (save! identity (stopped (status identity) (.getMessage e)))))]
              (when-not (#{:failed :normal :cancelled} (:phase state))
                (Thread/sleep 100) (recur)))))
        ;; A terminal phase must end this process. The runner's stream readers
        ;; are non-daemon agent threads, so simply returning would leave this
        ;; JVM alive for the pool's 60-second keep-alive: far past the ten
        ;; seconds cancel! grants, which would retain the companion resources
        ;; the contract requires to be destroyed only after the controller has
        ;; confirmed cancellation and exited.
        (System/exit 0)))))
