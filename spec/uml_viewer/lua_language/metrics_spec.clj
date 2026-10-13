(ns uml-viewer.lua-language.metrics-spec
  "The Lua scanner's namespaces and op names join crapper's and
  mutator's snapshots for the fixture in lua-fixture/."
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.application.overlay :as overlay]
            [uml-viewer.graph :as graph]
            [uml-viewer.lua-language.graph-lua]))

(def ^:private fixture (io/file "lua-fixture"))

(defn- scanned-classes []
  (let [g (graph/scan (graph/lookup :lua) (io/file fixture "src")
                      {:prefix "calc" :ns-prefix ""})]
    (:classes g)))

(describe "lua metrics overlay"
  (it "paints the fixture's classes from the snapshots crapper and mutator wrote"
    (let [classes (scanned-classes)
          doc {:prefix "calc" :packages [{:id :lua-fixture :classes classes}]}
          painted (overlay/apply-metrics doc (overlay/load-metrics fixture))
          by-id (into {} (map (juxt :id identity)
                              (mapcat :classes (:packages painted))))
          calc (by-id :calc)
          util (by-id :util)]
      (should (:crap calc))
      (should (:crap util))
      (should= 0.833333 (:coverage (some #(when (= "M.classify" (:name %)) %) (:ops calc))))
      (should= 2.0 (:mu (:crap (some #(when (= "M.total" (:name %)) %) (:ops calc)))))
      (should (:private (some #(when (= "between" (:name %)) %) (:ops util))))
      (should= 0.0 (:coverage (some #(when (= "M.untested" (:name %)) %) (:ops util))))
      (should (pos? (or (:killed calc) 0)))
      (should (pos? (or (:survived calc) 0))))))
