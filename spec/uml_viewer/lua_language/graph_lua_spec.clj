(ns uml-viewer.lua-language.graph-lua-spec
  (:require [clojure.java.io :as io]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.lua-language.graph-lua :as lua]))

(defn- spit-file [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- temp-root []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "uml-lua-" (System/nanoTime))))

(def ^:private fixture
  (io/file "lua-fixture"))

(describe "lua module surface"
  (it "reads requires and function declarations"
    (let [surface (lua/read-module
                    (str "local util = require(\"calc.util\")\n"
                         "local cfg = require 'config'\n"
                         "local lfs = require(\"lfs\")\n"
                         "local function between(x)\n"
                         "  return x\n"
                         "end\n"
                         "function M.clamp(x)\n"
                         "  return between(x)\n"
                         "end\n"
                         "M.total = function(xs)\n"
                         "  return xs\n"
                         "end\n"
                         "function Account:deposit(amount)\n"
                         "  return amount\n"
                         "end\n"))]
      (should= ["calc.util" "config" "lfs"] (:requires surface))
      (should= ["between" "M.clamp" "M.total" "Account:deposit"]
               (map :name (:defns surface)))
      (should= [true false false false] (map :local (:defns surface)))
      (should= [false false false false] (map :nested (:defns surface)))))

  (it "ignores comments, strings, and long brackets"
    (let [surface (lua/read-module
                    (str "-- require(\"nope\")\n"
                         "--[[\nrequire(\"nope\")\n]]\n"
                         "--[==[ require(\"nope\") ]==]\n"
                         "local s = \"require(\\\"nope\\\")\"\n"
                         "local t = 'function nope() end'\n"
                         "local u = [==[ function nope() end ]==]\n"
                         "local ok = require(\"real\")\n"))]
      (should= ["real"] (:requires surface))
      (should= [] (:defns surface))))

  (it "does not split on a dot or a hyphen in a name"
    (let [surface (lua/read-module "function M.foo_bar.baz(x) return x end\n")]
      (should= ["M.foo_bar.baz"] (map :name (:defns surface)))))

  (it "counts a declaration inside another function as nested"
    (let [surface (lua/read-module
                    (str "function outer()\n"
                         "  local function inner() return 1 end\n"
                         "  local pick = function(x) return x end\n"
                         "  if x then local function mid() end end\n"
                         "end\n"
                         "function top() end\n"))]
      (should= ["outer" "inner" "pick" "mid" "top"] (map :name (:defns surface)))
      (should= [false true true true false] (map :nested (:defns surface)))))

  (it "closes a loop or repeat block once"
    (let [surface (lua/read-module
                    (str "while x do y() end\n"
                         "for i = 1, 2 do y() end\n"
                         "repeat y() until x\n"
                         "do local function scoped() end end\n"
                         "function after() end\n"))]
      (should= ["scoped" "after"] (map :name (:defns surface)))
      (should= [true false] (map :nested (:defns surface)))))

  (it "reads require with spaces, without parens, and skips non-names"
    (let [surface (lua/read-module
                    (str "local a = require ( \"a.b\" )\n"
                         "local b = require\t'c'\n"
                         "local c = require(\"1bad\")\n"
                         "local d = require(name)\n"
                         "local e = required(\"no\")\n"
                         "local f = require"))]
      (should= ["a.b" "c"] (:requires surface))))

  (it "masks an unclosed long comment and a line comment at the end"
    (let [surface (lua/read-module
                    (str "function kept() end\n"
                         "--[[ require(\"nope\")\n"
                         "function gone() end"))]
      (should= [] (:requires surface))
      (should= ["kept"] (map :name (:defns surface))))
    (should= [] (:requires (lua/read-module "-- require(\"nope\")"))))

  (it "reads a top-level setmetatable base bound by require"
    (let [surface (lua/read-module
                    (str "local Base = require(\"app.base\")\n"
                         "local Mixin = require 'app.mixin'\n"
                         "local Other = require(\"app.other\")\n"
                         "local Child = setmetatable({}, {__index = Base})\n"
                         "setmetatable(Child, Mixin)\n"
                         "local Plain = setmetatable({}, Unbound)\n"
                         "-- setmetatable({}, Other)\n"
                         "local s = \"setmetatable({}, Other)\"\n"
                         "function Child.new()\n"
                         "  return setmetatable({}, Other)\n"
                         "end\n"
                         "x.setmetatable({}, Other)\n"))]
      (should= ["app.base" "app.mixin"] (:bases surface))
      (should= ["app.base" "app.mixin" "app.other"] (:requires surface))))

  (it "skips an unclosed string at the end of the file"
    (let [surface (lua/read-module "local s = \"require(\"yes\")\n")]
      (should= [] (:requires surface)))))

