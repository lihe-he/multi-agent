export interface Project {
  id: string; name: string; path: string; branch?: string; head?: string;
  dirty?: boolean; fileCount?: number; modules?: string[]; files?: string[]; indexedAt?: string;
}
export interface Route {
  id: string; name: string; engine: 'CODEX' | 'CLAUDE' | 'API'; model?: string;
  provider?: string; baseUrl?: string; executable?: string; enabled?: boolean;
  hasKey?: boolean; hasApiKey?: boolean; available?: boolean; health?: string;
}
export interface Memory {
  id: string; projectId: string; kind: string; content: string; source?: string;
  status?: string; version?: number; createdAt?: string;
}
export interface Profile {
  id: string; name: string; role: string; instructions: string; capabilities?: string[]; version?: number;
}
export interface Stage {
  id: string; title: string; role?: string; dependencies?: string[]; state?: string;
}
export interface Evidence {
  id: string; kind?: string; stage?: string; version?: string; command?: string;
  exitCode?: number; output?: string; createdAt?: string; status?: string;
}
export interface Task {
  id: string; projectId: string; title: string; goal: string; mode: string; modeReason?: string;
  state: string; outcome?: string; createdAt?: string; updatedAt?: string; routeId?: string;
  routeSnapshot?: Route;
  contract?: { scope?: string[]; acceptance?: string[]; commands?: string[] };
  budget?: { maxCalls?: number; reserveCalls?: number; usedCalls?: number; committedCalls?: number };
  mcpAuthorization?: { version?: number; bindings?: { serverId: string; serverName?: string; name: string; actionKey: string; fixedArguments: Record<string, unknown>; timeoutMs: number }[] };
  mcpBudget?: { maxCalls?: number; maxDurationMs?: number; usedCalls?: number; committedDurationMs?: number };
  locks?: Record<string, string>; baseline?: string; workspace?: string; version?: string;
  plan?: Stage[] | { stages?: Stage[] }; evidence?: Evidence[];
  review?: { status?: string; output?: string; version?: string };
  context?: { id?: string; kind?: string; content?: string; source?: string; version?: string | number }[];
  issues?: (string | { message?: string; title?: string; severity?: string; [key: string]: unknown })[];
  error?: string;
}
export interface WorkbenchState {
  projects: Project[]; tasks: Task[]; routes: Route[]; memories: Memory[]; profiles: Profile[];
  system: { version?: string; modelCapacity?: number; activeModels?: number; storageHealthy?: boolean; capabilities?: (string | { name?: string; available?: boolean; message?: string })[] };
}
export interface RunEvent { id: number; taskId?: string; type: string; message: string; createdAt?: string }
export interface Connection { baseUrl: string; token: string }
declare global {
  interface Window {
    workbench?: {
      getConnection: () => Promise<Connection>;
      selectDirectory?: () => Promise<string | null>;
      openPath?: (path: string) => Promise<void>;
    };
  }
}
