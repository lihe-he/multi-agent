import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent, PointerEvent, ReactNode } from 'react';
import { PanelLeftClose, PanelLeftOpen, RotateCcw, TriangleAlert } from 'lucide-react';

const storageKey = 'workbench-layout';
const minimumWidth = 200;
const maximumWidth = 420;
const minimumContentWidth = 720;
const compactBreakpoint = 980;
interface Preference { version: 1; sidebarWidth: number | null; sidebarCollapsed: boolean; }
const defaults: Preference = { version: 1, sidebarWidth: null, sidebarCollapsed: false };

function readPreference(): { preference: Preference; storageAvailable: boolean } {
  let raw: string | null;
  try { raw = window.localStorage.getItem(storageKey); }
  catch { return { preference: defaults, storageAvailable: false }; }
  try {
    const value = raw === null ? null : JSON.parse(raw);
    if (value?.version === 1 && typeof value.sidebarCollapsed === 'boolean'
      && (value.sidebarWidth === null || (typeof value.sidebarWidth === 'number' && Number.isFinite(value.sidebarWidth)
        && value.sidebarWidth >= minimumWidth && value.sidebarWidth <= maximumWidth)))
      return { preference: { version: 1, sidebarWidth: value.sidebarWidth, sidebarCollapsed: value.sidebarCollapsed }, storageAvailable: true };
  } catch { /* An invalid layout uses the responsive defaults. */ }
  return { preference: defaults, storageAvailable: true };
}

function widthLimit(viewport: number) {
  return Math.max(minimumWidth, Math.min(maximumWidth, viewport - minimumContentWidth));
}

function visibleWidth(preference: Preference, viewport: number, available: number) {
  if (viewport <= 450) return 58;
  if (preference.sidebarCollapsed || viewport <= compactBreakpoint) return 75;
  const intended = preference.sidebarWidth ?? (viewport <= 1200 ? 215 : 244);
  return Math.min(widthLimit(available), Math.max(minimumWidth, intended));
}

interface LayoutContextValue {
  preference: Preference;
  width: number;
  storageAvailable: boolean;
  toggle: () => void;
  reset: () => void;
}
const LayoutContext = createContext<LayoutContextValue | null>(null);
function useLayout() {
  const context = useContext(LayoutContext);
  if (!context) throw new Error('工作台布局上下文不可用');
  return context;
}

interface Drag {
  pointerId: number;
  startX: number;
  startWidth: number;
  limit: number;
  width: number;
  moved: boolean;
  bodyStyles: { property: string; value: string; priority: string }[];
}

