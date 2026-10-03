(ns uml-viewer.lua-language.source-lua
  "Lua LanguageSource: open :file and find a function or an assignment
  to a function."
  (:require [uml-viewer.source :as source]))

(def ^:private pattern-fmts
  ["function\\s+%s(?::[A-Za-z_]\\w*)?\\b"
   "local\\s+function\\s+%s(?::[A-Za-z_]\\w*)?\\b"
   "%s\\s*=\\s*function\\b"
   "local\\s+%s\\s*=\\s*function\\b"])

(defn member-line
  "1-based line of `member-name` in `source`, or nil."
  [source member-name]
  (source/member-line-of source member-name pattern-fmts))

(def impl (source/file-source pattern-fmts))

(source/register! :lua impl)
