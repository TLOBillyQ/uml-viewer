(ns uml-viewer.adapters.companion
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [uml-viewer.domain.mailbox :as mailbox])
  (:import [java.util.concurrent TimeUnit]))

(defn process!
  "External process boundary preserving status, output streams and timeout."
  ([argv] (process! argv 10000))
  ([argv timeout-ms] (process! argv timeout-ms nil))
  ([argv timeout-ms env-remove]
   (try
     (let [pb (ProcessBuilder. (into-array String argv))]
       (doseq [v env-remove] (.remove (.environment pb) ^String v))
       (let [p (.start pb)
             out (future (slurp (.getInputStream p)))
             err (future (slurp (.getErrorStream p)))
             done? (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)]
         (when-not done? (.destroy p))
         {:exit (when done? (.exitValue p))
          :out (if done? (str/trim @out) "")
          :err (if done? (str/trim @err) "process timed out")
          :timeout? (not done?)}))
     (catch Exception e {:exit nil :out "" :err (.getMessage e) :timeout? false}))))

(defn checked! [run args]
  (let [result (run args)
        result (if (number? result) {:exit result :out "" :err ""} result)]
    (when-not (= 0 (:exit result))
      (throw (ex-info (str "Companion command failed: " (first args) ": " (:err result))
                      (assoc result :args args :status (if (:timeout? result) :timeout :failure)))))
    result))

(defn shell-word [s]
  (str "'" (str/replace (str s) "'" "'\"'\"'") "'"))

(defprotocol SessionBackend
  (create! [backend session cwd command])
  (probe! [backend session])
  (bind! [backend session pane])
  (wake! [backend pane keys] [backend pane keys opts])
  (recovery! [backend session pane cwd command enabled?])
  (cleanup! [backend session])
  (identity! [backend session pane]))

(defn encoded [text]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str text) "UTF-16LE")))

(def legacy-guidance
  "tmux/psmux companions are retired. Ownership and resources were retained; no takeover or cleanup was attempted. Finish the old viewer/session using the old release, verify that this project's legacy resources have exited, then archive .uml-viewer/companion.edn and start ./uml fresh with Herdr. Do not use --restart to migrate; retain session.edn and mailbox queues.")

(defn recorded-backend
  "Require explicit Herdr provenance; unmarked ownership cannot be adopted."
  [record]
  (when (seq record)
    (when (or (nil? (:backend record)) (#{:tmux :psmux} (:backend record)))
      (throw (ex-info legacy-guidance {:status :failure :reason :legacy-ownership})))
    (:backend record)))

(defn selection-args
  "Remove backend options without reinterpreting the remaining argv."
  [args]
  (loop [remaining (seq args) forwarded [] selected nil]
    (if-let [arg (first remaining)]
      (if (= "--backend" arg)
        (let [value (second remaining)]
          (when (#{"tmux" "psmux"} value)
            (throw (ex-info legacy-guidance {:status :failure :reason :retired-backend})))
          (when (or selected (not= "herdr" value))
            (throw (ex-info "Unknown or duplicate companion backend; use --backend herdr" {:status :failure})))
          (recur (nnext remaining) forwarded :herdr))
        (recur (next remaining) (conj forwarded arg) selected))
      {:args forwarded :backend selected})))

(def herdr-guidance
  "Herdr 0.9.3 (protocol 22) is required. Install the pinned release from https://github.com/herdrdev/herdr/releases/tag/v0.9.3 (https://herdr.dev). No automatic download, upgrade or fallback. Windows native acceptance is partial; macOS has not passed native acceptance.")

(defn select-backend!
  "Read-only preflight before any viewer, terminal or companion resources."
  [root requested restart? os run]
  (let [file (mailbox/companion-file root)
        record (when (.exists file) (edn/read-string (slurp file)))
        _ (when (and (.exists file) (not (map? record)))
            (throw (ex-info "Present companion ownership is corrupt or unreadable; file retained" {:status :failure})))
        recorded (recorded-backend record)]
    (when (and recorded (not= :herdr recorded))
      (throw (ex-info "Unknown recorded companion backend; ownership retained" {:status :failure})))
    (when (and (seq record)
               (or (:provisional record)
                   (not (every? #(get record %) [:socket :workspace :terminal-id :shell-pid :owner-start :server-pid :server-start]))
                   (not (and (seq (:session record)) (seq (:pane record)) (:cwd record)))
                   (not= (.getCanonicalPath (io/file root))
                         (.getCanonicalPath (io/file (or (:cwd record) root))))
                   (not= :companion (:owner-role record))))
      (throw (ex-info "Companion ownership is incomplete, provisional or belongs to another project; file retained" {:status :failure})))
    (when (and requested (not= :herdr requested))
      (throw (ex-info legacy-guidance {:status :failure :requested requested})))
    (when (and restart? (not recorded))
      (throw (ex-info "Companion ownership is missing; cannot restart" {:status :failure})))
    (when-not (or (str/starts-with? os "Windows") (str/starts-with? os "Mac"))
      (throw (ex-info "Linux/WSL companion launch is unsupported after tmux retirement. Run the viewer and Herdr from native Windows or macOS; WSL no longer opens a tmux pane. Ownership and queues are retained." {:status :failure})))
    (let [version (run ["herdr" "--version"])
          schema (when (and (= 0 (:exit version)) (= "herdr 0.9.3" (:out version)))
                   (run ["herdr" "api" "schema" "--json"]))]
      (when-not (and schema (= 0 (:exit schema))
                     (re-find #"(?s)^\s*\{\s*\"\$schema\"\s*:\s*\"[^\"]*\"\s*,\s*\"protocol\"\s*:\s*22\s*," (:out schema)))
        (throw (ex-info herdr-guidance {:status :failure :version version :schema schema}))))
    :herdr))

(defn -main
  "Lightweight foreground wrapper preflight: no Quil or GUI namespaces."
  [root os & args]
  (try
    (let [{:keys [backend args]} (selection-args args)]
      (println (name (select-backend! root backend (boolean (some #{"--restart"} args)) os process!)))
      ;; process!'s non-daemon stream readers must not delay this foreground JVM.
      (System/exit 0))
    (catch Throwable t
      (binding [*out* *err*] (println "uml:" (.getMessage t)))
      (System/exit 1))))
