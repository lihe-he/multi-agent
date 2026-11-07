# 会话检索设计与自审

日期：2026-11-07。对应计划书 F02 与项目会话基础。本轮实现仅覆盖会话目标和协作笔记，不代表项目源码索引、语义检索或跨仓库搜索已交付。

## 开源参照

| 来源 | 可复用思路 | 本项目取舍 |
| --- | --- | --- |
| [SQLite FTS5](https://www.sqlite.org/fts5.html) | 官方 4.3.4 节说明 trigram 适合子串匹配，少于三个 Unicode 字符不能以 MATCH 命中；支持 `case_sensitive=1` | 使用现有 SQLite 依赖建立派生索引；内容和查询先按同一 Unicode 规则小写，再在敏感模式匹配，短词走字面扫描 |
| [OpenCode Session SQL](https://github.com/anomalyco/opencode/blob/66b25c2bfc39fe73d5da6ebb9a01f3eafbcaeb48/packages/core/src/session/sql.ts) | 同一提交的 `SessionTable`、`SessionMessageTable` 保存稳定 ID 和会话引用；[会话接口](https://github.com/anomalyco/opencode/blob/66b25c2bfc39fe73d5da6ebb9a01f3eafbcaeb48/packages/core/src/session.ts) 通过 message 身份分页 | 沿用 session/message 分离和 sequence 游标；不把上游会话接口当作现成全文搜索实现，不复制上游代码 |

2026-11-07 实际取回原始内容，缓存位于 `.runtime/research-search-2026-11-07/`；`git hash-object`：SQLite 页面 `c21cb03763ba3b57ff25dde356c4831f67af40db`，OpenCode 会话接口 `8c1f40b76792aaf60fdadea6049d88313de25550`、会话 SQL `8684925220d9edeecdb066ca1b2d82afe4f46359`。本机直连失败后使用已配置的代理取回，未引入第三方依赖。

SQLite trigram 对短于三个字符的查询无法生成完整 trigram，因此本实现对少于三个 Unicode 码点的输入回退到项目消息表扫描。三字符及以上使用带引号的 FTS 查询，输入作为字面查询而不是 FTS 操作符表达式。

## 实现边界

- 索引列只检索消息正文；projectId、sessionId、messageId 和 sequence 作为未索引元数据。
- SQLite 触发器对消息插入、更新和删除同步维护索引；消息与索引共用事务。启动升级或索引缺失时从既有消息回填，不改任务状态和原始正文；结构、回填、版本标记共用一个迁移事务，正常重启不重复扫描所有消息。
- 搜索在项目归属校验后执行，返回最多 100 项，默认 30 项；结果片段由纯文本组成，匹配区间由 React 节点呈现。
- 顶栏与 Ctrl/Cmd K 进入当前项目会话搜索。点击结果先重新打开关闭的标签，再按消息序号读取历史页并聚焦原消息。定位模式冻结消息轮询并提供返回最新入口，任务活动继续更新，不跳过定位与最新消息之间的记录。
- 少于三字符的回退查询为线性扫描，规模增长后需要性能测量；本轮不宣称大规模性能验收。

## 自审

- 覆盖中文连续子串、短中文词、无匹配、跨项目隔离、无效项目、符号字面查询、Unicode 大小写/片段区间、结果截断、旧 schema 索引回填、索引故障回滚、迁移失败保全原数据、HTTP 路由和浏览器定位。
- FTS 索引是可再生数据；消息表触发器共事务维护，索引故障会使消息保存失败并封闭派发，避免出现已保存但未索引的消息。自定义折叠函数由核心连接注册，外部程序应通过 API 修改消息。
- 结果局限于消息文本，不覆盖任务活动、运行日志、文件内容、Agent 推理或工具输出。
- UI 高亮不使用 HTML 注入。结果分页目前只取前 30 条，后续可加入稳定游标与更多结果加载。
- 桌面与窄屏图见 `web/qa/session-search-*.png`。未执行大数据规模或长时间写入竞争；跨平台证据以推送后当前 SHA 的 CI 结果为准。
