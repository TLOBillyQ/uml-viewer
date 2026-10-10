# Herdr SessionBackend 契约 — spec #13 / issue #14 产出

本文档把 #14 的双平台原生能力证据落成 #16 及后续实现可直接采用的契约。
所有声明均来自已记录的原生实验（Windows 11 x64 与 macOS arm64，
Herdr 0.9.3 / protocol 22）；未验证的行为明确标注为缺口，不得按官网
声明或命令退出码 0 推断。

证据来源：
- Windows checkpoint：#14 评论（2026-10-10，Herdr 0.9.3 / protocol 22）
- macOS checkpoint：#14 评论（2026-10-10，Darwin 27.0.0 arm64，同上版本）

## 1. 版本预检契约

**唯一有证据的版本是 Herdr 0.9.3（socket protocol 22）。** 不虚构范围：

- 启动器在创建任何资源之前执行预检，三步全部通过才继续：
  1. `herdr --version` 输出 `0.9.3`；
  2. `herdr --session <name> status server` 报告 `endpoint_compatible: yes`
     且 `private_protocol: 22`（server 未运行时先跳过协议核对，server 启动后复核）；
  3. 平台为 macOS 或 Windows。
- 任何一步失败：不创建 session/workspace/pane，报错并给出安装指引
  （Homebrew `herdr` 或 https://herdr.dev），不自动下载、不升级、
  不静默回退旧后端；旧后端只能由用户明确选择。
- 其他版本（包括更高版本）视为未验证，按缺失处理并提示已验证版本号。

## 2. 身份与 ownership 契约

Herdr 的 pane ID 是 **session 局部** 的：两个命名 session 各自都有
`w1:p1`。因此持久化记录必须包含完整寻址链，后续操作按记录路由，
禁止按当前 OS 或默认后端重新猜测：

```clojure
{:backend :herdr
 :owner-role :companion
 :session <命名 session，如 uml-viewer-<project-hash>-<uuid>>
 :socket <session socket 绝对路径>
 :workspace <如 "w1">
 :pane <如 "w1:p1">
 :terminal-id <"term_...">
 :shell-pid <pane shell pid>
 :owner-start <shell 进程启动时间，防 PID 复用>
 :cwd <规范化项目根目录>
 :command <原始命令向量>}
```

- 创建前写 provisional 记录；部分失败后保留，禁止自动再建。
- 每次 `probe!`/`bind!`/`wake!`/`cleanup!` 之前核对：session socket 可达、
  snapshot 中存在该 workspace/pane、`pane process-info` 的 shell_pid 与
  cwd 匹配、owner-start 一致。任一不符或含糊：失败并给诊断，不创建
  替代会话、不关闭猜测目标。
- macOS owner-start 可用 `ps -o lstart=`；Windows 沿用 #15 的进程创建
  时间方案。
- 双项目隔离已验证：A 的 workspace 关闭与 server 停止不影响 B。
  `cleanup!` 只作用于记录中的 session，绝不停共享/其他 session。

## 3. SessionBackend 接口映射

对现有协议（`companion.clj` 的 `SessionBackend`）逐项给出 Herdr 实现
边界：

| 接口 | Herdr 机制 | 契约与已验证语义 |
|---|---|---|
| `create!` | `herdr --session <s> server`（隔离 server）+ `workspace create --cwd <root> --no-focus` | cwd 原样保留（含空格、`&`、中文已验证）。pane 内命令经 **argv 桥接** 投递（见 §4）。 |
| `probe!` | `status server` + `api snapshot` | session 存在且 socket 可达、记录中的 pane 存在于 snapshot 才算存活；超时/含糊不得视为"已不存在"。 |
| `identity!` | snapshot + `pane process-info --pane <id>` | 返回并核对上节全部字段；`terminal_id` 在 pane 存续期稳定。 |
| `bind!` | 同上，纯核对，无副作用 | Viewer 新 JVM 重启后只重新绑定记录的 pane；缺失或身份变化则拒绝启动，不创建替代。 |
| `wake!` | `pane send-text <pane> <text>` + `pane send-keys <pane> enter` | send-text 为**字面投递**，不经 shell 展开（已验证含 `$`、中文、`&` 的文本）。wake 效果需按 §5 观察确认，超时保留邮件。 |
| `recovery!` | Herdr 不提供，由项目自有 runner + 监督层实现 | 见 §6。开启/关闭恢复即注册/注销项目监督。 |
| `cleanup!` | 先注销监督，再 `workspace close <w>`，最后 `session stop` + `session delete` | 顺序固定：先撤恢复再销毁。只清理记录拥有的资源。 |

错误语义（已验证，adapter 必须原样透出）：