export function SidebarLayout({ children }: { children: ReactNode }) {
  const [initial] = useState(readPreference);
  const [preference, setPreference] = useState(initial.preference);
  const [storageAvailable, setStorageAvailable] = useState(initial.storageAvailable);
  const [viewport, setViewport] = useState(() => window.innerWidth);
  const [available, setAvailable] = useState(() => document.documentElement.clientWidth || window.innerWidth);
  const root = useRef<HTMLDivElement>(null), separator = useRef<HTMLDivElement>(null);
  const preferenceRef = useRef(preference), unsaved = useRef(false), pendingStorage = useRef(false);
  const drag = useRef<Drag | null>(null), frame = useRef<number | null>(null);
  const availableRef = useRef(available);
  const currentAvailableWidth = useCallback(() => Math.floor(root.current?.getBoundingClientRect().width
    || document.documentElement.clientWidth || window.innerWidth), []);

  const updateAria = useCallback((width: number) => {
    const handle = separator.current;
    if (!handle) return;
    handle.setAttribute('aria-valuenow', String(width));
    handle.setAttribute('aria-valuetext', preferenceRef.current.sidebarCollapsed
      ? '侧栏已收起，按 Enter 展开' : `侧栏宽度 ${width} 像素`);
  }, []);

  const synchronize = useCallback(() => {
    const current = readPreference();
    preferenceRef.current = current.preference;
    setPreference(current.preference);
    setStorageAvailable(current.storageAvailable);
    unsaved.current = false;
  }, []);

  const persist = useCallback((next: Preference) => {
    preferenceRef.current = next;
    setPreference(next);
    pendingStorage.current = false;
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(next));
      unsaved.current = false;
      setStorageAvailable(true);
    } catch {
      unsaved.current = true;
      setStorageAvailable(false);
    }
  }, []);

  const finishDrag = useCallback((commit: boolean, mounted = true) => {
    const session = drag.current;
    if (!session) return;
    // Clear ownership before releasing capture: lostpointercapture can run during cleanup.
    drag.current = null;
    if (frame.current !== null) { cancelAnimationFrame(frame.current); frame.current = null; }
    for (const style of session.bodyStyles) {
      if (style.value) document.body.style.setProperty(style.property, style.value, style.priority);
      else document.body.style.removeProperty(style.property);
    }
    const handle = separator.current;
    try { if (handle?.hasPointerCapture(session.pointerId)) handle.releasePointerCapture(session.pointerId); }
    catch { /* Capture may already be gone after cancellation or removal. */ }
    root.current?.style.removeProperty('--sidebar-drag-size');
    root.current?.removeAttribute('data-sidebar-dragging');
    const changed = commit && session.moved && session.width !== session.startWidth;
    if (mounted && changed) persist({ ...preferenceRef.current, sidebarWidth: session.width });
    else if (mounted && pendingStorage.current && !unsaved.current) synchronize();
    pendingStorage.current = false;
    updateAria(visibleWidth(preferenceRef.current, window.innerWidth, currentAvailableWidth()));
  }, [persist, synchronize, updateAria, currentAvailableWidth]);

  useLayoutEffect(() => {
    const container = root.current;
    if (!container) return;
    function measure() {
      const width = currentAvailableWidth();
      if (width <= 0 || width === availableRef.current) return;
      availableRef.current = width;
      finishDrag(false);
      container!.style.setProperty('--sidebar-available-size', `${width}px`);
      setAvailable(width);
    }
    // The container excludes a native scrollbar or reserved edge; 100vw can include it.
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(container);
    return () => observer.disconnect();
  }, [currentAvailableWidth, finishDrag]);

  const toggle = useCallback(() => {
    finishDrag(false);
    persist({ ...preferenceRef.current, sidebarCollapsed: !preferenceRef.current.sidebarCollapsed });
  }, [finishDrag, persist]);
  const reset = useCallback(() => { finishDrag(false); persist({ ...defaults }); }, [finishDrag, persist]);

  useEffect(() => {
    function resize() {
      finishDrag(false);
      if (window.innerWidth <= compactBreakpoint && document.activeElement === separator.current)
        root.current?.querySelector<HTMLButtonElement>('[data-sidebar-toggle]')?.focus({ preventScroll: true });
      setViewport(window.innerWidth);
    }
    function cancel() { finishDrag(false); }
    function escape(event: globalThis.KeyboardEvent) {
      if (event.key === 'Escape' && drag.current) { event.preventDefault(); finishDrag(false); }
    }
    function visibility() { if (document.hidden) finishDrag(false); }
    function storage(event: StorageEvent) {
      if (event.key !== null && event.key !== storageKey) return;
      try { if (event.storageArea !== window.localStorage) return; } catch { return; }
      if (unsaved.current) return;
      if (drag.current) { pendingStorage.current = true; return; }
      // Queued events can describe an older write; read the latest record instead.
      synchronize();
    }
    function restore() { if (!unsaved.current && !drag.current) synchronize(); }
    window.addEventListener('resize', resize);
    window.addEventListener('blur', cancel);
    window.addEventListener('keydown', escape);
    window.addEventListener('storage', storage);
    window.addEventListener('pageshow', restore);
    document.addEventListener('visibilitychange', visibility);
    return () => {
      window.removeEventListener('resize', resize);
      window.removeEventListener('blur', cancel);
      window.removeEventListener('keydown', escape);
      window.removeEventListener('storage', storage);
      window.removeEventListener('pageshow', restore);
      document.removeEventListener('visibilitychange', visibility);
      finishDrag(false, false);
    };
  }, [finishDrag, synchronize]);

  function pointerDown(event: PointerEvent<HTMLDivElement>) {
    if (drag.current || preferenceRef.current.sidebarCollapsed || window.innerWidth <= compactBreakpoint
      || !event.isPrimary || event.button !== 0 || event.ctrlKey || event.detail > 1) return;
    const panel = root.current?.querySelector('.sidebar');
    if (!panel) return;
    try { event.currentTarget.setPointerCapture(event.pointerId); } catch { return; }
    event.preventDefault();
    event.currentTarget.focus({ preventScroll: true });
    const width = Math.round(panel.getBoundingClientRect().width);
    drag.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: width, width,
      limit: widthLimit(currentAvailableWidth()), moved: false,
      bodyStyles: ['cursor', 'user-select'].map(property => ({ property,
        value: document.body.style.getPropertyValue(property), priority: document.body.style.getPropertyPriority(property) })) };
    document.body.style.setProperty('cursor', 'col-resize', 'important');
    document.body.style.setProperty('user-select', 'none', 'important');
  }

  function pointerMove(event: PointerEvent<HTMLDivElement>) {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    if (event.pointerType === 'mouse' && event.buttons === 0) { finishDrag(false); return; }
    const delta = event.clientX - session.startX;
    if (!session.moved && Math.abs(delta) < 2) return;
    session.moved = true;
    session.width = Math.round(Math.max(minimumWidth, Math.min(session.limit, session.startWidth + delta)));
    if (frame.current !== null) return;
    // Preview only the layout and separator; the task/log tree keeps its React state.
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      if (drag.current !== session) return;
      root.current?.style.setProperty('--sidebar-drag-size', `${session.width}px`);
      root.current?.setAttribute('data-sidebar-dragging', 'true');
      updateAria(session.width);
    });
  }

  function pointerUp(event: PointerEvent<HTMLDivElement>) {
    const session = drag.current;
    if (!session || session.pointerId !== event.pointerId) return;
    if (session.moved) session.width = Math.round(Math.max(minimumWidth, Math.min(session.limit,
      session.startWidth + event.clientX - session.startX)));
    finishDrag(true);
  }

  function pointerCancel(event: PointerEvent<HTMLDivElement>) {
    if (drag.current?.pointerId === event.pointerId) finishDrag(false);
  }

  function keyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.altKey || event.ctrlKey || event.metaKey || drag.current) return;
    if (event.key === 'Enter') { event.preventDefault(); toggle(); return; }
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const current = preferenceRef.current;
    const limit = widthLimit(currentAvailableWidth());
    const width = visibleWidth({ ...current, sidebarCollapsed: false }, window.innerWidth, currentAvailableWidth());
    const step = event.shiftKey ? 50 : 10;
    const next = event.key === 'Home' ? minimumWidth : event.key === 'End' ? limit
      : current.sidebarCollapsed ? width : width + (event.key === 'ArrowRight' ? step : -step);
    persist({ ...current, sidebarWidth: Math.max(minimumWidth, Math.min(limit, next)), sidebarCollapsed: false });
  }

  const width = visibleWidth(preference, viewport, available);
  const context = useMemo(() => ({ preference, width, storageAvailable, toggle, reset }), [preference, width, storageAvailable, toggle, reset]);
  const style = { '--sidebar-intended-size': preference.sidebarWidth === null ? undefined : `${preference.sidebarWidth}px`,
    '--sidebar-available-size': `${available}px` } as CSSProperties;
  return <LayoutContext.Provider value={context}><div ref={root} className={`app-shell ${preference.sidebarCollapsed ? 'sidebar-collapsed' : ''}`} style={style}>
    {children}
    <div ref={separator} className="sidebar-resizer" role="separator" tabIndex={0} aria-label="调整侧栏宽度"
      aria-orientation="vertical" aria-controls="workbench-sidebar" aria-describedby="sidebar-resize-help"
      aria-valuemin={preference.sidebarCollapsed ? width : minimumWidth} aria-valuemax={widthLimit(available)} aria-valuenow={width}
      aria-valuetext={preference.sidebarCollapsed ? '侧栏已收起，按 Enter 展开' : `侧栏宽度 ${width} 像素`}
      title="拖动调整侧栏，或使用方向键；Enter 收起或展开"
      onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp}
      onPointerCancel={pointerCancel} onLostPointerCapture={pointerCancel} onKeyDown={keyDown}>
      <span className="sidebar-resize-grip" aria-hidden="true" />
    </div>
    <span id="sidebar-resize-help" className="layout-keyboard-help">左右方向键调整宽度，Shift 加速，Home 和 End 到达边界，Enter 收起或展开，Escape 取消拖动。</span>
    {!storageAvailable && <div className="layout-save-warning" role="status"><TriangleAlert size={15} aria-hidden="true" /><span>无法保存布局，本次窗口仍可调整。</span></div>}
  </div></LayoutContext.Provider>;
}

export function SidebarToggle() {
  const { preference, toggle } = useLayout();
  return <button className="icon-button" data-sidebar-toggle aria-controls="workbench-sidebar" aria-expanded={!preference.sidebarCollapsed}
    aria-label={preference.sidebarCollapsed ? '展开侧栏' : '收起侧栏'} onClick={toggle}>
    {preference.sidebarCollapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}
  </button>;
}

export function LayoutPreferences() {
  const { preference, width, storageAvailable, reset } = useLayout();
  return <section className="card layout-preferences"><div><h3>工作台布局</h3>
    <p>拖动侧栏边缘调整宽度，也可聚焦分隔器后使用方向键。当前{preference.sidebarCollapsed ? '已收起' : `宽度 ${width} 像素`}。</p>
    <p>{storageAvailable ? '调整后的布局会保存在此客户端，重新打开后继续使用。' : '无法保存布局，本次窗口仍可调整。'}</p>
  </div><button className="button secondary small" onClick={reset}><RotateCcw size={15} />恢复默认布局</button></section>;
}
