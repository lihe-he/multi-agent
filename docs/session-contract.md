# 会话与记录接口

适用版本：0.2.0-alpha.5。会话是项目内一组任务与协作记录的持久容器；任务仍保留各自的目标、完成约定、授权、预算、执行目录和验收门禁。

## 接口

所有路径以 `/api/projects/{projectId}/sessions` 为根，需要核心管理令牌。项目专属 MCP 只读令牌不能调用这些管理接口。项目归属校验不是多用户权限系统；当前仍为本机单用户核心。

| 方法与路径 | 输入和结果 |
| --- | --- |
| `POST /` | `{title}` → Session；名称去首尾空白，1–180 字符 |
| `GET /?before=0&limit=50&openOnly=false` | `{sessions,cursor,hasMore}`；稳定创建顺序倒序，页大小限制 1–100 |
| `GET /search?q=搜索词&limit=30` | `{results,hasMore}`；当前项目内全部会话的目标/笔记，包含关闭会话；搜索词去首尾空白，1–120 个 UTF-16 单元且无空字符；页大小限制 1–100，按消息序号倒序 |
| `GET /{sessionId}` | 当前项目中的 Session；不存在或归属不符返回 404 |
| `POST /{sessionId}` | `{expectedVersion,title?,isOpen?}`；版本不符返回 409，`isOpen` 必须是布尔值 |
| `GET /{sessionId}/messages?before=0&after=0&limit=50` | `{messages,cursor,latestCursor,hasMore}`；页大小限制 1–100 |
| `POST /{sessionId}/messages` | `{requestId,content}` → 已保存记录；内容 1–16000 字符，编号限 1–100 个字母、数字、下划线或短横线 |
| `GET /{sessionId}/events?after=0&limit=100` | `{events,cursor,hasMore}`；聚合归属任务的原始活动，按事件编号递增，页大小限制 1–200 |

Session：`{id,projectId,title,version,isOpen,createdAt,updatedAt}`。`version` 管理名称和标签状态的并发更新；新增记录只更新 `updatedAt`，不使浏览中的元数据版本失效。

记录：`{id,sessionId,projectId,requestId,role,kind,content,taskId?,createdAt}`。GET 额外返回数据库单调 `sequence`，不能把 UUID 当游标。POST 的 `role` 固定为 `USER`、`kind` 固定为 `NOTE`，不能伪造模型回答、任务目标或任务来源。已知路由密钥在名称和笔记写入前脱敏；这不是任意敏感文本的自动识别。

同一会话中相同 `requestId` 与相同已处理内容重复提交，返回原记录；不同内容返回 409。此约束由 SQLite 唯一键保障。任务目标使用服务端保留编号 `task:{taskId}`，客户端不能占用该命名空间。

## 分页与续读

会话历史的 `before` 是服务返回的稳定行游标，不按会不断变化的 `updatedAt` 排序。元数据更新不会改变行编号；新建会话不会使已读取的旧页发生位移。

消息默认返回最近一页，并按时间顺序展示。用 `before=cursor` 读更早记录，用 `after=latestCursor` 从最早未读记录开始续读。`before` 与 `after` 不可同时非零，也不可为负。`hasMore` 对应本次读取方向；续读需要一直使用本页末尾游标，不能直接跳到数据库最新编号，否则会丢失中间记录。

活动接口保留实际 `taskId`，供界面跳回原任务。记录与活动具有不同游标，不共享编号或进度。前端对同一面板的并发刷新串行处理，合并时按记录 ID 去重；旧标签的取消请求不会成为新标签的连接故障。当前活动面板保留最近 500 条已读取事件，可继续读取后续事件；完整原始活动仍在任务事件接口中。

搜索结果：`{sessionId,sessionTitle,messageId,sequence,taskId,content,matchStart,matchEnd,createdAt}`。`content` 是原消息的有界纯文本片段；匹配区间按 UTF-16 半开区间返回，供 JavaScript 原样切片，片段边界不切开 Unicode 码点。大小写规则为 Unicode 单码点小写，不执行词干、全角、重音或同义词归一化。三个及以上码点使用 FTS5 trigram，短词使用同一归一化规则的字面扫描；引号、百分号及 FTS 操作符仍按字面搜索。

顶部搜索按钮和 `Ctrl+K` / `Cmd+K` 从任意页面聚焦当前项目的会话检索；已有模态对话框打开时保持当前表单。结果只展示最近 30 条，超出时提示缩小范围。点击结果会先读取会话元数据，必要时按版本重新打开标签，然后读取 `before=sequence+1` 的消息页并聚焦原记录。定位期间冻结消息续读、继续读取任务活动；用户可加载更早记录或返回最新。笔记保存后返回最新消息，草稿仍按会话保留。

## 任务、迁移与生命周期

`POST /api/tasks` 可带 `sessionId`，后端检查其项目归属。未提供时为正常研发任务自动创建会话。任务、初始活动、会话和 `TASK_GOAL` 记录同一事务提交；记录失败时全部回滚，持久层停止新派发。

启动时将旧任务迁入稳定编号 `legacy-{taskId}` 的会话，默认收进历史。迁移只添加 `sessionId` 和目标来源记录，不改变执行状态、代码版本、证据、预算及未确认操作；重复启动不重复写入。SQLite `user_version` 升为 3，结构迁移、派生索引回填和版本更新共用事务，拒绝以本版本打开未来数据版本；健康接口分开报告 `apiVersion=1` 和 `schemaVersion=3`，客户端按 API 协议版本判断兼容性。消息插入、更新与删除的 SQLite 触发器同步维护 FTS 索引；索引失败使原消息事务一并回滚。交付包 manifest 的版本仍独立为 1。结构化快照包含这些会话数据，但仍不等同于工作目录和成果的完整恢复。

关闭标签只更新 `isOpen=false`，后台任务继续；重新打开需要读取当前元数据版本。切换标签保留未提交笔记，在保存响应丢失后沿用请求编号重试。当前草稿与待重试编号限本次工作台挂载期间，离开会话页面或重载会丢失；已保存记录和标签状态持久化。当前选中标签按核心地址与项目保存到本地浏览器。

笔记不会自动发起模型请求，也不会自动注入已经确认的任务上下文。目标变更仍需通过任务约定处理，不能以协作笔记静默修改在途任务。AgentRun、Team、动态委派、活动层级、终端、项目符号/语义检索及 Worker 协议继续实现。
