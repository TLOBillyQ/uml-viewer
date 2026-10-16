(ns uml-viewer.adapters.core-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.companion :as companion]
            [uml-viewer.adapters.sketch :as sketch]))

(defn- without-processes [run-spec]
  (let [blocked (fn [& args]
                  (throw (AssertionError. (str "Unexpected startup process: " (pr-str args)))))]
    (with-redefs [companion/process! blocked sketch/run-command blocked]
      (run-spec))))

(describe "cli args"
  (around [run-spec] (without-processes run-spec))
  (it "defaults the path and does not restart"
    (should= {:path "examples/library.edn" :restart? false :help? false}
             (core/parse-args nil))
    (should= {:path "examples/library.edn" :restart? false :help? false}
             (core/parse-args [])))

  (it "takes a path and the --restart flag in either order"
    (should= {:path "doc.edn" :restart? false :help? false}
             (core/parse-args ["doc.edn"]))
    (should= {:path nil :restart? true :help? false}
             (core/parse-args ["--restart"]))
    (should= {:path "doc.edn" :restart? true :help? false}
             (core/parse-args ["--restart" "doc.edn"]))
    (should= {:path "doc.edn" :restart? true :help? false}
             (core/parse-args ["doc.edn" "--restart"])))

  (it "prints a description of the arguments on --help"
    (should= {:path "examples/library.edn" :restart? false :help? true}
             (core/parse-args ["--help"]))
    (should (:help? (core/parse-args ["-h" "doc.edn"])))
    (should (re-find #"edn-file" core/help-text))
    (should (re-find #"--restart" core/help-text))
    (with-redefs [sketch/start! (fn [& _] (throw (Exception. "should not start")))]
      (let [ret (atom nil)
            out (with-out-str (reset! ret (core/start! :unused "--help")))]
        (should= :help @ret)
        (should (re-find #"Usage: clj -M:run" out)))))

  (it "starts the sketch when not asking for help"
    (let [args (atom nil)]
      (with-redefs [uml-viewer.adapters.companion/select-backend! (fn [& _] :herdr)
                    sketch/start! (fn [& a] (reset! args a) :started)]
        (let [out (with-out-str (core/start! :src "doc.edn"))]
          (should= ["doc.edn" :src false sketch/startup-project-root :herdr] @args)
          (should (re-find #"Watching" out))
          (should (re-find #"real diagram above Proposals" out)))))))

(describe "backend selection before viewer creation"
  (around [run-spec] (without-processes run-spec))
  (it "preserves positional argv and diagnoses missing duplicate and unknown selection"
    (should= {:path "with spaces.edn" :restart? true :help? false :backend :herdr}
             (core/parse-args ["with spaces.edn" "--backend" "herdr" "--restart"]))
    (doseq [args [["--backend"] ["--backend" "other"] ["--backend" "tmux"] ["--backend" "psmux"]
                  ["--backend" "herdr" "--backend" "herdr"]]]
      (should-throw clojure.lang.ExceptionInfo (core/parse-args args))))
  (it "passes the selected Herdr backend into fresh startup"
    (let [started (atom nil)]
      (with-redefs [uml-viewer.adapters.companion/select-backend! (fn [& _] :herdr)
                    sketch/start! (fn [& args] (reset! started args))]
        (with-out-str (core/start! :source "--backend" "herdr" "diagram.edn"))
        (should= ["diagram.edn" :source false sketch/startup-project-root :herdr] @started))))
  (it "does not start the sketch when preflight fails"
    (with-redefs [uml-viewer.adapters.companion/select-backend! (fn [& _] (throw (ex-info "preflight" {})))
                  sketch/start! (fn [& _] (throw (AssertionError. "viewer created")))]
      (should-throw clojure.lang.ExceptionInfo "preflight" (core/start! :source "--backend" "herdr")))))
