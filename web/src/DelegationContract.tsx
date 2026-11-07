import type { Task } from './types';
import { SectionHeading } from './ui';

export function DelegationContract({ task }: { task: Task }) {
  const policy = task.collaboration;
  if (!policy?.enabled) return null;
  return <section className="card delegation-contract" aria-label="固定委派约定">
    <SectionHeading title="动态委派约定" subtitle="执行前固定定义版本、数据发送路由和限制；实际副任务权限由根、父阶段与委派范围逐层相交。" />
    <div className="delegation-contract-body">
      <p>活动或排队上限 {policy.maxActive}（含父执行者） · 最大深度 {policy.maxDepth} · 根累计委派上限 {policy.maxTotal}</p>
      {policy.definitions?.map(definition => <article key={definition.id}>
        <h3>{definition.name} · v{definition.version}</h3>
        <p>{definition.role} · {definition.routeSnapshot?.name || task.routeSnapshot?.name || '根任务路由'} · {definition.routeSnapshot?.model || task.routeSnapshot?.model || '已固定模型'}</p>
        {definition.routeSnapshot?.baseUrl && <p>数据发送地址：<code>{definition.routeSnapshot.baseUrl}</code></p>}
        <p>定义能力需求：{definition.capabilities.join('，')} · 单次调用上限 {definition.maxCalls}</p>
        <p>定义文件范围：<code>{definition.scope.join('，')}</code></p>
        <details><summary>固定职责与交付要求</summary><pre>{definition.instructions}</pre></details>
      </article>)}
      {!policy.definitions?.length && <p className="muted-text">正在读取任务保存的固定定义…</p>}
    </div>
  </section>;
}
