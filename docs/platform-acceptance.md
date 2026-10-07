# Platform acceptance — issue #5 / spec #1

All-platform support is **pending**. Automated backend simulations do not replace native process, terminal, and GUI acceptance. The subsequent native Windows run below covers installation, real analysis entry points, Claude display/mail and public cleanup; recovery and complete two-project acceptance remain unverified.

## Recorded results

macOS host: Darwin 27.0.0; PowerShell 7.6.6; tmux 3.7c; Temurin Java 21.0.12.1; Claude Code 2.1.291. Installed checkout `f90023c`; restart checkout `ca7fa57`. Two independent fixture directories contained spaces and two nested Clojure namespaces, with a pre-existing named `Saved` proposal.

Evidence root for this run: `/Users/billyq/.claude/jobs/c31d870c/tmp/a5/evidence`. Fixtures and their logs, metrics, policy, mailboxes, and saved view remain under the sibling `Project A With Spaces` and `Project B With Spaces` directories. This location is a local run artifact; the table and remaining procedure below are the durable record.

| Criterion | macOS | Native Windows / psmux 3.3.8 | Linux / tmux | WSL / tmux |
| --- | --- | --- | --- | --- |
| First installation, repeated installation, preserved policy/proposal, path with spaces | Passed, local fixed-ref install and A reinstall; repeat-preservation also covered by entry tests | Pending | Pending | Pending |
| Real `ir`, `crap`, differential `mutate` | Passed both projects, two source files individually; no forced mutation reruns | Partial: A/B crap and ir passed; original mutate blocked by symlink privilege | Pending | Pending |
| Real Claude launch and `:display` consumed by viewer | Passed A and B; Claude generated IR and sent persistent mail | Partial: B passed, full A/B run pending | Pending | Pending |
| FIFO context and regeneration mail, isolated from other project | Passed A `Saved` proposal context then regen; real Claude reported FIFO consumption; both queues empty; B identity unchanged | Pending | Pending | Pending |
| Claude unexpected exit restores same pane and command | Passed: verified A Claude PID 64705 received SIGTERM; restored Claude PID 66469 in session `$1`, pane `%1`; B `$0/%0`, PID 63937 unchanged | Passed: 2026-10-07 fixture 受控 runner 真实恢复（issue #8，见下节）；`#{pane_pid}` 在 psmux 3.3.8 respawn 后永久为空是已定位的 psmux 元数据缺陷，身份改由 server 子进程 OS 证据验证 | Pending | Pending |
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

## 原生 Windows 入口验收 — 2026-10-06

本次环境为 Windows 11 build 26300 / ARM64、PowerShell 7.6.6、Git 2.52.0.windows.1、Claude Code 2.1.291；Windows Terminal 1.24.12741.0 已安装。验收使用固定本地 checkout `b5a4fa1480031c0159143ac9dbe065e10ded2e3c`，在独立临时项目 `Project A With Spaces` 中调用真实 PowerShell 和 `.cmd` 入口，没有使用已有 viewer 或 companion。

13 项检查通过：PS1 安装成功、三个项目入口生成、已有 policy/proposal 的哈希保持不变、重复安装保留用户 `.gitignore` 且仅有一个生成区块、安装器 CMD help、含空格项目路径的 CMD help、非法安装参数返回非零，以及 `ir`、`crap`、`mutate`、fresh start、`--restart` 在缺少 Clojure 时及时返回非零并输出安装指引。依赖失败后无 viewer 进程记录或 launch manifest。首次本地 clone 被共享文件系统的 Git ownership 检查拒绝；验收脚本仅为子进程设置两个精确 `safe.directory` 值后，在新临时项目重跑成功，没有修改全局 Git 配置。

证据目录：`C:\Users\billyq\AppData\Local\Temp\uml-win-acceptance-1ae4d63dda7b47c082edb295a635663c`，包含 `results.json`、首次及重复安装日志、CMD help/非法参数日志、五个依赖失败日志和保留的临时项目。执行脚本位于 `C:\Users\billyq\AppData\Local\Temp\uml-windows-acceptance-20261006.ps1`。这些路径是本机证据，以上结果是持久记录。

