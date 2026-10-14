(ns uml-viewer.adapters.core
  (:require [uml-viewer.adapters.sketch :as sketch]
            [uml-viewer.adapters.companion :as companion]))

(def help-text
  (str "Usage: clj -M:run [options] [edn-file]\n"
       "\n"
       "  edn-file          Diagram to watch (default: examples/library.edn).\n"
       "                    A fresh start waits for the companion Claude to send\n"
       "                    :display unless the associated agent recycles\n"
       "                    the window with :uml-viewer-restart, or you press R.\n"
       "\n"
       "  --restart         Associated agent only (via :uml-viewer-restart).\n"
       "                    New JVM, keep the existing companion session.\n"
       "                    Reloads the last view (depth, pan, zoom, proposal).\n"
       "                    Do not use this if no companion is attached.\n"
       "\n"
       "  --backend herdr   Optional compatibility flag; Herdr is the only backend.\n"
       "                    Legacy ownership is refused; migrate with a fresh start.\n"
       "                    Requires >=0.9.3/protocol 22. Windows acceptance is partial;\n"
       "                    macOS native acceptance was cancelled. Linux/WSL unsupported.\n"
       "  -h, --help        Print this help and exit.\n"))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent."
  [args]
  (let [{:keys [backend args]} (companion/selection-args (keep identity args))
        help? (boolean (some #{"--help" "-h"} args))
        restart? (boolean (some #{"--restart"} args))
        path (->> args (remove #{"--help" "-h" "--restart"}) first)]
    (cond-> {:help? help?
             :restart? restart?
             :path (or path (when-not restart? "examples/library.edn"))}
      backend (assoc :backend backend))))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? help? backend]} (parse-args args)]
    (if help?
      (do (print help-text) :help)
      (do
        (let [selected (companion/select-backend! sketch/startup-project-root backend restart?
                                                   (System/getProperty "os.name") companion/process!)]
          (sketch/start! path source-impl restart? sketch/startup-project-root selected))
        (println "Watching" path)
        (println "Double-click a class for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 resets. R reloads. Click the real diagram above Proposals, or a proposal to show it.")))))
