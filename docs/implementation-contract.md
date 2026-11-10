# 实施接口约定（开发协作用）

以计划书 1.1 为产品基线，代码目录 `core/`、`desktop/`、`web/`。Java 包 `dev.agentworkbench`，Java 21 / Spring Boot 3.5.7 / SQLite，React + TypeScript + Vite + Electron。API 监听 127.0.0.1:18765，Bearer 启动令牌，开发代理 `/api`。

## JSON API

- `GET /api/state` → `{projects:[],tasks:[],routes:[],memories:[],profiles:[],agentDefinitions:[],executionNodes:[],repositoryBindings:[],workers:[],processRecovery:{dispatchAllowed,pendingCount,sessions,hasMore},system:{version,modelCapacity,activeModels,storageHealthy,capabilities:[]}}`
- `POST /api/projects` `{name,path}` → Project
- `POST /api/projects/{id}/scan` → Project
- `GET /api/projects/{id}/search?q=` → `{results:[{path,line,text}],coverage,version}`
- 项目会话、幂等笔记、消息历史和任务活动：见[会话接口约定](./session-contract.md)。`POST /api/tasks` 新增可选 `sessionId`，不提供时自动创建会话。
- `POST /api/tasks` `{projectId,title,goal,mode:'AUTO'|'LEAD'|'ROUNDTABLE'|'PIPELINE',routeId,contract:{scope:string[],acceptance:string[],commands:string[]},budget:{maxCalls,reserveCalls},locks:{engine?,model?,provider?,routeId?},includeChanges:false}` → Task
- `GET /api/tasks/{id}` → Task（含详情）
- 任务/会话的实际 Agent 运行查询、状态和证据关系：见[Agent 运行协议](./agent-run-contract.md)。
- 每轮模型请求的身份、预算、回执、脱敏片段和用量查询：见[模型请求追踪协议](./model-request-contract.md)。
- alpha.8 的版本化 Agent 定义、任务 collaboration、实际父子运行、团队消息/依赖/权限、受控采纳和固定 MCP 原来源：见[动态团队协议](./team-contract.md)。
- alpha.9 的 execution 快照、本机节点/仓库版本、实际 Worker 进程与操作租约、执行授权更新和导出：见[本机 Worker 协议](./worker-contract.md)。API 仍为 1，SQLite schema 为 4。
- alpha.11 的原 Worker/MCP 进程清单、核心重启门禁、出生核验与覆盖/外部效果分别核对：见[进程恢复协议](./process-recovery-contract.md)。API 仍为 1，SQLite schema 为 5；ZIP 保留完整原观察记录。
- alpha.12 的独立根执行尝试、待派发动作、原编号未领取恢复、领取后 UNKNOWN 和核对后的新尝试：见[根派发协议](./task-dispatch-contract.md)。SQLite schema 为 6，API 与 Worker 帧协议仍为 1；ZIP 保留完整尝试和动作，验收与导出等待根结束回执。
- alpha.13 的显式启动选择、固定本地/远程提交、独立 clone/真 worktree、READY 复用和实际归属：见[启动现场协议](./workspace-selection-contract.md)。SQLite schema 为 7，API 与 Worker 帧协议仍为 1；已有准备固定原选择和来源。
- alpha.14 的整次根写入归属、实际文件锁、授予/释放回执、UNKNOWN核对和完整历史：见[根写入保护协议](./workspace-write-contract.md)。SQLite schema 为8，API与Worker帧协议仍为1；`GET /api/tasks/{id}/workspace-write-leases` 提供稳定游标，ZIP保留完整原编号与依据。
- `POST /api/tasks/{id}/{action}`，action 为 `approve`/`pause`/`resume`/`cancel`/`accept`（空对象）；`accept` 只记录人工验收，不写原项目。
- `GET /api/tasks/{id}/diff` → `{diff,version,workspace}`
- `GET /api/tasks/{id}/export` → 可下载 zip
- `GET /api/tasks/{id}/events?after=0&limit=200` → `{events:[{id,taskId,type,message,createdAt}],cursor,hasMore}`
- `GET /api/events?after=0` SSE，可选供桌面使用；前端可用有界事件轮询。
- `POST /api/routes` `{id?,name,engine:'CODEX'|'CLAUDE'|'API',model,provider,baseUrl,executable,enabled:true,apiKey?}` → Route；apiKey 仅会话内。
- `POST /api/routes/{id}/probe` → `{available,version,capabilities,message}`
- `POST /api/memories` `{projectId,kind:'FACT'|'DECISION'|'EXPERIENCE'|'RULE',content,source}` → Memory
- `PATCH /api/memories/{id}` `{content?,status:'ACTIVE'|'DISABLED'|'NEEDS_REVIEW'}`，`DELETE /api/memories/{id}`
- `POST /api/profiles` `{id?,name,role,instructions,capabilities:string[]}` → Profile（版本自增，旧任务快照固定）
- `POST /api/plans/validate` `{stages:[{id,title,role,dependencies:[],inputs:[],outputs:[],acceptance:[],resources:[],permissions:[]}],permissions:[],maxRoles:6}` → `{valid,errors:[]}`

