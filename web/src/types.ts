export interface Project {
  id: string; name: string; path: string; branch?: string; head?: string;
  dirty?: boolean; fileCount?: number; modules?: string[]; files?: string[]; indexedAt?: string;
}
export interface Route {
  id: string; name: string; engine: 'CODEX' | 'CLAUDE' | 'API'; model?: string;
  provider?: string; baseUrl?: string; executable?: string; enabled?: boolean;
  hasKey?: boolean; hasApiKey?: boolean; available?: boolean; health?: string; version?: number;
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
  id: string; projectId: string; sessionId?: string; title: string; goal: string; mode: string; modeReason?: string;
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
  teamId?: string; collaboration?: { enabled: boolean; maxActive: number; maxDepth: number; maxTotal: number; spawned: number; definitions?: AgentDefinition[] };
}
export interface WorkbenchState {
  projects: Project[]; tasks: Task[]; routes: Route[]; memories: Memory[]; profiles: Profile[];
  agentDefinitions?: AgentDefinition[];
  system: { version?: string; modelCapacity?: number; activeModels?: number; storageHealthy?: boolean; capabilities?: (string | { name?: string; available?: boolean; message?: string })[] };
}
export interface RunEvent { id: number; taskId?: string; type: string; message: string; createdAt?: string }
export interface AgentRun {
  id: string; taskId: string; sessionId: string; stage: string; kind: string; title?: string; role: string;
  attempt: number; state: string; createdAt: string; startedAt?: string; endedAt?: string; elapsedMs?: number; queueMs?: number;
  routeName?: string; engine?: string; model?: string; readOnly: boolean; calls: number; callUnit: string;
  evidenceId?: string; reason?: string; inputVersion?: string; resultVersion?: string;
  parentRunId?: string; subtaskId?: string; teamId?: string; depth?: number; permissions?: AgentPermission;
}
export interface AgentPermission { capabilities: string[]; readScopeLayers: string[][]; writeScopeLayers: string[][] }
export interface AgentDefinition {
  id: string; version: number; name: string; role: string; instructions: string; capabilities: string[];
  projectId?: string; routeId?: string; scope: string[]; maxCalls: number; enabled: boolean;
  routeSnapshot?: Route;
}
export interface Subtask {
  id: string; taskId: string; agentRunId: string; parentRunId: string; parentSubtaskId?: string; title: string; goal: string; role: string;
  definitionId: string; definitionVersion: number; depth: number; dependsOn: string[]; state: string; permissions: AgentPermission;
  createdAt: string; endedAt?: string; evidenceId?: string; reason?: string; resultVersion?: string; integrationState: string;
  routeId: string; model?: string; requestId: string; toolCallId: string;
  integrations?: { operationId: string; agentRunId: string; resultVersion: string; createdAt: string }[];
}
export interface TeamMessage { id: string; sequence: number; content: string; sender: string; recipient: string; requestId?: string; toolCallId?: string; createdAt: string }
export interface ModelRequest {
  id: string; taskId: string; agentRunId: string; stage: string; sequence: number; attempt: number;
  engine: string; model?: string; state: string; unit: string; createdAt: string; endedAt?: string; elapsedMs?: number;
  httpStatus?: number; providerRequestId?: string; responseId?: string; responseModel?: string; finishReason?: string;
  inputBytes?: number; inputDigest?: string; messageCount?: number; toolCount?: number;
  inputPreview?: string; inputTruncated?: boolean;
  usage?: { inputTokens?: number | string; outputTokens?: number | string; cachedInputTokens?: number | string; reasoningTokens?: number | string };
  outputPreview?: string; outputTruncated?: boolean; errorType?: string; reason?: string; remoteOutcome?: string;
}
export interface Connection { baseUrl: string; token: string }
export interface Session { id: string; projectId: string; title: string; version: number; isOpen: boolean; createdAt: string; updatedAt: string }
export interface SessionMessage { id: string; sessionId: string; projectId: string; sequence: number; role: string; kind: 'NOTE' | 'TASK_GOAL'; content: string; taskId?: string; createdAt: string }
export interface SessionSearchResult { sessionId: string; sessionTitle: string; messageId: string; sequence: number; taskId?: string; content: string; matchStart: number; matchEnd: number; createdAt: string }
declare global {
  interface Window {
    workbench?: {
      getConnection: () => Promise<Connection>;
      selectDirectory?: () => Promise<string | null>;
      openPath?: (path: string) => Promise<void>;
    };
  }
}
