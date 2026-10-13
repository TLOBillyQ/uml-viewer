(ns uml-viewer.application.document-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.application.document :as document]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.domain.ir :as ir]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.domain.log :as log]
            [uml-viewer.domain.mailbox :as mailbox]))

(defn state []
  {:scene document/empty-scene
   :selected nil
   :hover nil
   :cam-x 0
   :cam-y 0
   :path "examples/library.edn"
   :mtime 0})

(def display-doc
  {:hierarchical true :title "Display"
   :proposals [{:id :saved :name "Saved"
                :layers [{:id :core :label "Core" :nses [:core]}]}]
   :classes [{:id :core.alpha :name "Alpha" :ns "demo.core.alpha"}
             {:id :core.beta :name "Beta" :ns "demo.core.beta"}]
   :edges [] :order [:core.alpha :core.beta]})

(defn with-display-file [f]
  (let [root (io/file "target" (str "display-" (System/nanoTime)))
        path (io/file root "diagram.edn")]
    (.mkdirs (io/file root ".metrics"))
    (spit path (pr-str display-doc))
    (try (f root path)
         (finally
           (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))

(describe "document"
  (it "display reloads IR and metrics while retaining the same diagram view across path spellings"
    (with-display-file
      (fn [root path]
        (doseq [view [{:focus [:core]}
                      {:focus [] :proposal-id :saved :proposal true
                       :open-layer :proposal.core}]]
          (let [before (merge (document/load-path (.getPath path))
                              {:proposal-id nil :proposal false :open-layer nil} view
                              {:project-root (.getPath root) :cam-x 47 :cam-y -23
                               :zoom 1.35 :declutter :classes
                               :selected {:kind :class :id :core.alpha}
                               :detail-id :core.alpha})]
            (spit path (pr-str (assoc display-doc :title "Fresh")))
            (spit (io/file root ".metrics" "crap.edn")
                  (pr-str {:entries [{:name "go" :namespace "demo.core.alpha"
                                      :complexity 4 :coverage 50.0 :crap 12.5}]}))
            (doseq [spelling ["./diagram.edn" (.getCanonicalPath path)]]
              (let [after (document/apply-mail before {:op :display :id 9 :path spelling})]
                (should= (select-keys before [:focus :proposal-id :proposal :open-layer
                                             :cam-x :cam-y :zoom :declutter :selected :detail-id])
                         (select-keys after [:focus :proposal-id :proposal :open-layer
                                            :cam-x :cam-y :zoom :declutter :selected :detail-id]))
                (should= "Fresh" (get-in after [:doc :title]))
                (should= 12.5 (get-in after [:doc :classes 0 :crap :mu]))
                (should= 9 (:mail-seen after)))))))))
  (it "display restores the closest remaining namespace ancestor and clears vanished card and selection targets"
    (with-display-file
      (fn [root path]
        (spit path (pr-str (assoc display-doc :classes
                                 [{:id :core.deep.alpha :name "Alpha"}
                                  {:id :core.beta :name "Beta"} ])))
        (let [before (assoc (document/load-path (.getPath path))
                            :project-root (.getPath root) :focus [:core :deep]
                            :cam-x 47 :cam-y -23 :zoom 1.35
                            :selected {:kind :class :id :core.deep.alpha}
                            :detail-id :core.deep.alpha)]
          (spit path (pr-str (assoc display-doc :classes [{:id :core.beta :name "Beta"}])))
          (let [after (document/apply-mail before {:op :display :id 1 :path "diagram.edn"})]
            (should= [:core] (:focus after))
            (should= #{:core.beta} (set (map :id (get-in after [:scene :classes]))))
            (should-be-nil (:selected after))
            (should-be-nil (:detail-id after))
            (should= [47 -23 1.35] (mapv after [:cam-x :cam-y :zoom])))
          (spit path (pr-str (assoc display-doc :classes [{:id :other :name "Other"}])))
          (should= [] (:focus (document/apply-mail before {:op :display :id 2 :path "diagram.edn"})))))))

  (it "display returns vanished proposal layers to proposal root and vanished proposals to the namespace tree"
    (with-display-file
      (fn [root path]
        (let [before (assoc (document/load-path (.getPath path))
                            :project-root (.getPath root) :focus []
                            :proposal-id :saved :proposal true :open-layer :proposal.core
                            :selected {:kind :package :id :proposal.core})]
          (spit path (pr-str (assoc display-doc :proposals
                                   [{:id :saved :name "Saved" :layers []}])))
          (let [after (document/apply-mail before {:op :display :id 1 :path "diagram.edn"})]
            (should= :saved (:proposal-id after))
            (should (:proposal after))
            (should-be-nil (:open-layer after))
            (should-be-nil (:selected after))
            (should (seq (get-in after [:scene :classes]))))
          (spit path (pr-str (dissoc display-doc :proposals)))
          (let [after (document/apply-mail before {:op :display :id 2 :path "diagram.edn"})]
            (should-be-nil (:proposal-id after))
            (should-not (:proposal after))
            (should-be-nil (:open-layer after))
            (should= [] (:focus after))
            (should-not (get-in after [:scene :diagram :proposal])))))))

  (it "display starts a different diagram at its own root while retaining discussion context"
    (with-display-file
      (fn [root path]
        (let [other (io/file root "other.edn")
              context {:context :proposal :proposal-id :discussion :name "Discussed"}
              before (assoc (document/load-path (.getPath path))
                            :project-root (.getPath root) :focus [:core]
                            :proposal-id :saved :proposal true :open-layer :proposal.core
                            :cam-x 47 :cam-y -23 :zoom 1.35 :declutter :classes
                            :selected {:kind :class :id :core.alpha} :detail-id :core.alpha
                            :discussion-context context)]
          (spit other (pr-str {:hierarchical true :title "Other"
                              :classes [{:id :other :name "Other"}] :edges []}))
          (doseq [discussion [context {:context :real}]]
            (let [after (document/apply-mail (assoc before :discussion-context discussion)
                                            {:op :display :id 7 :path "other.edn"})]
              (should= "Other" (get-in after [:doc :title]))
              (should= [] (:focus after))
              (should= [0 0] (mapv after [:cam-x :cam-y]))
              (doseq [key [:proposal-id :proposal :open-layer :declutter :zoom :selected :detail-id]]
                (should-be-nil (key after)))
              (should= discussion (:discussion-context after))
              (should= 7 (:mail-seen after))))))))

  (it "display retains a card for an existing leaf outside the visible namespace boxes"
    (with-display-file
      (fn [root path]
        (let [before (assoc (document/load-path (.getPath path))
                            :project-root (.getPath root) :focus []
                            :selected {:kind :class :id :core.alpha} :detail-id :core.alpha)
              after (document/apply-mail before {:op :display :id 1 :path "diagram.edn"})]
          (should= #{:core} (set (map :id (get-in after [:scene :classes]))))
          (should= :core.alpha (:detail-id after))
          (should= (:selected before) (:selected after))))))

  (around [it]
    (let [f (str (System/getProperty "java.io.tmpdir")
                 "/uv-doc-log-" (System/nanoTime) ".txt")]
      (try
        (binding [log/*log-file* f]
          (it))
        (finally
          (io/delete-file f true)))))

  (it "bakes edge strokes so the draw loop does not recompute splines"
    (let [scene (compose/compile-diagram
                  (ir/normalize
                    {:packages [{:id :p :label "P"
                                 :classes [{:id :a :name "A"}
                                           {:id :b :name "B"}]}]
                     :edges [{:from :a :to :b :kind :dependency}]}))
          e (first (:edges scene))]
      (should (seq (:strokes e)))
      (should (:tip e))
      (should (:draw-bounds e))))

  (it "loads a document from disk"
    (let [s (document/load-path "examples/library.edn")]
      (should (seq (:classes (:scene s))))
      (should (pos? (:mtime s)))))

  (it "restores pan, zoom, and declutter after restart"
    (let [root (doto (io/file "target" (str "restart-" (System/nanoTime)))
                 (.mkdirs))
          metrics (io/file root ".metrics")
          edn (io/file root "d.edn")]
      (.mkdirs metrics)
      (spit edn (slurp "examples/library.edn"))
      (mailbox/write-session! root {:cam-x 40 :cam-y 20 :zoom 1.1
                                    :declutter :arrows :focus []})
      (let [s (document/restart-state (.getPath edn))]
        (should= 40 (:cam-x s))
        (should= 20 (:cam-y s))
        (should= 1.1 (:zoom s))
        (should= :arrows (:declutter s))
        (should-not (:waiting s))
        (should (seq (:classes (:scene s)))))))

  (it "roundtrips nonempty namespace focus and an expanded named proposal through disk on restart"
    (let [root (io/file "target" (str "view-roundtrip-" (System/nanoTime)))
          path (io/file root "diagram.edn")
          doc {:hierarchical true :title "Demo"
               :proposals [{:id :saved :name "Saved"
                            :layers [{:id :core :label "Core" :nses [:core]}]}]
               :classes [{:id :core.alpha :name "Alpha" :ns "demo.core.alpha"}
                         {:id :core.beta :name "Beta" :ns "demo.core.beta"}]
               :edges [] :order [:core.alpha :core.beta]}]
      (.mkdirs (io/file root ".metrics"))
      (spit path (pr-str doc))
      (try
        (doseq [[view expected-title expected-ids]
                [[{:focus [:core] :proposal-id nil :proposal false :open-layer nil}
                  nil #{:core.alpha :core.beta}]
                 [{:focus [] :proposal-id :saved :proposal true :open-layer :proposal.core}
                  "Core" #{:core.alpha :core.beta}]]]
          (let [saved (merge (document/load-path (.getPath path)) view
                             {:cam-x 47 :cam-y -23 :zoom 1.35 :declutter :classes
                              :selected :core.alpha :detail-id :core.alpha})]
            (document/save-session! saved)
            (let [restored (document/restart-state (.getPath path))]
              (should= (select-keys saved document/session-keys)
                       (select-keys restored document/session-keys))
              (should= expected-ids (set (map :id (get-in restored [:scene :classes]))))
              (when expected-title (should= expected-title (get-in restored [:scene :diagram :title])))
              (should-not (:waiting restored)))))
        (finally
          (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "restarts the saved diagram and discussion independently of selection, with an explicit path override"
    (let [root (io/file "target" (str "restart-path-" (System/nanoTime)))
          original (io/file root "chosen.edn")
          override (io/file root "other.edn")
          context {:context :proposal :proposal-id :discussion :name "Discussed"}]
      (.mkdirs (io/file root ".metrics"))
      (spit original (pr-str {:title "Original" :packages [] :edges []}))
      (spit override (pr-str {:title "Override" :packages [] :edges []}))
      (try
        (document/save-session! (assoc (document/load-path (.getPath original))
                                      :project-root (.getPath root)
                                      :discussion-context context :proposal-id nil :cam-x 31))
        (let [restored (document/restart-state nil (.getPath root))
              changed (document/restart-state (.getPath override) (.getPath root))]
          (should= (.getPath original) (:path restored))
          (should= "Original" (get-in restored [:doc :title]))
          (should= context (:discussion-context restored))
          (should-be-nil (:proposal-id restored))
          (should= 31 (:cam-x restored))
          (should= (.getPath override) (:path changed))
          (should= "Override" (get-in changed [:doc :title]))
          (should= context (:discussion-context changed)))
        (finally
          (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "restores a legacy session from the public IR policy output without touching companion ownership"
    (let [root (io/file "target" (str "legacy-" (System/nanoTime)))
          output (io/file root "generated" "diagram.edn")
          context {:context :proposal :proposal-id :saved :name "Saved"}
          ownership "{:backend :legacy :session \"untouched\"}"]
      (.mkdirs (.getParentFile output))
      (.mkdirs (io/file root "examples"))
      (spit output (slurp "examples/library.edn"))
      (spit (io/file root "policy.edn") "{:out \"wrong.edn\"}")
      (spit (io/file root "examples" "a.policy.edn") "{:out \"generated/diagram.edn\"}")
      (mailbox/write-session! root {:discussion-context context :mail-seen 17
                                   :cam-x 41 :cam-y -12 :zoom 1.4 :declutter :classes})
      (spit (io/file root ".uml-viewer" "companion.edn") ownership)
      (try
        (let [restored (document/restart-state nil (.getPath root))]
          (should= (.getPath output) (:path restored))
          (should= context (:discussion-context restored))
          (should= 17 (:mail-seen restored))
          (should= [41 -12 1.4 :classes]
                   (mapv restored [:cam-x :cam-y :zoom :declutter]))
          (should= ownership (slurp (io/file root ".uml-viewer" "companion.edn"))))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "fails restart explicitly when the selected legacy output cannot be loaded"
    (let [root (io/file "target" (str "legacy-invalid-" (System/nanoTime)))]
      (.mkdirs root)
      (spit (io/file root "policy.edn") "{:out \"absent.edn\"}")
      (try
        (should-throw clojure.lang.ExceptionInfo "Invalid restart diagram"
                      (document/restart-state nil (.getPath root)))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "diagnoses missing or malformed legacy policies and never replaces an invalid saved path"
    (let [root (io/file "target" (str "legacy-policy-" (System/nanoTime)))
          policy (io/file root "policy.edn")]
      (.mkdirs root)
      (try
        (should-throw clojure.lang.ExceptionInfo "No legacy restart policy; supply an explicit diagram path"
                      (document/restart-state nil (.getPath root)))
        (doseq [content ["[" "[]" "{:out 42}" "{:out \"\"}"]]
          (spit policy content)
          (should-throw clojure.lang.ExceptionInfo (document/restart-state nil (.getPath root))))
        (spit policy "[]")
        (should-throw clojure.lang.ExceptionInfo "Invalid legacy restart policy"
                      (document/restart-state nil (.getPath root)))
        (spit policy "{:out \"examples/library.edn\"}")
        (mailbox/write-session! root {:path "missing-saved.edn"})
        (should-throw clojure.lang.ExceptionInfo "Invalid restart diagram"
                      (document/restart-state nil (.getPath root)))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "uses project-named policy before sorted examples, then root policy and the IR default"
    (let [root (io/file "target" (str "legacy-selection-" (System/nanoTime)))
          preferred (io/file root "examples" (str (.getName root) ".policy.edn"))
          first-policy (io/file root "examples" "a.policy.edn")
          last-policy (io/file root "examples" "z.policy.edn")
          root-policy (io/file root "policy.edn")
          output (io/file root "examples" "uml-viewer.edn")]
      (.mkdirs (.getParentFile output))
      (spit output (slurp "examples/library.edn"))
      (spit preferred (pr-str {:out (.getAbsolutePath output)}))
      (spit first-policy "{:out \"missing.edn\"}")
      (spit last-policy "{:out \"missing.edn\"}")
      (spit root-policy "{:out \"missing.edn\"}")
      (try
        (should= (.getAbsolutePath output) (:path (document/restart-state nil (.getPath root))))
        (io/delete-file preferred)
        (spit first-policy "{}")
        (should= (.getPath output) (:path (document/restart-state nil (.getPath root))))
        (io/delete-file first-policy)
        (io/delete-file last-policy)
        (spit root-policy "{}")
        (should= (.getPath output) (:path (document/restart-state nil (.getPath root))))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "keeps discussion context across display and consumes pending restart mail oldest first"
    (let [root (io/file "target" (str "restart-mail-" (System/nanoTime)))
          path (io/file root "diagram.edn")
          context {:context :proposal :proposal-id :saved :name "Saved"}]
      (.mkdirs (io/file root ".metrics"))
      (spit path (pr-str {:title "Demo" :packages [] :edges []}))
      (try
        (let [state (document/apply-mail (assoc (document/load-path (.getPath path))
                                              :project-root (.getPath root))
                                       (assoc context :op :context :id 1))
              displayed (document/apply-mail state {:op :display :path "diagram.edn" :id 2})]
          (should= context (:discussion-context displayed))
          (document/save-session! displayed)
          (mailbox/write-command! (mailbox/to-viewer root) :context {:context :real})
          (mailbox/write-command! (mailbox/to-viewer root) :context context)
          (mailbox/write-command! (mailbox/to-viewer root) :display {:path "diagram.edn"})
          ;; Queue ids above the saved cursor simulate mail arriving while the JVM exits.
          (let [f (mailbox/to-viewer root)
                queue (edn/read-string (slurp f))]
            (spit f (pr-str (assoc queue :next-id 6 :queue
                                  (mapv #(update % :id + 2) (:queue queue))))))
          (let [resumed (document/poll-mail (document/restart-state nil (.getPath root)))]
            (should= context (:discussion-context resumed))
            (should= 5 (:mail-seen resumed))
            (should= [] (:queue (edn/read-string (slurp (mailbox/to-viewer root)))))))
        (finally (doseq [f (reverse (file-seq root))] (io/delete-file f true))))))

  (it "compiles named proposal packages at the root and the ns tree otherwise"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposal {:notice "PROPOSAL — not instantiated in code"
                          :layers [{:id :kernel :label "Kernel" :nses [:domain]}]}
               :classes [{:id :domain :name "Domain" :ns "demo.domain"}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges []
               :order [:domain :engine]}
          proposed (document/compile-view doc "target" [] true)
          tree (document/compile-view doc "target" [] false)
          drilled (document/compile-view doc "target" [:domain] true)]
      (should (get-in proposed [:diagram :proposal]))
      (should= "PROPOSAL — not instantiated in code"
               (get-in proposed [:diagram :title]))
      (should (some #(= "Kernel" (:label %)) (:packages proposed)))
      (should-not (get-in tree [:diagram :proposal]))
      (should-not (get-in drilled [:diagram :proposal]))))

  (it "routes every declutter mode on the ns tree and a proposal without throwing"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposal {:layers [{:id :kernel :label "Kernel" :nses [:domain]}
                                   {:id :shell :label "Shell" :nses [:engine]}]}
               :classes [{:id :domain :name "Domain" :ns "demo.domain"
                          :crap {:mu 1 :max 1 :sigma 0} :killed 2 :survived 0}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges [{:from :engine :to :domain :kind :dependency}
                       {:from :domain :to :missing :kind :dependency}]
               :order [:domain :engine]
               :levels [[:domain] [:engine]]}]
      (doseq [mode [:full :arrows :triangles :elements :classes]]
        (let [tree (document/compile-view doc "target" [] {:declutter mode})
              prop (document/compile-view doc "target" [] {:declutter mode
                                                          :proposal true})]
          (should (map? tree))
          (should (map? prop))
          (should (every? #(number? (get-in % [:rect :x])) (:classes tree)))
          (should (every? #(number? (get-in % [:rect :x])) (:classes prop)))))))

  (it "hides nested names, members, and ports when declutter is :elements"
    (let [doc {:hierarchical true
               :title "Demo"
               :classes [{:id :domain :name "Domain" :ns "demo.domain"
                          :ops [{:name "go" :text "go()"}]}]
               :edges []
               :order [:domain]}
          scene (document/compile-view doc "target" [] {:declutter :elements})
          c (first (filter #(= :domain (:id %)) (:classes scene)))]
      (should-not (some #(= :op (:kind %)) (:lines c)))
      (should-not (some #(= :child (:kind %)) (:lines c)))
      (should-not (seq (:in-ports c)))
      (should-not (seq (:out-ports c)))))

  (it "starts waiting without loading the EDN"
    (let [root (io/file "target" (str "wait-" (System/nanoTime)))]
      (.mkdirs root)
      (try
        (let [s (document/waiting-state "examples/library.edn" (.getPath root))]
          (should (:waiting s))
          (should= "examples/library.edn" (:path s))
          (should= [] (get-in s [:scene :classes]))
          (should-be-nil (:error s))
          (should= 0 (:mail-seen s))
          (should= document/waiting-message "Waiting for agent to create diagram."))
        (finally
          (.delete root)))))

  (it "ignores mailbox already present when waiting starts"
    (let [root (io/file "target" (str "wait-mail-" (System/nanoTime)))
          edn (io/file root "diagram.edn")]
      (.mkdirs (io/file root ".metrics"))
      (.mkdirs (io/file root ".uml-viewer"))
      (spit edn (pr-str {:packages [] :edges []}))
      (try
        (let [path (.getPath edn)
              cmd (mailbox/write-command! (mailbox/to-viewer root) :display
                                          {:path path})
              s (document/waiting-state path)]
          (should= (:id cmd) (:mail-seen s))
          (should (:waiting (document/poll-mail s))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "does not re-apply quit-for-restart after loading the EDN"
    (let [root (io/file "target" (str "load-mail-" (System/nanoTime)))
          edn (io/file root "diagram.edn")]
      (.mkdirs (io/file root ".metrics"))
      (.mkdirs (io/file root ".uml-viewer"))
      (spit edn (pr-str {:packages [] :edges []}))
      (try
        (let [path (.getPath edn)
              cmd (mailbox/write-command! (mailbox/to-viewer root)
                                          :quit-for-restart {})
              s (document/load-path path)]
          (should= (:id cmd) (:mail-seen s))
          (should-not (:quit-for-restart (document/poll-mail s))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "does not auto-reload while waiting for the agent"
    (let [s (assoc (document/waiting-state "examples/library.edn" "target")
              :mtime 0)]
      (should (:waiting (document/maybe-reload s)))
      (should= [] (get-in (document/maybe-reload s) [:scene :classes]))))

  (it "loads the current EDN once waiting is cleared"
    (let [s (-> (document/waiting-state "examples/library.edn" "target")
                (dissoc :waiting)
                (assoc :mtime 0))
          next (document/maybe-reload s)]
      (should-not (:waiting next))
      (should (seq (:classes (:scene next))))
      (should (pos? (:mtime next)))))

  (it "does not throw on a missing file"
    (let [s (document/load-path "no-such-diagram.edn")]
      (should (re-find #"not found" (:error s)))
      (should= [] (get-in s [:scene :classes]))))

  (it "does not throw on invalid EDN"
    (let [f (java.io.File/createTempFile "bad" ".edn")]
      (spit f "{:packages")
      (let [s (document/load-path (.getPath f))]
        (should (string? (:error s)))
        (should= [] (get-in s [:scene :classes])))))

  (it "reloads when the file mtime changes"
    (let [s (assoc (document/load-path "examples/library.edn") :mtime 0)
          next (document/maybe-reload s)]
      (should (pos? (:mtime next)))
      (should-not (:error next))))

  (it "leaves state alone when mtime is unchanged"
    (let [s (document/load-path "examples/library.edn")]
      (should= s (document/maybe-reload s))))

  (it "reloads overlay when metrics snapshots change, keeping an open card"
    (let [root (io/file "target" (str "doc-metrics-" (System/nanoTime)))
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file examples "diagram.edn")
            (pr-str {:hierarchical true
                     :title "Demo"
                     :classes [{:id :board :name "Board" :ns "demo.board"}]
                     :edges []}))
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (try
        (let [path (.getPath (io/file examples "diagram.edn"))
              s (assoc (document/load-path path) :detail-id :board)
              before (first (filter #(= :board (:id %)) (:classes (:doc s))))]
          (should= 1.0 (get-in before [:crap :mu]))
          (spit (io/file metrics "crap.edn")
                (pr-str {:entries [{:name "place" :namespace "demo.board"
                                    :complexity 4 :coverage 50.0 :crap 12.5
                                    :updated true}]}))
          (let [next (document/maybe-reload s)
                after (first (filter #(= :board (:id %)) (:classes (:doc next))))]
            (should= :board (:detail-id next))
            (should= 12.5 (get-in after [:crap :mu]))
            (should-not= (:metrics-stamp s) (:metrics-stamp next))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "records an error when reloaded IR is invalid"
    (let [f (java.io.File/createTempFile "bad" ".edn")]
      (spit f "{:packages [{:classes [{}]}]}")
      (let [next (document/maybe-reload (assoc (state) :path (.getPath f) :mtime 0))]
        (should (string? (:error next)))
        (should (re-find #"reload" (slurp log/*log-file*))))))

  (it "drops a detail id whose class vanished on reload"
    (let [f (java.io.File/createTempFile "uml" ".edn")]
      (spit f "{:packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"} {:id :b :name \"B\"}]}] :edges []}")
      (let [s (document/load-path (.getPath f))
            gone (first (filter #(= "B" (:name %)) (:classes (:scene s))))
            kept-name "A"]
        (spit f "{:packages [{:id :p :label \"P\" :classes [{:id :a :name \"A\"}]}] :edges []}")
        (let [next (document/maybe-reload (assoc s :mtime 0 :detail-id (:id gone)))]
          (should-not (:detail-id next))
          (should (some #(= kept-name (:name %)) (:classes (:scene next))))))))

  (it "paints class-card ops from .metrics keyed by :ns"
    (let [root (io/file "target" "doc-ns")
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (spit (io/file examples "diagram.edn")
            (pr-str {:packages [{:id :p :label "P"
                                 :classes [{:id :board :name "Board"
                                            :ns "demo.board"}]}]
                     :edges []}))
      (try
        (let [s (document/load-path (.getPath (io/file examples "diagram.edn")))
              c (first (filter #(= "Board" (:name %))
                               (get-in s [:scene :classes])))]
          (should (some #(= "place" (:name %)) (:ops c)))
          (should= "demo.board" (:ns c)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "paints hierarchical :doc so the class card lists methods"
    (let [root (io/file "target" "doc-hier")
          examples (io/file root "examples")
          metrics (io/file root ".metrics")]
      (.mkdirs examples)
      (.mkdirs metrics)
      (spit (io/file metrics "crap.edn")
            (pr-str {:entries [{:name "place" :namespace "demo.board"
                                :complexity 1 :coverage 100.0 :crap 1.0}]}))
      (spit (io/file examples "diagram.edn")
            (pr-str {:hierarchical true
                     :title "Demo"
                     :classes [{:id :board :name "Board" :ns "demo.board"}]
                     :edges []}))
      (try
        (let [s (document/load-path (.getPath (io/file examples "diagram.edn")))
              c (first (filter #(= :board (:id %)) (:classes (:doc s))))]
          (should (some #(= "place" (:name %)) (:ops c)))
          (should= "demo.board" (:ns c)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "shifts a scene so routes that swing left of the boxes stay on canvas"
    (let [fit (ns-resolve 'uml-viewer.engine.compose 'fit-scene)
          scene {:packages [{:id :p :rect (geom/rect 40 40 200 80)}]
                 :classes [{:id :a :rect (geom/rect 50 50 80 40)}]
                 :edges [{:from :a :to :a
                          :points [[50 70] [-80 70] [-80 120] [50 120]]}]
                 :size {:w 280 :h 160}}
          fitted (fit scene)
          xs (mapcat #(map first (:points %)) (:edges fitted))]
      (should (<= layout/margin (apply min xs)))
      (should (<= (apply max xs) (get-in fitted [:size :w])))
      (should (< 40 (get-in fitted [:classes 0 :rect :x])))
      (should (>= 0.0 (get-in fitted [:size :min-x] 0)))))

  (it "stacks diagrams top to bottom"
    (let [d {:packages [{:id :p :label "P" :classes [{:id :a :name "A"}]}] :edges []}
          doc {:title "Doc"
               :diagrams [(assoc (ir/normalize d) :title "One")
                          (assoc (ir/normalize d) :title "Two")]}
          scene (document/compile-document doc)
          titles (map :title (:sections scene))]
      (should= ["One" "Two"] titles)
      (should (apply < (map :title-y (:sections scene))))))

  (it "opens one proposal layer and treats a nil focus as the root"
    (let [doc {:hierarchical true
               :title "Demo"
               :proposals [{:id :ccp :name "CCP"
                            :layers [{:id :kernel :label "Kernel" :nses [:domain]}
                                     {:id :shell :label "Shell" :nses [:engine]}]}]
               :classes [{:id :domain :name "Domain" :ns "demo.domain"}
                         {:id :engine :name "Engine" :ns "demo.engine"}]
               :edges [{:from :engine :to :domain :kind :dependency}]
               :order [:domain :engine]}
          rooted (document/compile-view doc "target" [] false)
          nil-focus (document/compile-view doc "target" nil false)
          layer (document/compile-view doc "target" []
                                       {:proposal-id :ccp
                                        :open-layer :proposal.kernel
                                        :declutter :classes})]
      (should= (map :id (:classes rooted)) (map :id (:classes nil-focus)))
      (should= "Kernel" (get-in layer [:diagram :title]))
      (should (some #(= :domain (:id %)) (:classes layer)))))

  (it "finds a sibling policy file only when that file exists"
    (let [root (io/file "target" (str "policy-path-" (System/nanoTime)))
          edn (io/file root "diagram.edn")
          policy (io/file root "diagram.policy.edn")]
      (.mkdirs root)
      (spit policy "{:title \"T\"}")
      (try
        (should-be-nil (document/policy-path-for nil))
        (should-be-nil (document/policy-path-for (.getPath (io/file root "notes.txt"))))
        (should-be-nil (document/policy-path-for (.getPath (io/file root "missing.edn"))))
        (should= (.getPath policy) (document/policy-path-for (.getPath edn)))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true))))))

  (it "writes proposals to the IR and the policy file"
    (let [root (io/file "target" (str "proposals-" (System/nanoTime)))
          edn (io/file root "diagram.edn")
          policy (io/file root "diagram.policy.edn")
          explicit (io/file root "explicit.policy.edn")
          doc {:title "T"
               :proposal {:name "old" :layers []}
               :proposals [{:id :one :name "One" :layers []}
                           {:id :two :name "Two"
                            :layers [{:id :kernel :label "Kernel" :nses [:domain]}]}]}]
      (.mkdirs root)
      (spit policy (pr-str {:title "Keep" :proposal {:name "old"}}))
      (spit explicit (pr-str {:title "Keep" :proposal {:name "old"}}))
      (try
        (let [out (document/write-proposals! (.getPath edn) doc)
              written (edn/read-string (slurp edn))
              pol (edn/read-string (slurp policy))
              by-file (document/write-proposals!
                        nil (assoc doc :policy-file (.getPath explicit)))
              pol2 (edn/read-string (slurp explicit))
              only (io/file root "only.edn")
              skipped (document/write-proposals!
                        (.getPath only)
                        (assoc doc :policy-file (.getPath (io/file root "nope.policy.edn"))))]
          (should= [:one :two] (map :id (:proposals out)))
          (should= ["One" "Two"] (map :name (:proposals out)))
          (should= (:proposals out) (:proposals written))
          (should= (:proposals out) (:proposals pol))
          (should-not (:proposal pol))
          (should= "Keep" (:title pol))
          (should (:proposal out))
          (should= (:proposals by-file) (:proposals pol2))
          (should-not (:proposal pol2))
          (should (.isFile only))
          (should= [:one :two] (map :id (:proposals skipped)))
          (should-not (.exists (io/file root "nope.policy.edn"))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (io/delete-file f true)))))))
