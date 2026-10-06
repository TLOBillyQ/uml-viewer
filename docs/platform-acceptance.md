# Platform acceptance — issue #5 / spec #1

All-platform support is **pending**. Automated backend simulations do not replace native process, terminal, and GUI acceptance. Native Windows, Linux, and WSL were unavailable on 2026-10-06.

## Recorded results

macOS host: Darwin 27.0.0; PowerShell 7.6.6; tmux 3.7c; Temurin Java 21.0.12.1; Claude Code 2.1.291. Installed checkout `f90023c`; restart checkout `ca7fa57`. Two independent fixture directories contained spaces and two nested Clojure namespaces, with a pre-existing named `Saved` proposal.

Evidence root for this run: `/Users/billyq/.claude/jobs/c31d870c/tmp/a5/evidence`. Fixtures and their logs, metrics, policy, mailboxes, and saved view remain under the sibling `Project A With Spaces` and `Project B With Spaces` directories. This location is a local run artifact; the table and remaining procedure below are the durable record.

| Criterion | macOS | Native Windows / psmux 3.3.8 | Linux / tmux | WSL / tmux |
| --- | --- | --- | --- | --- |
| First installation, repeated installation, preserved policy/proposal, path with spaces | Passed, local fixed-ref install and A reinstall; repeat-preservation also covered by entry tests | Pending | Pending | Pending |
| Real `ir`, `crap`, differential `mutate` | Passed both projects, two source files individually; no forced mutation reruns | Pending | Pending | Pending |
| Real Claude launch and `:display` consumed by viewer | Passed A and B; Claude generated IR and sent persistent mail | Pending | Pending | Pending |
| FIFO context and regeneration mail, isolated from other project | Passed A `Saved` proposal context then regen; real Claude reported FIFO consumption; both queues empty; B identity unchanged | Pending | Pending | Pending |
| Claude unexpected exit restores same pane and command | Passed: verified A Claude PID 64705 received SIGTERM; restored Claude PID 66469 in session `$1`, pane `%1`; B `$0/%0`, PID 63937 unchanged | Pending | Pending | Pending |
| `:quit-for-restart` saves view, old JVM exits, wrapper starts new JVM, original companion remains | Passed process/mail flow: launcher 64680 exited; wrapper started 67033, Java child 67041; A `$1/%1`, Claude 66469 retained; B unchanged | Pending | Pending | Pending |
| Mail works after recovery and restart | Passed public `request-agent!`/`request-regen!`, both wake results true, real Claude consumed context/regen and sent display; queues empty | Pending | Pending | Pending |
| GUI pan/zoom/declutter/nonempty focus/proposal/open layer restored | Pending GUI interaction; complete disk roundtrip automated test passes in two valid view modes | Pending | Pending | Pending |
| Terminal visible attach and fallback behavior | Pending visual confirmation and forced Ghostty→Terminal fallback; real pane starts successfully; opener failures covered by automated process seam | Pending Windows Terminal present/absent | Pending terminal strategy | Pending compatibility terminal strategy |
| GUI normal close disables recovery and cleans only owned session | Pending GUI click. Public shutdown boundary actually cleaned A while B stayed alive, then B; automatic shutdown ordering tests pass | Pending | Pending | Pending |

macOS GUI automation was refused with Apple Event error `-1743` for System Events. No permission bypass or interaction with existing viewer sessions was attempted. Consequently GUI acceptance remains pending even though real JVM, Claude, mail, and tmux behavior was exercised.

The first fixture harness used `capture_output=True`; the old launcher inherited its wrapper's pipes and kept capture waiting for the viewer's lifetime. A regression test reproduced this (6-second CLI caused a 7.44-second call); the command launcher now gives its child separate standard streams and persists background failures in the project log. The corrected captured entry test returns before the long-lived child exits. The original waiting harness later launched a second fresh A after B exited; that delayed launch was recorded and gracefully cleaned in its own project. It is not evidence of restart continuity.

Relevant local evidence files: `spec.log`, `entries-final.log`, `crap.log`, `mutate-sketch.log`, `ir.log`; `A/B-install.log` and analysis logs; `A-mail-pane.txt`, `A-before/after-recovery.txt`, `B-before/after-recovery.txt`, `A/B-after-restart.txt`, `A-restart.log`, `A-recovery-restart-mail-pane.txt`, `B-after-A-cleanup.txt`, and public shutdown logs. Fixture mailboxes show consumed queues; session EDN shows the saved default view. No screenshots or GUI state claims are inferred from process logs.

## Automated checks

- Full Clojure suite: 489 examples, 0 failures, 1985 assertions.
- Installed entry suite: 14 tests passed, including capture detachment and waiting for an old recorded process to exit.
- Restart rejects a missing recorded companion even when a fallback session exists; probe/bind failure or timeout never opens the viewer or creates a substitute.
- Document save/restart traverses real disk with nonempty namespace focus in one round and valid named proposal/open layer in the other; pan, zoom, declutter, selection, and detail are retained.
- CRAP completed using a command-line `-Sdeps` absolute `crap4clj` override. Only changed `src/uml_viewer/adapters/sketch.clj` received differential mutation: 2/2 killed, exit 0. IR regenerated last. The non-Clojure scanners and mail queue contract were unchanged.

## Review fixes and automated evidence

