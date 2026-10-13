(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[uml-viewer.adapters.companion :as companion]
         '[uml-viewer.adapters.herdr :as herdr]
         '[uml-viewer.domain.mailbox :as mailbox])

(def run-id (str (java.util.UUID/randomUUID)))
(def base (io/file "target" (str "herdr-native-" run-id)))
(def records (atom []))
(defn check! [label value]
  (println (if value "PASS" "FAIL") label)
  (when-not value (throw (ex-info label {}))))
(defn await! [f]
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (or (f) (when (< (System/currentTimeMillis) deadline)
                (Thread/sleep 100) (recur))))))
(defn create-probe! [label args]
  (let [root (.getCanonicalPath (io/file base (str label " space & 中文")))
        session (str "uml-viewer-native-" label "-" run-id)
        script (io/file root "probe.ps1")
        output (io/file root "argv.json")
        release (io/file root "release")]
    (.mkdirs (io/file root))
    (spit script (str "$ErrorActionPreference='Stop'\n"
                      "$probeArgs=@($args)\n"
                      "@{cwd=(Get-Location).Path;argv=$probeArgs}|ConvertTo-Json -Depth 5 -Compress|Set-Content -LiteralPath (Join-Path $PSScriptRoot 'argv.json') -Encoding utf8\n"
                      "while(-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'release'))){Start-Sleep -Milliseconds 100}\n"
                      "exit 37\n"))
    (let [command (into ["pwsh.exe" "-NoProfile" "-File" (.getPath script)] args)
          provisional {:backend :herdr :owner-role :companion :cwd root :session session :command command :provisional true}]
      (swap! records conj provisional)
      (mailbox/write-companion! root provisional)
      (let [record (companion/create! (herdr/herdr provisional) session root command)]
        (swap! records #(mapv (fn [r] (if (= session (:session r)) record r)) %))
        (mailbox/write-companion! root record)
        (check! (str label " output produced") (await! #(.exists output)))
        (let [observed (json/read-str (slurp output :encoding "UTF-8") :key-fn keyword)]
          (check! (str label " cwd exact") (= root (:cwd observed)))
          (check! (str label " argv exact") (= args (:argv observed))))
        {:record record :release release}))))

(try
  (let [args ["with spaces" "quote\"value" "single'quote" "&" ";" "$literal" "中文☃" "" "trailing\\"]
        a (create-probe! "A" args)
        b (create-probe! "B" args)
        a-info (:record a)
        b-info (:record b)]
    (check! "sessions distinct" (not= (:session a-info) (:session b-info)))
    (check! "server identities distinct" (not= (:server-pid a-info) (:server-pid b-info)))
    (spit (:release a) "exit")
    (let [state (await! #(let [file (io/file (:runner-state a-info))]
                          (when (.exists file)
                            (let [state (json/read-str (slurp file) :key-fn keyword)]
                              (when (= "exited" (:phase state)) state)))))]
      (check! "runner exit=37 recorded" (= 37 (:exit state))))
    (check! "A shell survived child exit" (companion/probe! (herdr/herdr a-info) (:session a-info)))
    (companion/cleanup! (herdr/herdr a-info) (:session a-info))
    (swap! records #(vec (remove (fn [r] (= (:session a-info) (:session r))) %)))
    (check! "B survived A cleanup" (= (:shell-pid b-info)
                                       (:shell-pid (companion/identity! (herdr/herdr b-info) (:session b-info) (:pane b-info)))))
    (companion/cleanup! (herdr/herdr b-info) (:session b-info))
    (swap! records #(vec (remove (fn [r] (= (:session b-info) (:session r))) %)))
    (println "NATIVE CHECK PASSED" (.getCanonicalPath base)))
  (finally
    (doseq [record @records]
      (let [saved (mailbox/read-companion (:cwd record))]
        (try
          (companion/cleanup! (herdr/herdr saved) (:session saved))
          (catch Exception e (println "CLEANUP REQUIRED" (:session record) (.getMessage e))))))))
(shutdown-agents)