首次入口验收时 Java、Clojure CLI 和 psmux 缺失，完整验收被阻塞。用户随后明确授权安装依赖，后续结果见下节；13 项入口检查本身不代表完整平台验收通过。

## 原生 Windows 后续验收 — 2026-10-06 至 2026-10-07

经用户授权，在用户目录安装并核对官方发布校验值：Temurin JDK 21.0.12.1+1 ARM64、Clojure CLI 1.12.6.1673（ClojureTools PowerShell 模块）、psmux 3.3.8 ARM64。环境仅通过验收脚本注入，没有永久修改 PATH 或 profile。基线为 `b5a4fa1`，A/B 安装目录同步了本次工作区修复，因此结果对应带修复的 checkout。

- 修复官方 ClojureTools alias 协议：`-M:crap` 被模块当作源文件；改用 `-M:` 与 alias 两个参数。`-NoProfile` 子进程会先找到 PATH 的 PS1 shim，现显式导入官方模块并重新解析命令。真实 CMD → PS7 → JVM 回归六项通过，覆盖模块、PATH shim、无模块外部 PS1、含空格 cwd/argv、字面 shell 字符、stdout/stderr 和退出码 23。
- 修复 psmux pane target：`=session:%1` 返回 `can't find window: %1`；使用经 session/server 身份核对的原生 `%1`。旧格式记录拒绝使用并给出重建指引。独立真实 session 的创建、身份探测、绑定、recovery hook 设置/撤销及清理通过。
- 修复 mailbox spec 的 Windows 路径断言，使用 canonical path；该组 37 examples / 141 assertions、零失败。
- A/B 的真实 `uml.cmd crap` 与 `uml.cmd ir` 返回 0，两个 namespace 的覆盖率均为 100%。原版 clj-mutate 配置默认调用 `clj`，验收 fixture 改用显式 coverage/test command 与 `--test-roots spec` 后 baseline 通过，但 worker 的 `Files/createSymbolicLink` 因 Windows 缺少权限失败，退出 1。曾临时在外部依赖加入复制 fallback，A/B 各得到 3/3 和 1/1 killed；这些是实验结果，**不算固定版本 mutation 验收通过**。外部依赖已恢复原版，实验补丁单独保存在 evidence。不是 uncovered-mutant 退出 3。
- B 的真实 Claude 启动并发送 `:display`；viewer 消费后 mailbox 为 `{:next-id 2 :queue []}`。首次 context/regen wake 的即时 capture 校验偶发报告 `psmux wake effect was not observed`，但真实 Claude 随后按 FIFO 消费了两条命令；在队列清空后再验一次，两次 wake 均成功、队列清空且新的 display 被消费。该误报已于 2026-10-07 修复（issue #7）：wake 校验改为 2 秒窗口、100 毫秒间隔的有限轮询观察（`companion.clj` 的 `wake-observe-timeout-ms`/`wake-observe-interval-ms`），超时仍报错且 mail 保留在队列。受控 spec 证据：`windows_companion_spec.clj` 新增两个 it，分别证明 capture-pane 前几次不可见、之后才可见的渲染延迟不再误报，以及始终无效果时仍以 `:failure` 抛错；本机全量 spec 496 examples / 1943 assertions，18 项失败均为基线既有环境问题（路径分隔符、缺 Node 等），与该修复无关。修复后的真实 Claude wake 复验仍待进行。
- B 的受控 Claude 退出触发了 respawn，但恢复后 psmux 的 `#{pane_pid}` 为空，harness 以 `For input string: ""` 失败。后来手动诊断 respawn 已改变测试状态，故不能据此证明原始 hook 完整恢复成功；恢复项保持 Pending。该问题由 issue #8 跟踪并已在 2026-10-07 的 recovery 验收（见下节）中定位根因并完成真实恢复验证。
- B 通过 `:quit-for-restart` mailbox 正常退出 viewer；再次核对 session/server/pane 和 owner-start 后，公开 `shutdown-children!` 清理成功，companion 记录为 `{}`。这是公开接口清理，不能代替 GUI close 验收。

