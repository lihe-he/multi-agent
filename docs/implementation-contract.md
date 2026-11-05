# 实施接口约定（开发协作用）

以计划书 1.1 为产品基线，代码目录 `core/`、`desktop/`、`web/`。Java 包 `dev.agentworkbench`，Java 21 / Spring Boot 3.5.7 / SQLite，React + TypeScript + Vite + Electron。API 监听 127.0.0.1:18765，Bearer 启动令牌，开发代理 `/api`。

## JSON API

- `GET /api/state` → `{projects:[],tasks:[],routes:[],memories:[],profiles:[],system:{version,modelCapacity,activeModels,storageHealthy,capabilities:[]}}`
- `POST /api/projects` `{name,path}` → Project
- `POST /api/projects/{id}/scan` → Project
- `GET /api/projects/{id}/search?q=` → `{results:[{path,line,text}],coverage,version}`
- `POST /api/tasks` `{projectId,title,goal,mode:'AUTO'|'LEAD'|'ROUNDTABLE'|'PIPELINE',routeId,contract:{scope:string[],acceptance:string[],commands:string[]},budget:{maxCalls,reserveCalls},locks:{engine?,model?,provider?,routeId?},includeChanges:false}` → Task
- `GET /api/tasks/{id}` → Task（含详情）
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

调用/验证返回值只提供事实，最终状态与不可变证据由核心持久化。主 Agent 负责 Runtime 与 Electron、启动构建脚本；backend Agent 负责其余 core 与测试；frontend Agent 负责 web；audit Agent 负责端到端检查及实际完成度矩阵。互不覆盖文件。
