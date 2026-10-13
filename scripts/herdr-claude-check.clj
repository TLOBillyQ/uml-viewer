(require '[clojure.java.io :as io]
         '[clojure.data.json :as json]
         '[quil.core :as q]
         '[uml-viewer.adapters.sketch :as sketch]
         '[uml-viewer.adapters.companion :as companion]
         '[uml-viewer.adapters.herdr :as herdr]
         '[uml-viewer.application.document :as document]
         '[uml-viewer.domain.mailbox :as mailbox])
(def root (.getCanonicalPath (io/file "target" (str "herdr-claude-" (java.util.UUID/randomUUID)))))
(def diagram (io/file root "diagram.edn"))
(io/make-parents diagram)
(spit diagram "{:layers [{:id :probe :name \"Windows Herdr\" :classes [{:id :hello :name \"Native companion\" :ns \"probe.hello\" :fields [] :methods []}]}]}")
(def prompt "This is an authorized isolated Windows Herdr acceptance fixture. Do not scan or change source, policy, or metrics. Use your Write tool to create .uml-viewer/to-viewer.edn with exactly {:next-id 2 :queue [{:id 1 :op :display :path \"diagram.edn\"}]}. Then reply DISPLAY-SENT and stay ready for mail. For later mail, read .uml-viewer/to-agent.edn, process oldest first, remove processed entries retaining :next-id, and reply MAIL-CONSUMED. Do not launch another viewer or modify anything outside this fixture.")
(println "FIXTURE" root)
(flush)
(def observed-state (atom nil))
(def original-update sketch/update-state)
(alter-var-root #'sketch/update-state
                (constantly (fn [state] (let [next-state (original-update state)]
                                          (reset! observed-state next-state) next-state))))
(try
  (with-redefs [sketch/standing-rules "You are the test companion for this isolated fixture. Follow the launch prompt."
                sketch/launch-prompt prompt]
    (sketch/start! (.getPath diagram) :source false root :herdr))
  (let [deadline (+ (System/currentTimeMillis) 120000)]
    (loop []
      (let [state @observed-state]
        (cond
          (and state (not (:waiting state)) (= (.getPath diagram) (:path state)))
          (do
            (println "PASS real Claude display consumed by viewer")
            (let [frame (first (filter #(and (= "UML viewer" (.getTitle %)) (.isShowing %)) (java.awt.Frame/getFrames)))]
              (when-not frame (throw (ex-info "Viewer window is not visible" {:observed (map #(.getTitle %) (java.awt.Frame/getFrames))})))
              (javax.imageio.ImageIO/write (.createScreenCapture (java.awt.Robot.) (.getBounds frame))
                                          "png" (io/file root "viewer.png"))
              (println "SCREENSHOT" (str (io/file root "viewer.png"))))
            (let [result (sketch/request-agent! root :context {:context :real})]
              (when-not (:woke? result) (throw (ex-info "Real Claude mail wake failed" result)))
              (println "PASS real Claude consumed context mail")))
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 500) (recur))
          :else (do
                  (let [record (mailbox/read-companion root)]
                    (println (:out (companion/process! ["herdr" "--session" (:session record) "pane" "read" (:pane record) "--source" "recent-unwrapped" "--lines" "30"]))))
                  (throw (ex-info "Timed out waiting for real Claude display" {})))))))
  (finally
    (sketch/shutdown-children! root)
    (doseq [frame (java.awt.Frame/getFrames)]
      (when (= "UML viewer" (.getTitle frame)) (.dispose frame)))
    (shutdown-agents)))
(System/exit 0)
