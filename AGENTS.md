# UML viewer

The diagram is already on screen.

- Do **not** edit `examples/uml-viewer.edn`. It is generated from
  `examples/uml-viewer.policy.edn` via `clj -M:ir`.
- Policy follows **namespace nesting**, not invented layers/components.
  Dots after the prefix are the tree. Do not add Domain/Engine-style
  packages. **Layer** and **component** mean the same thing.
- TypeScript, Rust, Python, and Lua scanners sit beside `clojure-language`. This
  policy stays `:lang :clojure`. Do not change Clojure scanning, source
  extraction, or the rule that missing CRAP and mutation are red.
  CRAP and mutation for other languages are separate tools. The overlay
  joins a snapshot by the class `:ns`, then by the class id. A class
  with no CRAP or mutation data is red. A non-Clojure project sets
  `:lang` or `:sources`.
- `:proposals` are named groupings that are **not** in the source. Preserve
  them when rewriting policy. Do not invent them on launch. The inspector
  lists them; **P** returns to the namespace tree. If instructed, add a
  named proposal (default name is a timestamp) and regenerate the IR.
- After source or policy change: `clj -M:crap`, `clj -M:mutate` on changed
  `src/` files (differential), then `clj -M:ir`. Uncovered mutants are
  coverage gaps: keep the snapshot; do not re-run the file or force a
  full mutation because mutate exited non-zero.
- Mail files are queues `{:next-id n :queue [cmd …]}`. Pop the head of
  `:queue` as you handle it (rewrite the file). Oldest first.
- Right-click quality mail uses public `./uml crap` / `./uml mutate` dispatch
  (install the entry with `get-uml-viewer --install-only` if absent).
  `:refresh-crap` follows declared scope (standard tools measure the whole
  business tree; an operation override may declare target scope);
  `:refresh-mutate` is differential on target files; `:refresh-mutate-all`
  passes `--mutate-all` only on those files. Clojure components invoke mutation
  once per file. Scoped mutation keeps explicit targets, without source-root flags.
- Serialize quality calls. Remove the old `.uml-viewer/quality-result.json`
  before each call, await its actual exit, and require a freshly published
  version-1 record matching operation, canonical project root and actual exit,
  with `validResult: true`. Only then generate IR; only IR exit 0 permits
  `:display`. Missing/stale/mismatched records or failures stop that request's
  IR/display and report the cause; continue later FIFO mail. Keep findings
  snapshots without retries. Exit 3 requires a tool or project declaration
  (this repository's pinned alias contract is in `uml-quality.json`).
- `:omit` → add `:id` to the current proposal's `:omit`, or to policy `:omit`
  on the real diagram, then IR; display only after IR succeeds.
- `:context` mail: `{:context :real}` or `{:context :proposal :proposal-id
  id :name "..."}` is the diagram under discussion. Stay on that context
  until a later `:context` arrives.
- Prefer `./uml` (fresh start: spawn this companion, wait for
  `:display`) and `./uml --restart` (new JVM, keep this session,
  restore last view). If `./uml` is missing, use aliases `:uml-viewer`
  and `:uml-viewer-restart`, or tell the user to run `get-uml-viewer`
  in this directory (it clones uml-viewer into gitignored
  `.uml-viewer/uml-viewer/`).
- Do not start the viewer on launch; EDN reloads when the file mtime
  changes. To restart it: write `:quit-for-restart` to
  `.uml-viewer/to-viewer.edn`, wait for the JVM to exit, then
  `./uml --restart`. The new JVM restores the saved diagram path,
  discussion context, view, and mailbox cursor; omitting the path uses the
  saved diagram, while an explicit path overrides it. The public wrapper
  verifies and waits for the old launcher/JVM identity and refuses missing
  or changed companion ownership instead of replacing it. Do not pass
  `--restart` except through that wrapper. Do not SIGKILL. Companion
  ownership in `.uml-viewer/companion.edn` must explicitly record Herdr;
  closing the viewer cancels recovery first and releases only this
  project's verified resources. Verified abnormal exits retry at most three
  times (1, 2, 4 seconds) through the durable controller; normal or unknown
  exits stop recovery. Other Claude sessions stay up.
- Herdr is the sole companion backend on native Windows/macOS. Linux/WSL
  companion launch is unsupported. Legacy tmux/psmux or unmarked ownership
  is retained and refused; before migration read
  `docs/herdr-backend-contract.md` §9. Windows native acceptance is partial;
  macOS end-to-end acceptance passed under spec #32 (Herdr 0.9.4); terminal
  attach remains NOT VERIFIED (#49).

Do not commit or push unless asked. Esc interrupts a turn in this terminal;
do not kill the process on interrupt.

## Agent skills

### Issue tracker

Issues 和 specs 使用 GitHub Issues；操作前读取 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认 triage 标签；分类和应用标签前读取 `docs/agents/triage-labels.md`。

### Domain docs

领域文档采用 single-context；探索代码前读取 `docs/agents/domain.md`。
