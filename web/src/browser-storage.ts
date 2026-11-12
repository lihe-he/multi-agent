type StorageKind = 'localStorage' | 'sessionStorage';

export function readBrowserValue(kind: StorageKind, key: string) {
  try { return window[kind].getItem(key); }
  catch { return null; }
}

export function writeBrowserValue(kind: StorageKind, key: string, value: string) {
  try { window[kind].setItem(key, value); return true; }
  catch { return false; }
}