本次仓库质量检查亦未全绿：CRAP 退出 1，配置的本地 `crap4clj` 路径 `\\Mac\Home\Desktop\dev\clojure\crap4clj` 不存在；companion/sketch 分别执行差分 mutation，均因 coverage 无法生成退出 1，未执行 mutants，既有快照保留。相关 Windows/sketch/mailbox spec 为 96 examples / 15 failures / 367 assertions；单独 Windows companion + mailbox 为 30 examples / 1 failure / 76 assertions，唯一失败是缺少 Node。全 coverage suite 为 494 examples / 19 failures / 1939 assertions，退出 19；其他失败包括 Unix/macOS fixture、Windows 路径分隔符和换行假设。失败运行的 LCOV 不能算质量检查通过。IR 检查退出 0，输出 `target/companion-sketch-check.edn`；没有改动受保护的 `examples/uml-viewer.edn`。

完整 A/B 并行隔离、wrapper 重启和原 Claude 身份保持、GUI pan/zoom/focus/proposal 恢复、GUI close，以及 Windows Terminal 有无两种情况仍为 Pending。不能声称原生 Windows 或全平台完整验收通过。

本机证据目录：`C:\Users\billyq\AppData\Local\Temp\uml-win-full-20261006-222855\evidence`。包含 B observe、mail-sent/mail-observed/mail-pane、before-recovery、quit-sent，以及 `mutation-experimental-copy.patch`；fixture 的 policy、metrics、mailbox、session EDN 和 viewer log 保留在相邻 A/B 项目。实验 mutation 快照不应被当作原版工具的验收证据。

## 原生 Windows 质量检查修正 — 2026-10-07

针对上一节记录的质量检查失败（issue #10），在独立 worktree（分支 `issue-10-quality`，基线 `spec-6-windows-acceptance` c317a9d）修正，合并 issue #7/#8（a19e1ab）后在合并树上真实重跑，环境同上节的便携 Temurin JDK 21.0.12.1+1 与 Clojure CLI 1.12.6.1673。

- `:crap` alias 由不存在的本地路径 `../clojure/crap4clj` 改为按 git SHA 固定 `io.github.unclebob/crap4clj`（e90be2e），与 `:mutate` 的固定方式一致。crap4clj 经 `sh -c "clj -M:cov --lcov"`、clj-mutate 经直接进程启动调用 `clj`，而官方 Windows Clojure CLI 只有 PowerShell 模块；按要求声明 prerequisite：在 `C:\Users\billyq\.local\bin` 放置转发到 ClojureTools 模块的 `clj`（sh 脚本）与 `clj.exe`（.NET Framework csc 编译的转发器，源码随证据保存），README 已记录该前置条件。
- spec 修正：macOS/Linux fixture 规格的 `with-redefs` 补 `sketch/windows? (fn [] false)`（与 windows_companion_spec 既有 stub 风格一致）；路径断言改为与 `existing-file` 的正斜杠归一一致；stderr 换行断言改用 `System/lineSeparator`；真实 `/bin/sh` 进程规格在无 Unix shell 时、真实 Node 原生 argv 规格在检测不到 Node 时以 speclj `pending` 声明缺失 prerequisite，不再失败。
- 全 spec 套件 `clj -M:spec`：501 examples / 0 failures / 2007 assertions / 2 pending（Unix shell、Node），退出 0。
- 全 coverage `clj -M:cov`（随 CRAP 内部运行）：501 examples / 0 failures，退出 0，LCOV 写入 `target/coverage/lcov.info`，ALL FILES 93.34% 行 / 96.46% 形式。
- CRAP `clj -M:crap`：退出 0；固定版本重写 `.metrics/crap.edn`（单行格式），973 条目（含 #8 新增 3 条），相对旧快照 8 条数值变化，全部位于本分支与 #7/#8 触及的 sketch/companion。
- 差分 mutation：`clj -M:mutate src/uml_viewer/adapters/companion.clj` 与 `.../sketch.clj` 的 coverage 生成均成功、baseline 均 PASS（18.5s），随后 worker 目录 `Files/createSymbolicLink` 因 Windows 缺少符号链接特权（Developer Mode 未启用、非管理员）抛 FileSystemException，退出 1，mutants 未执行，既有快照保留。companion 运行另报告 11 个未覆盖行 mutation（其中 M037–M050 位于 #8 新增的 `windows-recovered!`）。这是 #9 的符号链接权限阻塞，不算 mutation 验收通过，也未改动外部依赖；#8 新增的差分 surface 同样未执行 mutants。
- IR 检查 `clj -M:ir examples/uml-viewer.policy.edn target/ir-check.edn`：退出 0，内容与受保护的 `examples/uml-viewer.edn` 一致（仅行尾符差异），未改动该文件。

