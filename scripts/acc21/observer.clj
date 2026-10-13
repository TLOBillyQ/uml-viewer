;; #21 native acceptance observer. Loaded with `-i` ahead of
;; `-m uml-viewer.main.uml-viewer` by the clojure.ps1 shim. Observation only:
;; wraps sketch/update-state to record the real GUI/mail/recovery state
;; timeline and to serve an on-demand command file (state snapshot, screen
;; capture, native AWT input events, native window close). It never replaces
;; start!, recovery, restart-state or view snapshots, and never writes to the
;; mailbox, session or ownership files.
(ns acc21.observer
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.application.events :as events]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.adapters.sketch :as sketch])
  (:import [java.awt Canvas Container Frame Rectangle Robot Toolkit]
           [java.awt.event InputEvent KeyEvent MouseEvent MouseWheelEvent WindowEvent]
           [javax.imageio ImageIO]))

(def root (or (System/getenv "UML_VIEWER_PROJECT_ROOT") (System/getProperty "user.dir")))
(def acc-dir (io/file root ".uml-viewer"))
(def timeline-file (io/file acc-dir "observer-timeline.edn"))
(def cmd-file (io/file acc-dir "observer-cmd.edn"))
(def !timeline (atom []))
(def !last-sig (atom nil))
(def !state (atom nil))

(def timeline-keys
  [:path :waiting :mtime :metrics-stamp :focus :proposal-id :proposal
   :open-layer :declutter :cam-x :cam-y :zoom :selected :detail-id
   :mail-seen :discussion-context :error :recovery-status :mail-status])

(defn- now [] (str (java.time.Instant/now)))

(defn- record! [state]
  (reset! !state state)
  (let [sig (select-keys state timeline-keys)]
    (when (not= sig @!last-sig)
      (reset! !last-sig sig)
      (swap! !timeline conj (assoc sig :t (now)))
      (spit timeline-file (pr-str @!timeline)))))

(defn- matching-frames [title]
  (filter #(and (= (str title) (.getTitle ^Frame %)) (.isShowing ^Frame %))
          (Frame/getFrames)))

(defn showing-frame [title]
  (first (matching-frames title)))

(defn viewer-frame []
  (showing-frame "UML viewer"))

(defn- find-canvas [^java.awt.Component c]
  (cond
    (instance? Canvas c) c
    (instance? Container c) (some find-canvas (.getComponents ^Container c))
    :else nil))

(defn event-target
  ([] (event-target "UML viewer"))
  ([title]
   (when-let [^Frame f (showing-frame title)]
     (or (find-canvas f) f))))

