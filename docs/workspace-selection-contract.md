# 固定启动提交与任务现场

alpha.13 对显式 `workspaceSelection` 保存实际提交和源快照，支持独立 clone 与 locked detached Git worktree。任务、根尝试、准备操作和 Worker 租约共同固定选择，准备完成后保存实际 Git 归属。API 与 Worker 帧协议仍为 1；SQLite schema 为 7，旧核心不能打开新库。

本轮覆盖所选提交的上述两种隔离现场。当前目录直接执行、排他写入租约、完整 worktree 归档和恢复继续实施；准备意图或旧关联不明确时保留目录并拒绝覆盖，不自动删除共享 Git 元数据。

## 查询与创建

`GET /api/repository-bindings/{id}/workspace-options` 需要当前核心访问令牌，返回 `schemaVersion:1`、项目/节点/绑定编号与版本、实际源路径、源快照、本地/远程分支及实际 worktree 注册。分支使用完整 `refs/heads/...` / `refs/remotes/...`；忽略远程 HEAD 别名。当前 HEAD 为独立选择项，不能使用任意 rev 表达式或命令参数。

源快照包含 `head`、`branch`、`dirty`、`statusDigest`、内容 `version` 和原 index SHA-256 `index`。枚举和内容检查使用 `git --no-optional-locks`，不刷新用户原暂存区。读取期间源变化则拒绝返回混合快照。

创建任务在原字段之外可提供：

```json
{
  "repositoryBindingId": "binding-id",
  "bindingVersion": 1,
  "nodeVersion": 1,
  "workspaceSelection": {
    "strategy": "WORKTREE",
    "ref": "refs/heads/main",
    "expectedHead": "actual-commit-sha",
    "sourceVersion": "source-content-fingerprint",
    "sourceIndex": "source-index-sha256"
  },
  "includeChanges": false
}
```

`strategy` 只支持 `CLONE` 和 `WORKTREE`，默认 CLONE；`ref` 默认为 HEAD。其他字段只能是字符串且不接受额外字段。提交、源内容或暂存区与所展示快照不同，则返回 HTTP 409 和固定刷新提示，要求刷新后重新确认；其他内部 Git 错误继续使用通用响应。省略整个 `workspaceSelection` 的历史/API 任务继续使用原 clone 准备路径；显式空对象也会建立新的选择快照。

未提交修改必须明确带入，且所选 baseline 要与当前 HEAD 相同。带入只复制已允许的文件变化，不改原 index；敏感文件、符号链接、特殊文件、子模块及超限文件按运行时边界拒绝。选择其他提交时，项目 AGENTS.md 从该提交读取，并把完整 ref 与 baseline 保存进任务上下文。

## 准备与归属

准备命令使用已固定 commit SHA，不重新解析 ref。clone 独立获取该对象，包括只在远程跟踪引用中出现的提交；副本移除 origin，仅在自己的 Git 配置中写入任务用户和 hooks 设置。worktree 使用 `add --detach --lock --reason`，HEAD/index 独立，普通 Git 历史和配置共享；准备器不写原共享 user/config，也不创建或改变源分支。

任务数据范围内先写 `selection.json` 的 PREPARED 意图，实际目录与 Git 准备完成后再保存 READY。完整回执保存 `workspaceSelection`、`workspaceOwnership`、内容版本与实际路径。归属包括随机 owner、taskId、策略、源路径、实际 gitDirectory/commonDirectory，以及 worktree 的原 lockReason。

只有同任务、同选择的 READY 回执，文件仍等于原准备版本且实际 Git 关联/锁定一致时，未保存任务回执的准备才可复用。此时源仓库已经变化也保持原现场和规则。已开始执行的任务允许正常文件变化，但启动和 Worker 调用仍核对实际归属。部分准备、外国目录、重解析目标、关联改变、锁定理由改变或缺失 Git 标记均拒绝接管和重建。

`GET /api/state` 与 `GET /api/tasks/{id}` 对显式选择任务返回读取时的 `workspacePreparationPresent`。它表示已存在准备记录或目录，不表示 READY 已有效；仅供界面保留原选择。READY 文件已写入而任务 workspace 回执为空时，界面仍隐藏替换入口，同源更新执行授权不强制提交新的选择。实际继续、替换和接管必须重新通过核心门禁，该字段不持久化为准备成功事实。

根排队动作保存完整选择授权，领取后原根尝试不允许改版；准备操作与 Worker 领取必须相同。Worker 在实际操作前核对方法、目录、taskId、选择及带入修改参数。源在排队期间变化会结束本次尝试并进入 WAITING_INPUT，尚未建立准备操作、租约或模型调用；原尝试与预算保留。

## 显式重新确认

`POST /api/tasks/{id}/workspace-selection` 提供 `expectedVersion`、新的 `workspaceSelection` 和可选布尔 `includeChanges`。只允许 DRAFT/PAUSED/WAITING_INPUT/FAILED，且根已收尾、无未知效果、无现场或准备意图。保存时递增选择版本并保留 `workspaceSelectionHistory`，其中同时保留原项目规则内容，避免丢失未提交规则的历史；更新当前项目规则，原根历史与累计预算不重置，随后由用户继续执行。

执行授权更新可同时提供 `expectedWorkspaceVersion`、`workspaceSelection` 和 `includeChanges`。切换源仓库必须重新确认提交；已有目录或准备意图固定原节点/源目录，只更新相同来源的授权。已有准备保留原规则，不从已变化的源重新读取。两种编辑弹窗保留打开时的版本，后台轮询不能绕过版本冲突。

任务 ZIP 的 task.json 保存当前/历史选择与实际归属，task-executions.json 保存每次根选择和结束时归属，准备操作和公开 Worker 租约保留原来源。领取摘要继续从公开租约和 ZIP 去除。验收与导出仍要求根结束回执。

## 验证入口

真实 Git、核心 SQLite 和独立 JVM 回归覆盖固定 refs/SHA、原暂存区/config/refs 保全、带入修改、源过期零操作、原 READY 复用、历史选择、部分准备和参数/归属门禁。浏览器覆盖 1440px/430px 的选择、刷新输入、绑定切换、纯文本 ref、固定历史和版本冲突。

`npm run test:workspaces` 使用当前 Jar 与生产 Web：外部队列门只建立未领取窗口，数据库触发器在原 Worker 保存 READY 并 ACK 后阻止任务回执保存，重启使用普通 JarLauncher。部分 PREPARED 意图另明确标为人工故障窗口。报告保存原编号、SHA、Git 元数据、夹具来源、原 PID/出生核验和 ZIP；实际结果写入 `docs/qa/workspace-selection-<platform>.json`，失败现场单独保留，不以历史报告代替新产物验证。