- pane 缺失：`pane_not_found`，exit 1；
- 输出等待超时：`timeout`，exit 1，邮件保留在队列；
- 非法按键：`invalid_key`，exit 1；
- CLI 语法错误：exit 2。

## 4. argv 与终端连接契约

- `pane run` 的命令字符串**经 shell 解析**：裸 `$HOME` 会展开。字面 argv
  必须由项目桥接（对每个参数做 POSIX shell 引号转义后拼接；Windows 用
  已验证的 PowerShell ArgumentList 桥）。已验证可字面保留：空格、单引号、
  反引号、`$`、`&`、`;`、Unicode/中文、空串、尾部反斜杠。
- 退出码不由 Herdr 上报，桥接器负责显式捕获（`echo EXIT=$?` 模式或
  runner 写状态文件）。已验证 exit 37/0 均可捕获。
- 终端可见 attach：macOS 有原生 TUI（`herdr session attach <name>`，
  无 TTY 时干净失败 exit 1；交互行为待人工确认）。Windows 直连终端
  attach 不支持，app attachment 有文档但未验证——Windows 的可见终端
  路径是实现期缺口，#16 必须给出明确方案或记录限制。
- companion 的 EDN 邮箱联动不依赖 TUI：socket/CLI 控制与面板输入在
  无 TTY 下全部可用（已验证）。

## 5. wake 效果确认

沿用 #7 的语义：wake 投递后须用 `pane wait-output`（或 socket
`pane.wait_for_output`）在有限窗口内观察消费效果，而非信任写入成功。
已验证 `wait-output` 会立即搜索已有快照再轮询，超时 exit 1。唤醒失败
不丢邮件（队列保留由现有 mailbox 语义保证）。

## 6. 退出检测与恢复契约（项目自有）

已验证的 Herdr 能力边界：

- pane 内子进程正常退出与被 SIGKILL，pane shell 均存活且 shell_pid 不变，
  Herdr **不提供任何区分信号**；Claude 死亡后 agent 从列表消失
  （`agent_not_found`），无原因字段。
- socket `events.subscribe` 的 `pane.exited` 仅在 **pane 进程本身死亡**
  时推送，载荷仅 `{pane_id, workspace_id}`，无退出码；API 主动 close
  不触发；末 pane 死亡自动关闭整个 workspace。
- 同 pane 重启已验证可行：Claude 被 SIGKILL 后在原 pane 重新
  `agent start`/`pane run` 可正常交互。

因此契约如下：

- **runner**：companion 不由 shell 直接跑 Claude，而是跑项目拥有的
  runner（脚本/manifest 进程），runner exec Claude 并把退出码、退出
  时间、信号信息写到项目 `.uml-viewer/` 下的状态文件。这是区分
  正常/异常退出的唯一可靠来源。
- **监督层**：项目实现有限退避重试（次数上限、退避序列、计数重置规则
  在实现时明确记录并确定性测试）。监督通过轮询 `pane process-info`
  的 foreground + 读 runner 状态文件判断退出；`pane.exited` 仅用于
  pane 整体死亡（此时 runner 也死了，视为恢复失败或按配置重建）。
- **区分语义**：runner 状态文件退出码 0 + 主动关闭标记 → 不恢复；
  其他 → 异常，进入退避重试；Viewer 关闭 → 先注销监督取消待重试。
- **恢复动作**：在原 pane 重新投递 runner 命令（同 pane 重启已验证）。
- 若未来 Herdr 版本提供带子进程退出码的事件，可简化 runner，但
  0.9.3 必须按上述实现。

## 7. Viewer 重启连续性

- `:quit-for-restart` 流程不变：保存视图 → JVM 退出 → wrapper 起新 JVM。
- 新 JVM 用 §2 的持久化记录 `bind!` 原 pane（核对 socket/workspace/
  pane/shell_pid/owner-start），不创建新 companion。记录缺失或身份
  含糊 → 明确报错，不开 viewer、不建替代会话。
- Herdr server 独立于 Viewer JVM 存活（headless server 模型已验证），
  companion 跨 JVM 更换存活由架构保证，但端到端 JVM 连续性属
  #20 的原生验收项，本契约不预支结论。

## 8. 明确未验证项（实现与验收时必须重新确认）

- 交互式 TUI attach 的 GUI 行为（双平台；macOS 仅验证了无 TTY 时
  干净失败）。
- Windows app attachment。
- 真实 Viewer JVM restart 后 Herdr ownership 重绑定的端到端连续性。
- 0.9.3 以外的任何版本。
- Linux/WSL（不在本次目标平台内，删除旧后端前须单独说明其路径）。
