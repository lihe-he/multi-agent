import { Children, cloneElement, isValidElement, useEffect, useId, useRef } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { ArrowUpRight, Check, Circle, LoaderCircle, X } from 'lucide-react';

const labels: Record<string, string> = {
  DRAFT: '待确认计划', QUEUED: '排队中', DISPATCHING: '派发中', RUNNING: '执行中', PAUSING: '正在暂停', PAUSED: '已暂停',
  WAITING_INPUT: '需要你处理', WAITING_RESOURCE: '等待资源', UNKNOWN: '结果待核对',
  CANCELLING: '正在取消', CANCELLED: '已取消', FAILED: '执行失败',
  AWAITING_ACCEPTANCE: '待验收', COMPLETED: '已完成', SUCCEEDED: '已通过', PASSED: '已通过',
  PENDING: '尚未执行', ACTIVE: '有效', DISABLED: '已停用', NEEDS_REVIEW: '待核验',
  APPROVED: '审查通过', REJECTED: '需要修复', BLOCKED: '受阻', VERIFIED: '已验证',
  WAITING_TOOL: '等待工具', BUDGET_EXHAUSTED: '预算不足', NOT_RUN: '尚未执行', CHANGES_REQUESTED: '需要修复', UNVERIFIED: '未验证',
  WAITING_CHILDREN: '等待副任务', WAITING_DEPENDENCY: '等待前置副任务',
};
export const modeLabel = (mode: string) => ({ AUTO: '自动选择', LEAD: '主副协作', ROUNDTABLE: '圆桌讨论', PIPELINE: '研发流水线' }[mode] || mode);
export const statusLabel = (state?: string) => state ? labels[state] || state : '尚未执行';
export const kindLabel = (kind: string) => ({ FACT: '事实', DECISION: '决策', EXPERIENCE: '经验', RULE: '项目规则', BASELINE: '基线检查', VERIFICATION: '结果验证', REVIEW: '独立审查', IMPLEMENTATION: '实现记录', AGENT: '阶段执行', SUBAGENT: '副 Agent 执行' }[kind] || kind);
export const formatTime = (date?: string) => {
  if (!date) return '尚无记录';
  const value = new Date(date);
  return Number.isNaN(value.getTime()) ? date : value.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
};
export const shortHash = (value?: unknown) => value !== undefined && value !== null && value !== '' ? String(value).slice(0, 10) : '待生成';
export const lines = (text: string) => text.split('\n').map(s => s.trim()).filter(Boolean);

export function Badge({ state, children }: { state?: string; children?: ReactNode }) {
  const tone = ['COMPLETED', 'SUCCEEDED', 'PASSED', 'APPROVED', 'ACTIVE', 'VERIFIED'].includes(state || '') ? 'success'
    : ['FAILED', 'REJECTED', 'UNKNOWN', 'CHANGES_REQUESTED'].includes(state || '') ? 'danger'
    : ['WAITING_INPUT', 'WAITING_RESOURCE', 'WAITING_CHILDREN', 'WAITING_DEPENDENCY', 'AWAITING_ACCEPTANCE', 'NEEDS_REVIEW', 'BLOCKED', 'DRAFT'].includes(state || '') ? 'warning'
    : ['RUNNING', 'DISPATCHING', 'QUEUED', 'PAUSING', 'CANCELLING'].includes(state || '') ? 'live' : 'muted';
  return <span className={`badge ${tone}`}><span className="badge-dot" />{children || statusLabel(state)}</span>;
}

export function Empty({ icon, title, description, action }: { icon: ReactNode; title: string; description: string; action?: ReactNode }) {
  return <div className="empty"><div className="empty-icon">{icon}</div><h3>{title}</h3><p>{description}</p>{action}</div>;
}

export function PageTitle({ eyebrow, title, description, children }: { eyebrow: string; title: string; description?: string; children?: ReactNode }) {
  return <div className="page-title"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1>{description && <p>{description}</p>}</div><div className="title-actions">{children}</div></div>;
}

export function Modal({ title, description, children, onClose, wide = false }: { title: string; description?: string; children: ReactNode; onClose: () => void; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const oldFocus = document.activeElement as HTMLElement;
    ref.current?.querySelector<HTMLElement>('input,select,textarea,button')?.focus();
    function key(event: KeyboardEvent) {
      if (event.key === 'Escape') closeRef.current();
      if (event.key === 'Tab') {
        const focus = ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),[tabindex="0"]');
        if (!focus?.length) return;
        const first = focus[0], last = focus[focus.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    }
    window.addEventListener('keydown', key);
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', key); document.body.style.overflow = ''; oldFocus?.focus(); };
  }, []);
  return <div className="modal-overlay" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}><div ref={ref} className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}><header><div><h2>{title}</h2>{description && <p>{description}</p>}</div><button className="icon-button" aria-label="关闭对话框" onClick={onClose}><X size={19} /></button></header>{children}</div></div>;
}

export function Field({ label, hint, children, full = false }: { label: string; hint?: string; children: ReactNode; full?: boolean }) {
  const id = useId();
  function associate(child: ReactNode): ReactNode {
    if (!isValidElement(child)) return child;
    const element = child as ReactElement<{ children?: ReactNode; 'aria-labelledby'?: string; 'aria-describedby'?: string }>;
    if (['input', 'select', 'textarea'].includes(String(element.type))) {
      return cloneElement(element, { 'aria-labelledby': `${id}-label`, 'aria-describedby': hint ? `${id}-hint` : undefined });
    }
    return element.props.children ? cloneElement(element, {}, Children.map(element.props.children, associate)) : element;
  }
  return <label className={`field ${full ? 'full' : ''}`}><span id={`${id}-label`}>{label}</span>{Children.map(children, associate)}{hint && <small id={`${id}-hint`}>{hint}</small>}</label>;
}

export function SaveButton({ busy, children = '保存' }: { busy: boolean; children?: ReactNode }) {
  return <button type="submit" className="button primary" disabled={busy}>{busy ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />}{busy ? '正在保存…' : children}</button>;
}

export function SectionHeading({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return <div className="section-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>;
}

export function LinkButton({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return <button className="text-button" onClick={onClick}>{children}<ArrowUpRight size={15} /></button>;
}

export function Checklist({ items }: { items: string[] }) {
  return <ul className="checklist">{items.map((item, i) => <li key={i}><Circle size={13} /><span>{item}</span></li>)}</ul>;
}
