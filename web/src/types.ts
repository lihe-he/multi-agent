export interface Project {
  id: string; name: string; path: string; branch?: string; head?: string;
  dirty?: boolean; fileCount?: number; modules?: string[]; files?: string[]; indexedAt?: string;
  repositoryBindingId?: string; executionNodeId?: string;
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
  workerId?: string; workerLeaseId?: string; workerEpoch?: number; executionNodeId?: string; repositoryBindingId?: string; bindingVersion?: number; observedStatus?: string;
  deployment?: string; executorPid?: number; corePid?: number; processStartedAt?: string;
  workspaceWriteLeaseId?: string;
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
  diffViewProtocolVersion?: number;
  plan?: Stage[] | { stages?: Stage[] }; evidence?: Evidence[];
  review?: { status?: string; output?: string; version?: string };
  context?: { id?: string; kind?: string; content?: string; source?: string; version?: string | number }[];
  issues?: (string | { message?: string; title?: string; severity?: string; [key: string]: unknown })[];
  error?: string;
  teamId?: string; collaboration?: { enabled: boolean; maxActive: number; maxDepth: number; maxTotal: number; spawned: number; definitions?: AgentDefinition[] };
  execution?: ExecutionSnapshot;
  workspaceSelection?: WorkspaceSelection;
  workspaceOwnership?: WorkspaceOwnership;
  workspacePreparationPresent?: boolean;
  workspaceSelectionHistory?: { selection: WorkspaceSelection; actor: string; createdAt: string }[];
  taskExecutionId?: string; dispatchActionId?: string; workspaceWriteLeaseId?: string;
  workspaceWrite?: WorkspaceWriteLease; workspaceWriteHistoryPresent?: boolean;
  taskExecution?: { id: string; attempt: number; state: string; createdAt: string; endedAt?: string; reconciled?: boolean };
  dispatch?: { id: string; state: string; dispatchCount: number; createdAt: string; claimedAt?: string; waitReason?: string };
}
export interface WorkbenchState {
  projects: Project[]; tasks: Task[]; routes: Route[]; memories: Memory[]; profiles: Profile[];
  agentDefinitions?: AgentDefinition[];
  executionNodes?: ExecutionNode[]; repositoryBindings?: RepositoryBinding[]; workers?: Worker[];
  processRecovery?: ProcessRecovery;
  system: { version?: string; modelCapacity?: number; activeModels?: number; storageHealthy?: boolean; capabilities?: (string | { name?: string; available?: boolean; message?: string })[] };
}
export interface RunEvent { id: number; taskId?: string; type: string; message: string; createdAt?: string }
export interface AgentRun {
  id: string; taskId: string; sessionId: string; stage: string; kind: string; title?: string; role: string;
  attempt: number; state: string; createdAt: string; startedAt?: string; endedAt?: string; elapsedMs?: number; queueMs?: number;
  routeName?: string; engine?: string; model?: string; readOnly: boolean; calls: number; callUnit: string;
  evidenceId?: string; reason?: string; inputVersion?: string; resultVersion?: string;
  parentRunId?: string; subtaskId?: string; teamId?: string; depth?: number; permissions?: AgentPermission;
  workerId?: string; workerLeaseId?: string; workerEpoch?: number; executionNodeId?: string; repositoryBindingId?: string; bindingVersion?: number;
  deployment?: string; executorPid?: number; corePid?: number; processStartedAt?: string;
  taskExecutionId?: string; dispatchActionId?: string; workspaceWriteLeaseId?: string;
}
export interface DispatchAction {
  id: string; taskId: string; taskExecutionId: string; attempt: number; state: string; version: number; dispatchCount: number;
  createdAt: string; claimedAt?: string; endedAt?: string; ownerCoreSessionId?: string; ownerCorePid?: number; ownerCoreStartedAt?: string;
  waitReason?: string; reason?: string; reconciliation?: { note: string; createdAt: string };
  workspaceWriteLeaseId?: string;
}
export interface TaskExecution {
  id: string; taskId: string; dispatchActionId: string; attempt: number; trigger: string; state: string;
  createdAt: string; startedAt?: string; endedAt?: string; elapsedMs?: number; reason?: string;
  taskStateAtEnd?: string; taskOutcomeAtEnd?: string; contractVersion: number; authorizationVersion: number;
  budgetAtEnqueue?: { usedCalls?: number }; budgetAtStart?: { usedCalls?: number }; budgetAtEnd?: { usedCalls?: number };
  reconciliation?: { note: string; createdAt: string }; dispatchAction: DispatchAction;
  workspaceWriteLeaseId?: string;
}
export interface WorkspaceWriteLease {
  id: string; sequence?: number; taskId: string; projectId: string; taskExecutionId: string; dispatchActionId: string; attempt: number;
  scopePath: string; workerId: string; executionNodeId: string; workerEpoch: number; executorPid: number; processStartedAt: string;
  ownerCoreSessionId: string; ownerCorePid: number; ownerCoreStartedAt: string; authorizationVersion: number; workspaceSelectionVersion: number;
  strategy: string; baseline: string; state: 'PREPARED' | 'HELD' | 'RELEASED' | 'UNKNOWN'; protectionReleased: boolean;
  createdAt: string; grantedAt?: string; releasedAt?: string; lostAt?: string; reason?: string;
  grant?: { lockPath: string; fileKey: string; grantedAt: string; lockValid: boolean };
  release?: { observedAt: string; receiptExecutorPid: number; receiptProcessStartedAt: string; nativeOwnerAbsent: boolean };
  reconciliation?: { actor: string; note: string; createdAt: string };
}
export interface ExecutionNode { id: string; name: string; version: number; protocolVersion: number; type: string; deployment: string; platform: string; architecture: string; enabled: boolean; epoch: number; state?: string; workerId?: string; lastSeenAt?: string }
export interface RepositoryBinding { id: string; projectId: string; executionNodeId: string; name: string; path: string; version: number; enabled: boolean; head?: string; branch?: string }
export interface Worker { id: string; executionNodeId: string; epoch: number; state: string; pid: number; corePid?: number; parentPid?: number; processStartedAt: string; startedAt: string; heartbeatAt: string; heartbeatSource?: string; lastHeartbeatSequence?: number; expiresAt: string; stopState?: string; deployment: string; platform: string; reason?: string }
export interface ExecutionSnapshot { version: number; protocolVersion: number; executionNodeId: string; nodeVersion: number; nodeName: string; repositoryBindingId: string; bindingVersion: number; bindingName: string; sourcePath: string; deployment: string }
export interface WorkspaceSource { head: string; branch: string; version: string; index: string; statusDigest: string; dirty: boolean }
export interface WorkspaceSelection { schemaVersion: number; version: number; strategy: 'CLONE' | 'WORKTREE'; ref: string; baseline: string; sourcePath: string; source: WorkspaceSource; includeChanges: boolean; createdAt: string }
export interface WorkspaceOwnership { schemaVersion: number; owner: string; taskId: string; strategy: string; sourcePath: string; gitDirectory: string; commonDirectory: string; lockReason?: string }
export interface WorkspaceOptions { schemaVersion: number; repositoryBindingId: string; bindingVersion: number; nodeVersion: number; sourcePath: string; source: WorkspaceSource; refs: { ref: string; head: string; name: string; kind: string }[]; worktrees: { path: string; head: string; detached?: boolean; locked?: string; branch?: string }[] }
export interface WorkspaceChoice { bindingId: string; bindingVersion: number; input: { strategy: 'CLONE' | 'WORKTREE'; ref: string; expectedHead: string; sourceVersion: string; sourceIndex: string }; includeChanges: boolean }
export interface ProcessObservation { id: string; pid: number; processStartedAt: string; origin: string; role?: string; observation: string; currentProcessStartedAt?: string; observedFromPid?: number; observedFromStartedAt?: string }
export interface ProcessSession { id: string; type: string; state: string; pid?: number; processStartedAt?: string; ownerCorePid?: number; ownerCoreStartedAt?: string; workerId?: string; serverId?: string; taskId?: string; coverage: string; launchReceiptMissing: boolean; observedProcessesStopped: boolean; observedProcessCount: number; unconfirmedProcessCount: number; processes: ProcessObservation[]; recordedProcesses: ProcessObservation[]; processesTruncated: boolean }
export interface ProcessRecovery { dispatchAllowed: boolean; pendingCount: number; sessions: ProcessSession[]; hasMore: boolean; coverage?: string; corePid?: number; coreStartedAt?: string }
export interface WorkerLease { id: string; sequence: number; operationId: string; operationKind: string; kind: string; operationState: string; taskId: string; agentRunId?: string; taskExecutionId?: string; dispatchActionId?: string; workspaceWriteLeaseId?: string; workerId: string; executionNodeId: string; epoch: number; repositoryBindingId: string; bindingVersion: number; authorizationVersion: number; workingDirectory: string; deployment?: string; executorPid?: number; corePid?: number; processStartedAt?: string; state: string; stopState: string; createdAt: string; heartbeatAt: string; expiresAt: string; endedAt?: string; reason?: string; evidenceId?: string }
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
  workerId?: string; workerLeaseId?: string; workerEpoch?: number; executionNodeId?: string; repositoryBindingId?: string; bindingVersion?: number;
  deployment?: string; executorPid?: number; corePid?: number; processStartedAt?: string;
  taskExecutionId?: string; dispatchActionId?: string; workspaceWriteLeaseId?: string;
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
