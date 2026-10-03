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

(def ^:private long-open-re #"(?s)\[=*\[.*")

(defn- long-open?
  "A long bracket (`[[` or `[==[`) opens at `i`."
  [source i n]
  (and (< i n)
       (boolean (re-matches long-open-re (subs source i (min n (+ i 64)))))))

(defn- comment-end
  "Index just after the comment that opens at `i`: a long comment ends at
  its closing bracket, a line comment at the newline."
  [source i n]
  (let [j (+ i 2)]
    (if (long-open? source j n)
      (long-bracket-end source j n)
      (or (str/index-of source \newline i) n))))

(defn- quote-char? [c]
  (or (= c \") (= c \')))

(defn- span-at
  "What opens at `i`: `[:blank end]` for a comment or long-bracket string,
  `[:hole end]` for a quoted string, or nil."
  [source i n]
  (let [c (.charAt ^String source i)]
    (cond
      (and (= c \-) (= \- (graph/next-char source i))) [:blank (comment-end source i n)]
      (quote-char? c) [:hole (graph/scan-quoted source i c n)]
      (and (= c \[) (long-open? source i n)) [:blank (long-bracket-end source i n)])))

(defn- mask-step
  "Copy or blank what opens at `i` into `sb`; return the next `[i holes]`."
  [^StringBuilder sb source n [i holes]]
  (let [[kind end] (span-at source i n)]
    (case kind
      :blank (do (blank-span sb source i end)
                 [end holes])
      :hole (do (.append sb (subs source i end))
                [end (conj holes [(inc i) (min n (dec end))])])
      (do (.append sb (.charAt ^String source i))
          [(inc i) holes]))))

(defn- mask-comments
  "Comments and long-bracket strings become spaces (newlines kept).
  A quoted string is copied and recorded as a half-open hole so a match
  inside it can be ignored."
  [source]
  (let [n (count source)
        sb (StringBuilder. n)
        [_ holes] (->> (iterate #(mask-step sb source n %) [0 []])
                       (drop-while #(< (first %) n))
                       first)]
    {:text (str sb) :holes holes}))

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

(defn- require-arg
  "Index of the quote that opens the argument of the `require` at `i`,
  or nil."
  [text i]
  (let [n (count text)
        j (lookahead text (+ i 7))
        k (if (and (< j n) (= \( (.charAt ^String text j)))
            (lookahead text (inc j))
            j)]
    (when (and (< k n) (quote-char? (.charAt ^String text k)))
      k)))

(defn- quoted-name
  "Module name at the start of the string whose quote is at `k`, or nil."
  [text k]
  (let [m (re-matcher #"[A-Za-z_][\w.]*" (subs text (inc k)))]
    (when (.lookingAt m)
      (.group m))))

(defn- require-at
  "Module name required by a `require` at `i`, or nil."
  [text holes i]
  (when (and (not (in-hole? holes i)) (keyword-at? text i "require"))
    (some->> (require-arg text i) (quoted-name text))))

(defn- directives
  "Module names required in `text`: `require \"a\"`, `require(\"a\")`,
  `require 'a'`."
  [text holes]
  (vec (keep #(require-at text holes %) (range (count text)))))

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

(defn- after-local?
  "The statement holding `i` reads `local` just before it."
  [text i]
  (boolean (re-find #"local\s*$" (subs text (statement-start text i) i))))

(def ^:private decl-res
  "Declaration patterns tried at each keyword (nil: any other word), with
  whether they declare a local."
  {"local" [[local-fn-re true] [local-assign-re true]]
   "function" [[fn-decl-re false]]
   nil [[assign-re false]]})

(defn- decl-at
  "Function declaration whose keyword starts at `i`, or nil. `local
  function` and `local name = function` are handled at the `local`."
  [text i kw]
  (when-let [res (get decl-res kw)]
    (when-not (and (not= kw "local") (after-local? text i))
      (let [rest (subs text i)]
        (some (fn [[re local?]]
                (when-let [[_ name] (re-find re rest)]
                  {:name name :local local?}))
              res)))))

(def ^:private lua-keywords
  ["function" "local" "if" "do" "repeat" "end" "until"])

(defn- keyword-of [text i]
  (some #(when (keyword-at? text i %) %) lua-keywords))

(def ^:private depth-steps
  "Block depth change at each keyword. `for` and `while` open their block
  at `do`; `repeat` closes at `until`."
  {"function" 1 "if" 1 "do" 1 "repeat" 1 "end" -1 "until" -1})

(defn- next-depth [kw depth]
  (max 0 (+ depth (get depth-steps kw 0))))

(defn- word-start?
  "`i` begins a word: the previous char is not part of a longer name."
  [text i]
  (or (zero? i)
      (let [p (.charAt ^String text (dec i))]
        (and (not (ident-char? p)) (not= p \.) (not= p \:)))))

(defn- decl-step
  "Fold one position of `text` into `[depth decls]`."
  [text holes [depth acc] i]
  (if (and (not (in-hole? holes i))
           (ident-char? (.charAt ^String text i))
           (word-start? text i))
    (let [kw (keyword-of text i)
          decl (decl-at text i kw)]
      [(next-depth kw depth)
       (if decl (conj acc (assoc decl :nested (pos? depth))) acc)])
    [depth acc]))

(defn- decls
  "Function declarations in `text`. A declaration inside another
  function is nested."
  [text holes]
  (second (reduce #(decl-step text holes %1 %2) [0 []] (range (count text)))))

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
