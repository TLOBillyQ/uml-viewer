(ns uml-viewer.main.uml-viewer
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.domain.log :as log]
            [uml-viewer.lua-language.source-lua]
            [uml-viewer.python-language.source-python]
            [uml-viewer.rust-language.source-rust]
            [uml-viewer.typescript-language.source-typescript])
  (:gen-class))

(defn- record-jvm!
  "Persist the exact current JVM identity for the public wrapper restart."
  [root]
  (let [handle (java.lang.ProcessHandle/current)
        record (io/file root ".uml-viewer" "viewer-jvm.json")]
    (.mkdirs (.getParentFile record))
    (spit record (json/write-str
                   {:pid (.pid handle)
                    :started (str (.get (.startInstant (.info handle))))
                    :cwd (.getCanonicalPath (io/file root))}))))

(defn start-recorded!
  "Start the viewer, then record this JVM's identity. A refused start (for
  example a duplicate fresh launch) must not overwrite the live record."
  [root args]
  (apply core/start! clj-source/impl args)
  (record-jvm! root))

(defn -main [& args]
  (log/install-exception-log!)
  (try
    (start-recorded! (System/getenv "UML_VIEWER_PROJECT_ROOT") args)
    (catch Throwable t
      (log/log-exception! t "start!")
      (System/exit 1))))