The final review found unsafe Claude batch argument forwarding, direct background
execution of Clojure batch wrappers, stale README startup guidance, and duplicate
macOS Terminal fallback code. Claude now accepts native `.exe` and `.ps1` entries
only on Windows; `CLAUDE_BIN` remains supported. Unsafe batch entries receive a
native Claude / Node PS1 adapter diagnostic before a companion is created.
Clojure batch entries are rejected synchronously with `clojure.exe` / ClojureTools
/ PS1 guidance, before a detached launcher is spawned. The README includes a
concrete Node adapter for the official npm package. Terminal fallback shares one
implementation with the existing behavioral tests retained.

Real PowerShell argv fixtures on macOS verify quotes, multiline arguments, empty
arguments, Unicode, trailing backslashes and literal shell characters through the
controlled companion runner (PS1 and Node native process), including child exit
status. A simulated Windows command-discovery boundary verifies Clojure batch
rejection before a process record appears. These checks do not establish native
Windows process behavior: the full native Windows procedure below remains pending.
Final review checks: 492 Clojure examples / 1992 assertions, zero failures;
15 installed-entry tests passed. CRAP completed with the temporary absolute
crap4clj root; per-file differential mutation killed 2/2 companion mutants and
17/17 sketch mutants (both exit 0). IR regenerated last. The final entry run also
exposed a fixture read-before-write-completion race; the harness now waits for
the complete recorded argv before asserting.
Local regression evidence is under `/Users/billyq/.claude/jobs/c31d870c/tmp/reviewfix`.

## Repeatable native acceptance procedure

Use two new projects under a short unique temporary root, for example `a5-20261006/Project A With Spaces` and `Project B With Spaces`. Never use an existing project's viewer or companion as a test resource. Record versions and fixed checkout SHA, timestamps, exit codes, project log, policy hashes, mail EDN, saved-view EDN, JVM PID/parent, and exact session/pane/Claude identities. Do not dump credentials, the environment, or unrelated sessions.

1. Install prerequisites yourself: PowerShell 7, Git, Clojure CLI, Java, Claude, and the native backend. On Windows use exactly psmux 3.3.8; run `psmux.exe -V` and retain both provenance lines. On macOS/Linux/WSL use `tmux -V`. Record `pwsh --version`, `java -version`, `clojure -Sdescribe`, and `claude --version`.
2. Create a small Clojure project with two nested namespace files and corresponding tests. Give `deps.edn` genuine `:crap`, `:mutate`, and coverage aliases; configure local roots for that host. Create a namespace-nesting policy with an existing named proposal. Copy the same fixture into A and B. Record the initial policy hash.
3. Install a fixed branch/tag with `UML_VIEWER_REPO_URL` and `UML_VIEWER_REF` set for that invocation. Unix: invoke `pwsh -NoProfile -File /path/to/scripts/get-uml-viewer.ps1 --install-only` from each project. Windows: invoke `pwsh -NoProfile -File C:\path\scripts\get-uml-viewer.ps1 --install-only`. Repeat installation and verify policy/proposal and user configuration are preserved.
4. Unix: run `./uml ir`, `./uml crap`, then `./uml mutate src/demo/core/alpha.clj` and `./uml mutate src/demo/core/beta.clj`, one file per invocation, then `./uml ir`. Windows: use `.\uml.cmd` with the same arguments. Save stdout/stderr/exit status. An uncovered-mutant exit 3 is a coverage gap: retain its snapshot and continue to IR; do not rerun that file or force full mutation.
5. Launch B, then A through their wrappers. Wait to a finite deadline for each real Claude to send `:display`, for the queue to be consumed, and for the diagram to appear. Capture only those test panes. Confirm project cwd, recorded session/pane, and real Claude process. If inherited `CLAUDECODE` prevents nesting, remove only that marker for these explicitly new test processes; do not change global settings.
6. On A, click a named proposal to send context, then request refresh/regen. Observe FIFO queue consumption, actual tool execution, and `:display`. Verify B's session/pane/Claude identity and policy remain unchanged. Do not concurrently rewrite a mailbox being consumed by Claude.
7. Obtain A's exact owned pane PID and verify the real Claude process before interruption. On Unix, `kill -TERM <verified-test-Claude-PID>` is sufficient; do not use SIGKILL. On Windows, use the controlled test-process termination available on that host. Observe new real Claude PID in the same pane with the original cwd and launch command, then send mail and verify real consumption. Never terminate another process or use `kill-server`.
8. Set nondefault pan/zoom/declutter and a nonempty namespace focus via GUI. Ask the test companion to enqueue `:quit-for-restart` (ordinary FIFO queue format). Confirm saved session EDN and old JVM exit. Run `./uml --restart` or `.\uml.cmd --restart`; never pass restart directly to the JVM. Record new JVM PID and unchanged companion session/pane/Claude PID. Verify displayed state and working mail. Repeat with the named proposal and its expanded layer when focus and proposal cannot be simultaneously valid.
9. Close A's viewer using its GUI close control. Confirm its recovery hook was disabled before only A's session was destroyed, no respawn occurred, and B remains usable. Then close B. If GUI is unavailable, record pending and clean only confirmed owned test resources with the existing graceful save/quit and public cleanup boundaries; do not describe that as GUI close acceptance.
10. Platform terminal checks: macOS confirms Ghostty attachment and the fallback to Terminal.app when Ghostty is unavailable; Linux confirms its documented attach/terminal strategy; Windows runs once with Windows Terminal available and once without it, confirming the diagnostic manual attach command can attach to the already-created psmux companion. Repeat Windows failure/timeout binding against its fixed psmux baseline. WSL runs its Linux backend and terminal compatibility checks separately and cannot satisfy native Windows acceptance.

Only change the platform cells to passed when the corresponding real evidence is available. Until every native platform has completed the full procedure, keep the all-platform claim pending.
