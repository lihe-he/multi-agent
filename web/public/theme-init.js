(function initializeTheme() {
  'use strict';

  if (window.__workbenchTheme) return;
  const storageKey = 'workbench-theme';
  const themes = Object.freeze([
    { id: 'pure-white', name: '纯白', scheme: 'light', canvas: '#f5f7fb', description: '明净白色与清晰蓝色，轻快直接。' },
    { id: 'paper-ink', name: '纸墨', scheme: 'light', canvas: '#f4f2eb', description: '纸张底色与墨色文字，沉静耐看。' },
    { id: 'classic-warm', name: '经典暖色', scheme: 'light', canvas: '#f7f2e7', description: '温暖米色与木色侧栏，柔和熟悉。' },
    { id: 'celadon', name: '青瓷', scheme: 'light', canvas: '#f5f7f6', description: '青绿点缀与深绿侧栏，延续工作台配色。' },
    { id: 'ink-night', name: '墨夜', scheme: 'dark', canvas: '#141e19', description: '深墨底色与柔和青绿，适合夜间工作。' },
    { id: 'ink-night-blue', name: '墨夜蓝', scheme: 'dark', canvas: '#172238', description: '深蓝底色与冷蓝点缀，层次清楚。' },
  ].map(theme => Object.freeze(theme)));
  const byId = new Map(themes.map(theme => [theme.id, theme]));
  const listeners = new Set();
  let storage, media, snapshot, choice = 'system', storageAvailable = true, unsavedChoice = false;

  function validChoice(value) {
    return value === 'system' || byId.has(value) ? value : 'system';
  }

  function readChoice() {
    try {
      storage = window.localStorage;
      const value = validChoice(storage.getItem(storageKey));
      storageAvailable = true;
      return value;
    } catch {
      storageAvailable = false;
      return null;
    }
  }

  function applyTheme() {
    const systemScheme = media?.matches ? 'dark' : 'light';
    const resolved = choice === 'system' ? (systemScheme === 'dark' ? 'ink-night' : 'celadon') : choice;
    const theme = byId.get(resolved), root = document.documentElement;
    root.dataset.theme = resolved;
    root.dataset.themeChoice = choice;
    root.style.colorScheme = theme.scheme;
    root.style.backgroundColor = `var(--canvas, ${theme.canvas})`;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme.canvas);
    const next = Object.freeze({ choice, resolved, scheme: theme.scheme, systemScheme, storageAvailable });
    if (snapshot && Object.keys(next).every(key => snapshot[key] === next[key])) return;
    snapshot = next;
    for (const listener of [...listeners]) listener();
  }

  choice = readChoice() ?? choice;
  try { media = window.matchMedia('(prefers-color-scheme: dark)'); } catch { /* A missing media API uses the light default. */ }
  applyTheme();

  const store = Object.freeze({
    storageKey,
    themes,
    getSnapshot: () => snapshot,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    setChoice(value) {
      if (value !== 'system' && !byId.has(value)) return;
      choice = value;
      try {
        storage = window.localStorage;
        storage.setItem(storageKey, choice);
        storageAvailable = true;
        unsavedChoice = false;
      } catch { storageAvailable = false; unsavedChoice = true; }
      applyTheme();
    },
  });
  Object.defineProperty(window, '__workbenchTheme', { value: store });
  media?.addEventListener('change', applyTheme);
  window.addEventListener('storage', event => {
    if (event.storageArea !== storage || (event.key !== storageKey && event.key !== null)) return;
    // Read the latest persisted choice so an older queued event cannot restore an obsolete theme.
    const persisted = readChoice();
    if (persisted !== null) { choice = persisted; unsavedChoice = false; }
    applyTheme();
  });
  window.addEventListener('pageshow', () => {
    // A failed write must not let page restoration replace the in-memory choice with older storage.
    if (!unsavedChoice) choice = readChoice() ?? choice;
    applyTheme();
  });
})();