证据目录：`C:\Users\billyq\AppData\Local\Temp\uml-win-quality-20261007`，含 spec-suite、coverage、crap、两个 mutate、ir-check 日志及 clj shim 源码（`merged-*` 为合并后结果）。上一节的完整 A/B、GUI、Windows Terminal 等 Pending 项不受影响。

## 原生 Windows recovery 验收 — 2026-10-07（issue #8）

环境同上（Windows 11 build 26300 ARM64、PowerShell 7.6.6、固定 psmux 3.3.8 `66cf613 2026-08-18`）。使用全新 fixture 与独立 psmux session，没有触碰任何真实 Claude 或既有 psmux session；fixture 为可长期运行的假 Claude（`fake-claude.ps1`，逐行消费 stdin 并写 receipt），通过真实 adapter 的受控 encoded runner（`windows-command!` manifest）启动，因此 respawn 恢复的是与生产完全相同的 runner 命令路径。

**根因结论：psmux 3.3.8 元数据缺陷**，不是恢复时序也不是进程身份不符。证据链：新 session 中 `#{pane_pid}` 正确返回 fixture PID（如 4100，父进程为 psmux server）；受控终止后 respawn 真实发生（新 pwsh 5636/3692 作为同一 server 的子进程存在，`pane_dead=0`）；但 `#{pane_pid}` 在随后 15 秒以上、数十次轮询中始终为空，`#{pane_current_command}` 同时损坏（显示主机名 `BILLYPARALLEL` 而非 `pwsh`）。时序假设被持续为空否决；身份不符假设被 OS 进程证据否决。

**规避方案**（已实现在 `companion.clj` 并注释）：`windows-pane-pid!` 对 `#{pane_pid}` 做有界轮询，空元数据永不直接解析，持续为空抛出带 pane 状态（`:pane-dead`/`:pane-command`）的可诊断错误；`windows-recovered!` 在元数据缺失时回退到 OS 证据——枚举已验证 server PID 的直接子进程，要求有且仅有一个携带原始 encoded command token（token 绑定 cwd manifest 与 argv）的新进程，零个报 `:no-recovered-process`，多个报 `:ambiguous-recovered-processes`。注意 psmux respawn 的实际进程会被包一层 `pwsh -NoLogo -Command`，token 子串匹配对此宽容。不升级固定 psmux 版本（维护者决策，超出本修复范围）。

**真实恢复验证结果（ACCEPTANCE-PASS）**：受控终止已验证身份的 fixture（pid 14020，server 3728 子进程）后 pane-died hook 触发 respawn；`windows-recovered!` 在 15 秒 pane_pid 轮询持续为空后回退 OS 证据，确认唯一新进程 pid 3692（同 pane `%1`、同一 encoded command，即同 cwd manifest 与 argv），`pane_dead=0`；恢复后向该 pane 发送字面 mail `MAIL8-AFTER-RECOVERY`，被 respawn 后的 fixture 真实消费并写入 receipt；随后撤销 hook、kill session，`has-session` 返回 1，无残留。

本机证据目录：`C:\Users\billyq\AppData\Local\Temp\uml-issue8\evidence-20261007-105856`，含 `psmux-version.txt`、`before-recovery.txt`（原始身份与命令）、`hook-armed.txt`、`quit-sent.txt`、`after-recovery.txt`（OS 回退证据）、`pane-pid-after-respawn.txt`（缺陷证据）、`identity-verdict.txt`、`mail-after-recovery.txt`、`session-cleanup.txt`。该行为 fixture 级恢复证据；表格中 “Mail works after recovery and restart” 行的 restart 部分仍 Pending。

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