(describe "lua graph"
  (it "scans modules, resolves requires, and keeps foreign modules"
    (let [dir (temp-root)]
      (spit-file dir "src/app/init.lua"
                 (str "local model = require(\"app.model\")\n"
                      "local lfs = require(\"lfs\")\n"
                      "local M = {}\n"
                      "function M.run() return model.load() end\n"
                      "return M\n"))
      (spit-file dir "src/app/model.lua"
                 (str "local function hidden() end\n"
                      "local M = {}\n"
                      "function M.load() return 1 end\n"
                      "M.save = function(x) return x end\n"
                      "return M\n"))
      (spit-file dir "spec/app_spec.lua" "require(\"app\")\n")
      (spit-file dir "lua_modules/x.lua" "require(\"app\")\n")
      (let [g (graph/scan (graph/lookup :lua) (io/file dir "src")
                          {:prefix "app"})
            by-id (into {} (map (juxt :id identity) (:classes g)))]
        (should= #{:app :model :lfs} (set (map :id (:classes g))))
        (should= "app" (:ns (by-id :app)))
        (should= "app.model" (:ns (by-id :model)))
        (should= :lua (:lang (by-id :app)))
        (should (:foreign (by-id :lfs)))
        (should= ["M.run"] (map :name (:ops (by-id :app))))
        (should= ["M.load" "M.save"] (map :name (:ops (by-id :model))))
        (should= #{{:from :app :to :model :kind :dependency}
                   {:from :app :to :lfs :kind :dependency}}
                 (set (:edges g))))))

  (it "draws inheritance to a project module a top-level setmetatable uses"
    (let [dir (temp-root)]
      (spit-file dir "src/app/base.lua"
                 (str "local Base = {}\n"
                      "Base.__index = Base\n"
                      "return Base\n"))
      (spit-file dir "src/app/child.lua"
                 (str "local Base = require(\"app.base\")\n"
                      "local lfs = require(\"lfs\")\n"
                      "local Child = setmetatable({}, {__index = Base})\n"
                      "local Odd = setmetatable({}, lfs)\n"
                      "return Child\n"))
      (let [g (graph/scan (graph/lookup :lua) (io/file dir "src")
                          {:prefix "app"})]
        (should= #{{:from :child :to :base :kind :inheritance}
                   {:from :child :to :base :kind :dependency}
                   {:from :child :to :lfs :kind :dependency}}
                 (set (:edges g)))
        (should-not (some :inherits (:classes g))))))

  (it "names a module from the longest of src/lua, src, and lua"
    (let [dir (temp-root)]
      (spit-file dir "src/lua/deep/mod.lua" "function M.go() end\n")
      (spit-file dir "src/plain.lua" "function M.go() end\n")
      (spit-file dir "lua/loose/init.lua" "function M.go() end\n")
      (spit-file dir "init.lua" "function M.go() end\n")
      (let [g (graph/scan (graph/lookup :lua) dir {:prefix "app"})
            nss (set (map :ns (:classes g)))]
        (should= #{"app.deep.mod" "app.plain" "app.loose" "app"} nss))))

  (it "keeps the ns of crapper's Lua fixture so metrics join"
    (let [g (graph/scan (graph/lookup :lua) (io/file fixture "src")
                        {:prefix "calc" :ns-prefix ""})
          by-id (into {} (map (juxt :id identity) (:classes g)))]
      (should= #{"calc" "calc.util"} (set (map :ns (:classes g))))
      (should= ["M.classify" "M.total"] (map :name (:ops (by-id :calc))))
      (should= ["M.clamp" "M.untested"] (map :name (:ops (by-id :util))))
      (should= #{{:from :calc :to :util :kind :dependency}}
               (set (:edges g))))))
