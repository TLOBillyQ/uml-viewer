(ns uml-viewer.lua-language.source-lua-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.lua-language.source-lua]
            [uml-viewer.source :as source]))

(describe "lua extractor"
  (it "finds a table function and opens the file named on the ident"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-lua-src-" (System/nanoTime)))
          file (io/file dir "model.lua")]
      (io/make-parents file)
      (spit file "local M = {}\n\nfunction M.load()\n  return 1\nend\n")
      (let [found (source/member-source {:lang :lua
                                         :ns "app.model"
                                         :file (.getPath file)
                                         :name "M.load"})]
        (should= :lua (:lang found))
        (should (re-find #"model\.lua:3$" (:title found)))
        (should= 3 (:line found)))))

  (it "finds an assignment to a function and a method"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-lua-asg-" (System/nanoTime)))
          file (io/file dir "bank.lua")]
      (io/make-parents file)
      (spit file "M.save = function(x)\n  return x\nend\n\nfunction Account:deposit(a)\n  return a\nend\n")
      (should= 1 (:line (source/member-source {:lang :lua :file (.getPath file)
                                               :name "M.save"})))
      (should= 5 (:line (source/member-source {:lang :lua :file (.getPath file)
                                               :name "Account:deposit"})))))

  (it "returns nil when the member is not in the file"
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "uml-lua-miss-" (System/nanoTime)))
          file (io/file dir "model.lua")]
      (io/make-parents file)
      (spit file "function M.load() end\n")
      (should-be-nil (source/member-source {:lang :lua
                                            :file (.getPath file)
                                            :name "M.missing"})))))
