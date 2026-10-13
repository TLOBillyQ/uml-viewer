(ns uml-viewer.main.uml-viewer-spec
  (:require [speclj.core :refer :all]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.main.uml-viewer :as main]))

(defn temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory "uml-main" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn remove-root [root] (doseq [f (reverse (file-seq root))] (io/delete-file f true)))

(describe "viewer JVM identity recording"
  (it "records the live JVM only after startup succeeds"
    (let [root (temp-root)]
      (try
        (with-redefs [core/start! (fn [& _] :started)]
          (main/start-recorded! (.getCanonicalPath root) []))
        (let [record (json/read-str (slurp (io/file root ".uml-viewer" "viewer-jvm.json")) :key-fn keyword)]
          (should= (.pid (java.lang.ProcessHandle/current)) (:pid record))
          (should= (.getCanonicalPath root) (:cwd record)))
        (finally (remove-root root)))))
  (it "leaves an existing record untouched when startup is refused"
    (let [root (temp-root)]
      (try
        (let [record (io/file root ".uml-viewer" "viewer-jvm.json")]
          (.mkdirs (.getParentFile record))
          (spit record (json/write-str {:pid 4242 :started "keep" :cwd "original"}))
          (with-redefs [core/start! (fn [& _] (throw (ex-info "Companion ownership already recorded; use --restart or resolve it before fresh start" {})))]
            (should-throw clojure.lang.ExceptionInfo (main/start-recorded! (.getCanonicalPath root) [])))
          (should= {:pid 4242 :started "keep" :cwd "original"}
                   (json/read-str (slurp record) :key-fn keyword)))
        (finally (remove-root root))))))
