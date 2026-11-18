type PagePause = 'page' | 'offline';
type PollOptions = { onError?: (error: unknown) => void; onPause?: (reason: PagePause) => void; onResume?: () => void };

export function startPagePolling(read: (signal: AbortSignal) => Promise<void | boolean>, delay: number, options: PollOptions = {}) {
  let disposed = false, frozen = false, offline = !navigator.onLine, halted = false;
  let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined;
  function stopRead() {
    const previous = controller; controller = undefined; previous?.abort();
    clearTimeout(timer); timer = undefined;
  }
  async function poll() {
    if (disposed || frozen || offline || halted || controller) return;
    timer = undefined; const attempt = new AbortController(); controller = attempt;
    let keepPolling: void | boolean = true;
    try { keepPolling = await read(attempt.signal); }
    catch (error) { if (controller === attempt && !attempt.signal.aborted) options.onError?.(error); }
    if (disposed || controller !== attempt || attempt.signal.aborted) return;
    controller = undefined;
    if (keepPolling === false) { halted = true; return; }
    timer = setTimeout(() => void poll(), delay);
  }
  function resume() {
    if (disposed || frozen || offline || halted) return;
    options.onResume?.(); void poll();
  }
  function hide() { frozen = true; stopRead(); options.onPause?.('page'); }
  function show(event: PageTransitionEvent) { if (event.persisted && frozen) { frozen = false; resume(); } }
  function disconnect() { if (!offline) { offline = true; stopRead(); if (!halted) options.onPause?.('offline'); } }
  function reconnect() { if (offline) { offline = false; resume(); } }
  window.addEventListener('pagehide', hide); window.addEventListener('pageshow', show);
  window.addEventListener('offline', disconnect); window.addEventListener('online', reconnect);
  if (offline) options.onPause?.('offline'); else void poll();
  return () => {
    disposed = true; stopRead();
    window.removeEventListener('pagehide', hide); window.removeEventListener('pageshow', show);
    window.removeEventListener('offline', disconnect); window.removeEventListener('online', reconnect);
  };
}
