(ns uml-viewer.lua-language.graph-lua
  "Lua LanguageGraph: one class per module, require edges, function ops."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.graph :as graph]))

(def ^:private skip-dir-names
  #{"lua_modules" "spec" "tests" "test"
    "dist" "build" "target" "out" "coverage"})

(defn- skip-dir? [name]
  (or (str/starts-with? name ".")
      (contains? skip-dir-names name)))

(defn- excluded-name? [name]
  (str/ends-with? name "_spec.lua"))

(defn- source-files [root]
  (let [root (.getCanonicalFile (io/file root))]
    (->> (tree-seq (fn [f]
                     (and (.isDirectory f)
                          (or (= f root) (not (skip-dir? (.getName f))))))
                   (fn [dir] (vec (.listFiles dir)))
                   root)
         (filter #(.isFile %))
         (filter #(str/ends-with? (.getName %) ".lua"))
         (remove #(excluded-name? (.getName %)))
         (sort-by #(.getPath %)))))

(defn- blank-span [sb source i end]
  (doseq [k (range i end)]
    (.append ^StringBuilder sb (if (= \newline (.charAt ^String source k)) \newline \space))))

(defn- long-bracket-end
  "Index just after the long bracket that opens at `i` (`[==[ ... ]==]`),
  or the end of the source."
  [source i n]
  (let [m (re-find #"^\[(=*)\[" (subs source i (min n (+ i 64))))
        eqs (count (second m))
        closer (str "]" (str/join (repeat eqs "=")) "]")]
    (if-let [j (str/index-of source closer (+ i 2 eqs))]
      (+ j (count closer))
      n)))

(defn- mask-comments
  "Comments and long-bracket strings become spaces (newlines kept).
  A quoted string is copied and recorded as a half-open hole so a match
  inside it can be ignored."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)]
    (loop [i 0 holes []]
      (if (>= i n)
        {:text (str sb) :holes holes}
        (let [c (.charAt ^String source i)
              nxt (graph/next-char source i)]
          (cond
            (and (= c \-) (= nxt \-))
            (let [j (+ i 2)
                  long? (and (< j n)
                             (re-matches #"(?s)\[=*\[.*"
                                         (subs source j (min n (+ j 64)))))
                  end (if long?
                        (long-bracket-end source j n)
                        (or (str/index-of source \newline i) n))]
              (blank-span sb source i end)
              (recur end holes))

            (or (= c \") (= c \'))
            (let [end (graph/scan-quoted source i c n)]
              (.append ^StringBuilder sb (subs source i end))
              (recur end (conj holes [(inc i) (min n (dec end))])))

            (and (= c \[)
                 (re-matches #"(?s)\[=*\[.*" (subs source i (min n (+ i 64)))))
            (let [end (long-bracket-end source i n)]
              (blank-span sb source i end)
              (recur end holes))

            :else
            (do (.append ^StringBuilder sb c)
                (recur (inc i) holes))))))))

(defn- in-hole? [holes i]
  (boolean (some (fn [[a b]] (and (<= a i) (< i b))) holes)))

(defn- ident-char? [c]
  (or (Character/isLetterOrDigit ^char c) (= c \_)))

(defn- keyword-at? [text i word]
  (and (.startsWith ^String text ^String word (int i))
       (or (zero? i) (not (ident-char? (.charAt ^String text (dec i)))))
       (let [j (+ i (count word))]
         (or (>= j (count text))
             (not (ident-char? (.charAt ^String text j)))))))

(defn- lookahead
  "First index at or after `i` that is not a space or tab."
  [text i]
  (loop [j i]
    (if (and (< j (count text))
             (let [c (.charAt ^String text j)]
               (or (= c \space) (= c \tab))))
      (recur (inc j))
      j)))

(defn- directives
  "Module names required in `text`: `require \"a\"`, `require(\"a\")`,
  `require 'a'`."
  [text holes]
  (let [n (count text)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (if (and (not (in-hole? holes i)) (keyword-at? text i "require"))
          (let [j (lookahead text (+ i 7))
                paren? (and (< j n) (= \( (.charAt ^String text j)))
                k (lookahead text (if paren? (inc j) j))]
            (if (and (< k n)
                     (not (in-hole? holes k))
                     (#{\" \'} (.charAt ^String text k)))
              (let [m (re-matcher #"[A-Za-z_][\w.]*" (subs text (inc k)))
                    end (graph/scan-quoted text k (.charAt ^String text k) n)]
                (if (.lookingAt m)
                  (recur end (conj acc (.group m)))
                  (recur end acc)))
              (recur (inc i) acc)))
          (recur (inc i) acc))))))

(defn- statement-end
  "End of the statement that starts at `i`: the next newline or `;`."
  [text i]
  (or (str/index-of text \newline i)
      (str/index-of text \; i)
      (count text)))

(defn- statement-prefix
  "Text from `i` to the end of its statement. `i` must be the first
  non-blank position of that statement."
  [text i]
  (subs text (lookahead text i) (statement-end text i)))

(def ^:private fn-decl-re
  #"(?s)^function\s+([A-Za-z_][\w.]*(?::[A-Za-z_]\w*)?)")

(def ^:private local-fn-re
  #"(?s)^local\s+function\s+([A-Za-z_][\w.]*(?::[A-Za-z_]\w*)?)")

(def ^:private assign-re
  #"(?s)^([A-Za-z_][\w.]*(?::[A-Za-z_]\w*)?)\s*=\s*function\b")

(def ^:private local-assign-re
  #"(?s)^local\s+([A-Za-z_][\w.]*(?::[A-Za-z_]\w*)?)\s*=\s*function\b")

(defn- statement-start
  "Start of the statement that contains `i`."
  [text i]
  (loop [j (dec i)]
    (cond
      (neg? j) 0
      (#{\newline \;} (.charAt ^String text j)) (inc j)
      :else (recur (dec j)))))

(defn- decl-at
  "Function declaration whose keyword starts at `i`, or nil."
  [text i kw]
  (let [rest (subs text i)]
    (cond
      (= kw "local")
      (or (when-let [[_ name] (re-find local-fn-re rest)]
            {:name name :local true})
          (when-let [[_ name] (re-find local-assign-re rest)]
            {:name name :local true}))

      (= kw "function")
      ;; `local function` is handled at the `local` keyword.
      (when-not (re-find #"local\s*$" (subs text (statement-start text i) i))
        (when-let [[_ name] (re-find fn-decl-re rest)]
          {:name name :local false}))

      (nil? kw)
      ;; `local name = function` is handled at the `local` keyword.
      (when-not (re-find #"local\s*$" (subs text (statement-start text i) i))
        (when-let [[_ name] (re-find assign-re rest)]
          {:name name :local false})))))

(def ^:private lua-keywords
  ["function" "local" "if" "for" "while" "do" "end" "repeat" "until"])

(defn- keyword-of [text i]
  (some #(when (keyword-at? text i %) %) lua-keywords))

(defn- next-depth [kw depth repeat-depth]
  (case kw
    "function" [(inc depth) repeat-depth]
    "if" [(inc depth) repeat-depth]
    "for" [(inc depth) repeat-depth]
    "while" [(inc depth) repeat-depth]
    "do" [(inc depth) repeat-depth]
    "repeat" [depth (inc repeat-depth)]
    "end" [(max 0 (dec depth)) repeat-depth]
    "until" [depth (max 0 (dec repeat-depth))]
    [depth repeat-depth]))

(defn- word-start?
  "`i` begins a word: the previous char is not part of a longer name."
  [text i]
  (or (zero? i)
      (let [p (.charAt ^String text (dec i))]
        (and (not (ident-char? p)) (not= p \.) (not= p \:)))))

(defn- decls
  "Function declarations in `text`. A declaration inside another
  function is nested."
  [text holes]
  (let [n (count text)]
    (loop [i 0 depth 0 repeat-depth 0 acc []]
      (if (>= i n)
        acc
        (let [c (.charAt ^String text i)]
          (if (and (not (in-hole? holes i)) (ident-char? c) (word-start? text i))
            (let [kw (keyword-of text i)
                  [nd nr] (next-depth kw depth repeat-depth)
                  decl (decl-at text i kw)
                  item (when decl (assoc decl :nested (pos? depth)))]
              (recur (inc i) nd nr (if item (conj acc item) acc)))
            (recur (inc i) depth repeat-depth acc)))))))

(defn read-module
  "Module surface of Lua `source`: required module names, and top-level
  function declarations with their visibility."
  [source]
  (let [{:keys [text holes]} (mask-comments source)]
    {:requires (directives text holes)
     :defns (decls text holes)}))

(defn- module-relative
  "Module name of `file` under `root`: strip the longest of
  `src/lua/`, `src/`, `lua/`, drop `.lua`, collapse a trailing `/init`,
  then `/` becomes `.`."
  [file root]
  (let [root (.getCanonicalFile (io/file root))
        file (.getCanonicalFile (io/file file))
        rel (str/replace (str (.relativize (.toPath root) (.toPath file))) #"\\" "/")]
    (-> (reduce (fn [path prefix]
                  (if (str/starts-with? path prefix)
                    (str/replace-first path prefix "")
                    path))
                rel
                ["src/lua/" "src/" "lua/"])
        (str/replace #"\.lua$" "")
        (str/replace #"(^|/)init$" "$1")
        (str/replace #"/$" "")
        (str/replace "/" "."))))

(defn- ns-join [ns-prefix relative]
  (cond
    (str/blank? relative) (str ns-prefix)
    (str/blank? ns-prefix) relative
    (or (= relative ns-prefix)
        (str/starts-with? relative (str ns-prefix "."))) relative
    :else (str ns-prefix "." relative)))

(defn- file-meta [file root prefix ns-prefix]
  (let [relative (module-relative file root)
        ns-str (ns-join ns-prefix relative)]
    {:file file
     :relative relative
     :ns ns-str
     :id (graph/id-of ns-str prefix)}))

(defn- resolve-name [name by-rel]
  (if-let [id (get by-rel name)]
    {:id id}
    {:foreign name}))

(defrecord LuaGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (let [prefix (or (:prefix opts) "app")
          ns-prefix (or (:ns-prefix opts) prefix)
          rootf (.getCanonicalFile (io/file root))
          files (source-files rootf)
          metas (mapv #(file-meta % rootf prefix ns-prefix) files)
          by-rel (into {} (keep (fn [m]
                                  (when-not (str/blank? (:relative m))
                                    [(:relative m) (:id m)]))
                                metas))
          parsed (mapv (fn [m]
                         (let [surface (read-module (slurp (:file m)))
                               hits (map #(resolve-name % by-rel) (:requires surface))
                               publics (remove #(or (:local %) (:nested %))
                                               (:defns surface))]
                           (merge m
                                  {:name (graph/module-name (:id m))
                                   :lang :lua
                                   :file (graph/relative-path (:file m))
                                   :requires (vec (distinct (keep :id hits)))
                                   :foreigns (vec (distinct (keep :foreign hits)))
                                   :ops (mapv (fn [d] {:name (:name d) :text (:name d)})
                                              publics)})))
                         metas)
          project-ids (set (map :id parsed))
          foreigns (->> parsed
                        (mapcat :foreigns)
                        distinct
                        (remove project-ids)
                        (mapv #(graph/foreign-class (keyword %))))]
      {:classes (into (mapv #(dissoc % :relative :requires :foreigns) parsed)
                      foreigns)
       :edges (->> parsed
                   (mapcat (fn [c]
                             (concat
                               (map (fn [to] {:from (:id c) :to to :kind :dependency})
                                    (:requires c))
                               (map (fn [to] {:from (:id c) :to (keyword to) :kind :dependency})
                                    (:foreigns c)))))
                   (remove #(= (:from %) (:to %)))
                   distinct
                   vec)})))

(def impl (->LuaGraph))

(graph/register! :lua impl)
