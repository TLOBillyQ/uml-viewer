(ns uml-viewer.domain.config-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.config :as config]))

(describe "crap thresholds"
  (it "is green at 8, yellow at 12, red at 20"
    (should= 8 (:green config/crap-thresholds))
    (should= 12 (:yellow config/crap-thresholds))
    (should= 20 (:red config/crap-thresholds)))

  (it "bands a missing score as red"
    (should= :red (config/crap-band nil)))

  (it "is green at or below 8"
    (should= :green (config/crap-band 0))
    (should= :green (config/crap-band 8)))

  (it "is yellow above 8 through 12"
    (should= :yellow (config/crap-band 8.1))
    (should= :yellow (config/crap-band 12)))

  (it "is red above 12"
    (should= :red (config/crap-band 12.1))
    (should= :red (config/crap-band 20))
    (should= :red (config/crap-band 100))))

(describe "mutation thresholds"
  (it "is red at 80%, yellow at 90%, green at 100%"
    (should= 0.80 (:red config/mutation-thresholds))
    (should= 0.90 (:yellow config/mutation-thresholds))
    (should= 1.00 (:green config/mutation-thresholds)))

  (it "bands a missing score as red"
    (should= :red (config/mutation-band nil)))

  (it "is red at or below 80%"
    (should= :red (config/mutation-band 0))
    (should= :red (config/mutation-band 0.80)))

  (it "is yellow above 80% through 90%"
    (should= :yellow (config/mutation-band 0.81))
    (should= :yellow (config/mutation-band 0.90)))

  (it "is green above 90%"
    (should= :green (config/mutation-band 0.91))
    (should= :green (config/mutation-band 1.00))))

(describe "crap grade"
  (it "maps 8 to 10, 12 to 5.5, 20 to 1"
    (should= 10.0 (config/crap-grade 8))
    (should= 5.5 (config/crap-grade 12))
    (should= 1.0 (config/crap-grade 20)))

  (it "clamps better than green and worse than red"
    (should= 10.0 (config/crap-grade 0))
    (should= 1.0 (config/crap-grade 100)))

  (it "interpolates between the stops"
    (should= 7.75 (config/crap-grade 10)))

  (it "returns the start of the scale when the two stops are the same"
    (let [lerp (ns-resolve 'uml-viewer.domain.config 'lerp)]
      (should= 10.0 (lerp 4 2 2 10.0 1.0))))

  (it "is red when the score is missing"
    (should= 1.0 (config/crap-grade nil)))

  (it "reads μ+σ from a CRAP map"
    (should= 12.0 (config/crap-risk {:mu 6 :sigma 6}))
    (should-be-nil (config/crap-risk {}))))

(describe "mutation grade"
  (it "maps 80% to 1, 90% to 5.5, 100% to 10"
    (should= 1.0 (config/mutation-grade 0.80))
    (should= 5.5 (config/mutation-grade 0.90))
    (should= 10.0 (config/mutation-grade 1.00)))

  (it "clamps below red and treats a perfect score as 10"
    (should= 1.0 (config/mutation-grade 0.0))
    (should= 10.0 (config/mutation-grade 1.00)))

  (it "is red when the score is missing"
    (should= 1.0 (config/mutation-grade nil)))

  (it "is killed over killed plus survived"
    (should= 1.0 (config/mutation-ratio {:killed 4 :survived 0}))
    (should= 1.0 (config/mutation-ratio {:killed 4}))
    (should= 0.0 (config/mutation-ratio {:survived 3}))
    (should= 0.8 (config/mutation-ratio {:killed 8 :survived 2}))
    (should-be-nil (config/mutation-ratio {:killed 0 :survived 0}))
    (should-be-nil (config/mutation-ratio {}))))

(describe "combined grade"
  (it "averages the two 1–10 scores"
    (should= 10.0 (config/combined-grade 10.0 10.0))
    (should= 5.5 (config/combined-grade 10.0 1.0))
    (should= 1.0 (config/combined-grade 1.0 1.0)))

  (it "uses the one score that is present"
    (should= 10.0 (config/combined-grade 10.0 nil))
    (should= 1.0 (config/combined-grade nil 1.0))
    (should-be-nil (config/combined-grade nil nil))))

(describe "worst child"
  (it "picks the higher μ+σ CRAP map"
    (should= {:mu 20} (config/worse-crap {:mu 1} {:mu 20}))
    (should= {:mu 8} (config/worse-crap {:mu 8} nil))
    (should= {:mu 3} (config/worse-crap nil {:mu 3})))

  (it "treats a class with no CRAP data as worse than any measured score"
    (should= {} (config/worse-crap {:mu 8} {}))
    (should= {} (config/worse-crap {} {:mu 8})))

  (it "picks the lower mutation ratio"
    (let [good {:killed 9 :survived 1}
          bad {:killed 1 :survived 1}]
      (should= bad (config/worse-mutants good bad))
      (should= good (config/worse-mutants good nil))
      (should= bad (config/worse-mutants nil bad))))

  (it "keeps a measured ratio when the other class has no mutant data"
    (let [good {:killed 9 :survived 1}]
      (should= good (config/worse-mutants good {}))
      (should= good (config/worse-mutants {} good)))))
