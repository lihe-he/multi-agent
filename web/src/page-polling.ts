type PagePause = 'page';
type PollOptions = { onError?: (error: unknown) => void; onPause?: (reason: PagePause) => void; onResume?: () => void };

export function startPagePolling(read: (signal: AbortSignal) => Promise<void | boolean>, delay: number | (() => number), options: PollOptions = {}) {
  let disposed = false, frozen = false, networkOffline = !navigator.onLine, halted = false;
  let lastActivity = Date.now();
  let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined;
  function stopRead() {
    const previous = controller; controller = undefined; previous?.abort();
    clearTimeout(timer); timer = undefined;
  }
  async function poll() {
    if (disposed || frozen || halted || controller) return;
    timer = undefined; lastActivity = Date.now(); const attempt = new AbortController(); controller = attempt;
    let keepPolling: void | boolean = true;
    try { keepPolling = await read(attempt.signal); }
    catch (error) { if (controller === attempt && !attempt.signal.aborted) options.onError?.(error); }
    if (disposed || controller !== attempt || attempt.signal.aborted) return;
    controller = undefined;
    if (keepPolling === false) { halted = true; return; }
    timer = setTimeout(() => void poll(), typeof delay === 'number' ? delay : delay());
  }
  function resume() {
    if (disposed || frozen || halted) return;
    options.onResume?.(); void poll();
  }
  function hide() { frozen = true; stopRead(); options.onPause?.('page'); }
  function show(event: PageTransitionEvent) { if (event.persisted && frozen) { frozen = false; resume(); } }
  function disconnect() { if (!networkOffline) { networkOffline = true; stopRead(); resume(); } }
  function reconnect() { if (networkOffline) { networkOffline = false; stopRead(); resume(); } }
  function foreground() {
    if (!disposed && !frozen && !halted && document.visibilityState === 'visible' && Date.now() - lastActivity >= 20000) { stopRead(); resume(); }
  }
  window.addEventListener('pagehide', hide); window.addEventListener('pageshow', show);
  window.addEventListener('offline', disconnect); window.addEventListener('online', reconnect);
  document.addEventListener('visibilitychange', foreground);
  void poll();
  return () => {
    disposed = true; stopRead();
    window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', show);
    window.removeEventListener('offline', disconnect); window.removeEventListener('online', reconnect);
    document.removeEventListener('visibilitychange', foreground);
  };
}