Project: `{id,name,path,branch,head,dirty,fileCount,modules:string[],indexedAt,files:string[]}`。
Task: `{id,projectId,title,goal,mode,modeReason,state,outcome,createdAt,updatedAt,contract,budget:{maxCalls,reserveCalls,usedCalls},locks,routeId,baseline,workspace,version,plan:[{id,title,role,dependencies:[],state}],evidence:[{id,kind,stage,version,command,exitCode,output,createdAt,status}],review:{status,output,version},context:[{id,kind,content,source,version}],issues:[],error?}`。
状态：`DRAFT,QUEUED,RUNNING,PAUSING,PAUSED,WAITING_INPUT,WAITING_RESOURCE,UNKNOWN,CANCELLING,CANCELLED,FAILED,AWAITING_ACCEPTANCE,COMPLETED`。UI 对未知字段容错，但不可用假数据冒充成功。

## Runtime（主 Agent 实现，核心调度调用）

`dev.agentworkbench.runtime.WorkspaceRuntime` 是 Spring Component，构造器注入 `ObjectMapper` 与 `@Value("${workbench.data-dir:./data}") String dataDir`。

方法使用 `Map<String,Object>` 避免模块互相耦合：

```java
Map<String,Object> inspect(String projectPath);
Map<String,Object> prepare(String projectPath, String taskId, boolean includeChanges);
String fingerprint(String workspace);
String diff(String workspace);
String reviewCopy(String workspace, String taskId, String version);
Map<String,Object> probe(Map<String,Object> route);
Map<String,Object> run(Map<String,Object> route, String apiKey, String workspace,
  String prompt, boolean readOnly, List<String> scope,
  java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<String> log,
  java.util.function.BooleanSupplier acquireCall);
Map<String,Object> verify(String workspace, String command,
  java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<String> log);
void stop(String taskId); // 补充停止通过 cancelled 轮询，工作目录包含 taskId
List<Map<String,Object>> search(String projectPath, String query, int limit);
```

`prepare` 返回 `{workspace,baseline,version,sourceDirty}`；run 返回 `{exitCode,output,status:'SUCCEEDED'|'FAILED'|'CANCELLED'|'UNKNOWN',usage:{...}}`。每次真实模型请求前调用 acquireCall；返回 false 必须不发送。CLI 调用保守视为一个不可观测会话预算单位，usage 明确 softBudget。验证命令不消耗模型次数。API agent 的每一轮请求独立申请预算。readOnly 必须实际使用适配器只读权限或隔离目录，API工具层强制只读；不支持强边界的CLI显示能力限制，不宣称OS隔离。

运行器可接收实现 BooleanSupplier 的 `ModelCallObserver`，实际请求前以 begin(metadata) 承诺预算与身份，结束以 end(observation) 保存回执；普通预算回调继续兼容。核心在保存前脱敏输入输出，具体字段与 UNKNOWN 口径见模型请求协议。

调用/验证返回值只提供事实，最终状态与不可变证据由核心持久化。主 Agent 负责 Runtime 与 Electron、启动构建脚本；backend Agent 负责其余 core 与测试；frontend Agent 负责 web；audit Agent 负责端到端检查及实际完成度矩阵。互不覆盖文件。
