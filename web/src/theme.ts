import { useSyncExternalStore } from 'react';

export type ThemeId = 'pure-white' | 'paper-ink' | 'classic-warm' | 'celadon' | 'ink-night' | 'ink-night-blue';
export type ThemeChoice = ThemeId | 'system';
export type ColorScheme = 'light' | 'dark';
export interface ThemeDefinition {
  readonly id: ThemeId;
  readonly name: string;
  readonly scheme: ColorScheme;
  readonly canvas: string;
  readonly description: string;
}
interface ThemeSnapshot {
  readonly choice: ThemeChoice;
  readonly resolved: ThemeId;
  readonly scheme: ColorScheme;
  readonly systemScheme: ColorScheme;
  readonly storageAvailable: boolean;
}
interface ThemeStore {
  readonly storageKey: string;
  readonly themes: readonly ThemeDefinition[];
  getSnapshot: () => ThemeSnapshot;
  subscribe: (listener: () => void) => () => void;
  setChoice: (choice: ThemeChoice) => void;
}
declare global {
  interface Window { readonly __workbenchTheme: ThemeStore; }
}

// The blocking head script applies this same store before the first app render.
export const themeStore = window.__workbenchTheme;
export function useTheme() {
  return useSyncExternalStore(themeStore.subscribe, themeStore.getSnapshot);
}