(defn- scene-summary [state]
  (let [scene (:scene state)]
    {:canvas (when-let [^java.awt.Component c (event-target)]
               {:w (.getWidth c) :h (.getHeight c)})
     :classes (mapv #(select-keys % [:id :kind :ns :file :lang :rect :crap :cc
                                    :coverage :killed :survived :uncovered :sites :ops])
                    (:classes scene))
     :packages (mapv #(select-keys % [:id :label :rect]) (:packages scene))
     :size (:size scene)
     :detail-window? (boolean (some #(and (.isShowing ^Frame %) (not= "UML viewer" (.getTitle ^Frame %)))
                                    (java.awt.Frame/getFrames)))
     :frames (mapv #(.getTitle ^Frame %) (java.awt.Frame/getFrames))}))

(defn- do-snapshot []
  {:state (select-keys @!state timeline-keys)
   :scene (scene-summary @!state)
   :detail (when-let [id (:detail-id @!state)]
             (detail/model (events/card-scene @!state) id))
   :t (now)})

(defn- do-boxes []
  {:classes (mapv #(select-keys % [:id :kind :package :ns :rect :drill? :label])
                  (:classes (:scene @!state)))
   :packages (mapv #(select-keys % [:id :kind :label :rect])
                   (:packages (:scene @!state)))
   :focus (:focus @!state)
   :open-layer (:open-layer @!state)
   :proposal-id (:proposal-id @!state)
   :t (now)})

(defn- do-probe
  "Read-only native identity of the sketch surfaces and their frames."
  []
  (let [ap (:applet @sketch/!bridge)]
    {:detail-id (:detail-id @!state)
     :applet (when ap
               (try
                 {:applet (str (class ap))
                  :surface (str (class (.getSurface ap)))
                  :native (str (class (.getNative (.getSurface ap))))}
                 (catch Throwable t {:error (.getMessage t)})))
     :frames (mapv (fn [^Frame f]
                     {:title (.getTitle f)
                      :showing (.isShowing f)
                      :canvas (some-> (find-canvas f) class str)})
                   (Frame/getFrames))
     :focus-owner (some-> (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager)
                         .getFocusOwner class str)
     :t (now)}))

(defn- capture-frame [title]
  (let [want (str title)
        frames (matching-frames want)]
    (when-not (= 1 (count frames))
      (throw (ex-info "Screenshot requires one showing frame with the requested title"
                      {:title want :matches (count frames)
                       :showing (mapv #(.getTitle ^Frame %) (Frame/getFrames))})))
    (first frames)))

(defn- do-screenshot [{:keys [path screen? title]}]
  (let [robot (Robot.)
        file (io/file path)]
    (.mkdirs (.getParentFile file))
    (if screen?
      (ImageIO/write (.createScreenCapture robot (Rectangle. (.getScreenSize (Toolkit/getDefaultToolkit))))
                     "png" file)
      (if-let [^Frame f (capture-frame (or title "UML viewer"))]
        (ImageIO/write (.createScreenCapture robot (.getBounds f)) "png" file)
        (throw (ex-info "UML viewer frame is not showing" {}))))
    {:screenshot (.getAbsolutePath file)}))

(defn- do-key [{:keys [key-code ctrl? window] :as cmd}]
  ;; Commands come from PowerShell, which writes snake_case keys verbatim.
  (let [key-code (or key-code (:key_code cmd))]
    (if-let [^Frame frame (showing-frame (or window "UML viewer"))]
      (let [code (int key-code)
            owner (.getFocusOwner (java.awt.KeyboardFocusManager/getCurrentKeyboardFocusManager))
            focused-window (if (instance? java.awt.Window owner)
                             owner
                             (when owner (javax.swing.SwingUtilities/getWindowAncestor owner)))
            robot (Robot.)]
        (when-not (= frame focused-window)
          (throw (ex-info "Requested viewer window does not own keyboard focus"
                          {:window (str window)})))
        ;; KeyEvent.dispatchEvent can be discarded by the focus manager.
        ;; Native keyboard input follows the same focused-window path as a user.
        (when ctrl? (.keyPress robot KeyEvent/VK_CONTROL))
        (try
          (.keyPress robot code)
          (.keyRelease robot code)
          (finally
            (when ctrl? (.keyRelease robot KeyEvent/VK_CONTROL))))
        {:key true :window (or window "UML viewer")})
      (throw (ex-info "No viewer event target" {:window (or window "UML viewer")})))))

(defn- do-click [{:keys [x y button count window]}]
  (if-let [^java.awt.Component t (event-target (or window "UML viewer"))]
    (let [btn (int (or button 1))
          n (int (or count 1))
          mask (case btn 1 InputEvent/BUTTON1_DOWN_MASK 2 InputEvent/BUTTON2_DOWN_MASK 3 InputEvent/BUTTON3_DOWN_MASK 0)
          now (System/currentTimeMillis)]
      (.dispatchEvent t (MouseEvent. t MouseEvent/MOUSE_PRESSED now mask (int x) (int y) n false btn))
      (.dispatchEvent t (MouseEvent. t MouseEvent/MOUSE_RELEASED (inc now) mask (int x) (int y) n false btn))
      (.dispatchEvent t (MouseEvent. t MouseEvent/MOUSE_CLICKED (+ 2 now) mask (int x) (int y) n false btn))
      {:click [x y btn n]})
    (throw (ex-info "No viewer event target" {:window (or window "UML viewer")}))))

(defn- do-wheel [{:keys [x y rotation shift?]}]
  (if-let [^java.awt.Component t (event-target)]
    (let [mods (if shift? InputEvent/SHIFT_DOWN_MASK 0)]
      (.dispatchEvent t (MouseWheelEvent. t MouseEvent/MOUSE_WHEEL (System/currentTimeMillis) mods
                                          (int (or x 400)) (int (or y 400)) 0 false
                                          MouseWheelEvent/WHEEL_UNIT_SCROLL 1 (int (or rotation 1))))
      {:wheel [x y rotation shift?]})
    (throw (ex-info "No viewer event target" {}))))

(defn- world-box [id]
  (let [want (keyword (str id))
        boxes (concat (:classes (:scene @!state)) (:packages (:scene @!state)))]
    (or (first (filter #(= want (:id %)) boxes))
        (throw (ex-info "No scene box with that id" {:id (str id)
                                                     :ids (mapv :id boxes)})))))

(defn- box-screen
  "Screen point at the fractional `fx`/`fy` position inside a world box."
  [box fx fy]
  (let [state @!state
        z (double (or (:zoom state) 1.0))
        r (:rect box)
        wx (+ (:x r) (* (double fx) (:w r)))
        wy (+ (:y r) (* (double fy) (:h r)))]
    [(int (Math/round (* (- wx (double (or (:cam-x state) 0))) z)))
     (int (Math/round (* (- wy (double (or (:cam-y state) 0))) z)))]))

(defn- do-click-id [{:keys [box fx fy count]}]
  (let [box (world-box box)
        [x y] (box-screen box (or fx 0.5) (or fy 0.5))]
    (merge (do-click {:x x :y y :count count})
           {:box (:id box) :screen [x y]})))

(defn- inspector-point [{:keys [target index]}]
  (let [^java.awt.Component c (event-target)
        w (.getWidth c)
        n (count (:proposals (:doc @!state)))]
    (let [r (case (keyword (str target))
              :proposal (layout/proposal-row-rect w (int (or index 0)))
              :real-diagram (layout/real-diagram-rect w)
              :new-proposal (layout/new-proposal-rect w n)
              :declutter (layout/declutter-rect w n)
              (throw (ex-info "Unknown inspector target" {:target (str target)})))]
      [(+ (:x r) 24) (+ (:y r) (quot (:h r) 2))])))

(defn- do-inspector [{:keys [target index]}]
  (let [[x y] (inspector-point {:target target :index index})]
    (merge (do-click {:x x :y y :count 1}) {:inspector (str target) :screen [x y]})))

(defn- do-zoom [{:keys [dir]}]
  (let [[code ch] (case (str dir)
                    "in" [61 \=]
                    "out" [45 \-]
                    "reset" [48 \0]
                    (throw (ex-info "Unknown zoom direction" {:dir (str dir)})))]
    (merge (do-key {:key-code code :key-char ch :ctrl? true})
           {:zoom (:zoom @!state)})))

(defn- do-close-gui []
  (if-let [^Frame f (viewer-frame)]
    (do (.dispatchEvent f (WindowEvent. f WindowEvent/WINDOW_CLOSING))
        {:close-gui true})
    (throw (ex-info "UML viewer frame is not showing" {}))))

(defn- do-front [{:keys [title]}]
  (if-let [^Frame f (capture-frame (or title "UML viewer"))]
    (do (.setExtendedState f Frame/NORMAL)
        (.toFront f)
        (.requestFocus f)
        (when-let [^java.awt.Component c (event-target (or title "UML viewer"))]
          (.requestFocus c)
          (.requestFocusInWindow c))
        {:front true})
    (throw (ex-info "UML viewer frame is not showing" {}))))

(defn- handle-cmd! []
  (when (.isFile cmd-file)
    (let [cmd (try (edn/read-string (slurp cmd-file)) (catch Throwable _ nil))]
      (when (and (map? cmd) (:id cmd) (:op cmd))
        (let [result (try {:ok true :result (case (keyword (:op cmd))
                                              :snapshot (do-snapshot)
                                              :boxes (do-boxes)
                                              :probe (do-probe)
                                              :screenshot (do-screenshot cmd)
                                              :key (do-key cmd)
                                              :click (do-click cmd)
                                              :click-id (do-click-id cmd)
                                              :click-inspector (do-inspector cmd)
                                              :zoom (do-zoom cmd)
                                              :wheel (do-wheel cmd)
                                              :close-gui (do-close-gui)
                                              :front (do-front cmd)
                                              {:error "unknown op"})}
                          (catch Throwable t {:ok false :error (.getMessage t)}))]
          (spit (io/file acc-dir (str "observer-result-" (:id cmd) ".edn"))
                (pr-str (assoc result :id (:id cmd) :t (now))))
          (.delete cmd-file))))))

(let [original sketch/update-state]
  (alter-var-root
   #'sketch/update-state
   (constantly
    (fn [state]
      (let [next-state (original state)]
        (try
          (record! next-state)
          (handle-cmd!)
          (catch Throwable t
            (binding [*out* *err*] (println "acc21 observer:" (.getMessage t)))))
        next-state)))))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. ^Runnable
                           (fn []
                             (try (spit timeline-file (pr-str @!timeline))
                                  (catch Throwable _)))))
(println "acc21 observer loaded for" root)
