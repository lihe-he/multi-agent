# 持久请求用量趋势协议

适用版本：0.2.0-alpha.30，协议版本 1。趋势从已保存的 `ModelRequest` 派生，不累计 AgentRun 汇总、根预算或当前路由配置。请求创建、结束与预算事务仍按[模型请求协议](./model-request-contract.md)执行。

## 查询与范围

`GET /api/projects/{id}/usage-trends?days=7&sessionId=&taskId=`

`days` 只允许 7、30、90，省略为 7；无效值返回 400。项目必须存在，非空会话和任务必须属于该项目；同时给出会话与任务时，任务还须属于此会话，否则返回 404。筛选统计原请求保存的归属，不因当前任务或路由编辑重新归类历史请求。

接口沿用核心访问令牌和本机来源校验。项目 MCP 令牌无权读取。查询不启动 Worker、调用模型、生成请求或改变预算，不返回提示词、响应片段、供应商请求身份或凭据。

日桶固定使用 UTC，包含今天，按日期升序补齐 7/30/90 桶。`from` 为首日 00:00，包含；`toExclusive` 为明日 00:00，不包含。记录按原 `createdAt` 分桶，完成或取消不会将记录移至结束当天。`asOf` 是本次读取的时钟，不代表所有记录已经结束。

## 响应

```ts
type Metric = {
  observedSum: number | 'UNKNOWN';
  knownCount: number;
  unknownCount: number;
  total: number | 'UNKNOWN';
  overflow: boolean;
};
type Unit = {
  recordCount: number;
  hasRecords: boolean;
  states: Record<'DISPATCHING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN', number>;
  tokens: Record<'inputTokens' | 'outputTokens' | 'cachedInputTokens' | 'reasoningTokens', Metric>;
};
type Aggregate = {
  recordCount: number;
  hasRecords: boolean;
  units: Record<'API_REQUEST' | 'CLI_SESSION' | 'UNKNOWN', Unit>;
};
type UsageTrends = {
  schemaVersion: 1;
  source: 'MODEL_REQUEST';
  scope: { projectId: string; sessionId: string; taskId: string };
  days: 7 | 30 | 90;
  timeZone: 'UTC';
  bucket: 'DAY';
  from: string;
  toExclusive: string;
  asOf: string;
  cost: 'UNKNOWN';
  cliInternalRequests: 'UNKNOWN';
  summary: Aggregate;
  buckets: (Aggregate & { date: string })[];
};
```

每个字段独立记录覆盖情况：非负 JSON 整数为已知；缺失、null、字符串、负数、小数、布尔值、对象和数组为未知。数值超出 SQLite 有符号整数表示范围时也不能作为已知整数。明确的数值 0 保留为 0。

`observedSum` 是已知值的精确子和，`knownCount` 是报告有效值的请求数，`unknownCount` 是未报告有效值的请求数。只有至少存在一条记录、该字段全部已知且没有溢出时，`total` 才返回数值。无记录时 `hasRecords=false`，指标为 `{observedSum:0, knownCount:0, unknownCount:0, total:'UNKNOWN', overflow:false}`，不等于运行器报告了零消耗。

累计使用精确整数。已知子和超过 JavaScript `Number.MAX_SAFE_INTEGER` 时，`observedSum` 和 `total` 都为 `UNKNOWN`，`overflow=true`；覆盖计数继续保留，不能把已知的大数改成缺失回执。每日与整个范围分别判断，单日可表示不保证跨日摘要也可表示。

`API_REQUEST` 的记录数代表外层 API 请求，`CLI_SESSION` 代表外层 CLI 会话；缺失或无法识别的单位保留在 `UNKNOWN` 列。CLI 内部请求次数和费用始终未知。缓存 Token 已包含在输入 Token 中，推理 Token 已包含在输出 Token 中；两项只作明细，不将四项相加。未识别状态进入 `UNKNOWN`，请求失败或取消不推测用量为零。

## 存储与界面

新增项目与原创建时间的 ModelRequest 局部索引。一次 SQL 聚合产生有界的日×单位结果，摘要与日桶从同批结果生成。时间索引先缩小到范围前后各一天，再解析 ISO 时间为 UTC 日，兼容带时区偏移的旧记录；缺失或无效创建时间没有可推测的日桶，排除统计。没有保存的旧请求也不补造。

界面提供项目入口和时间、会话、任务筛选；会话选择包含关闭的历史会话。读取更新和错误重试只发送 GET，筛选、项目或连接变化后丢弃原迟到响应。图表与表格分别展示单位、已知子和和未知覆盖，保留空桶、明确零与数值溢出的区别。

本版本只交付效率账本中的持久用量趋势。IANA 时区、供应商账单、价格换算、CLI 内部请求、持久定时调度、漏跑处理、并发去重和独立调度会话继续实施，不能据此声明完整 F21 或 M0–M3 验收完成。
