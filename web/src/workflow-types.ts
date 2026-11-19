import type { AgentDefinition, AgentPermission, Route } from './types';

export interface WorkflowNode {
  id: string; kind: string; title: string; role: string; goal: string;
  definitionId: string; definitionVersion: number; dependencies: string[];
  inputs: string[]; outputs: string[]; scope: string[]; permissions: string[];
  maxCalls: number; timeoutSeconds: number; acceptance: string[]; termination: string;
}
export interface WorkflowDsl {
  dslVersion: number; inputs: string[]; maxParallel: number; nodes: WorkflowNode[];
}
export interface WorkflowDefinition {
  id: string; name: string; projectId: string; version: number; enabled: boolean;
  builtin?: boolean; createdAt: string; updatedAt: string;
}
export interface WorkflowVersion {
  id: string; definitionId: string; version: number; name: string; projectId?: string;
  digest: string; dsl: WorkflowDsl; agentDefinitions: AgentDefinition[];
  source: string; sourceVersionId?: string; createdAt: string; nodeCount?: number;
}
export type WorkflowRevision = Pick<WorkflowVersion, 'id' | 'definitionId' | 'version' | 'name' | 'digest' | 'source' | 'sourceVersionId' | 'createdAt' | 'nodeCount'>;
export interface WorkflowSnapshot {
  protocolVersion: number; definitionId: string; version: number; name: string;
  digest: string; snapshotDigest: string; dsl?: WorkflowDsl; agentDefinitions?: AgentDefinition[];
  bindings?: { nodeId: string; actor: AgentDefinition; routeSnapshot: Route; permissions: AgentPermission }[];
}
export interface WorkflowSelection {
  definitionId: string; version: number; digest: string; allowedRouteIds: string[];
}
export interface WorkflowRun {
  id: string; taskId: string; generation: number; state: string;
  definitionId: string; definitionVersion: number; definitionDigest: string; snapshotDigest: string;
  baselineVersion: string; rootVersion: string; createdAt: string; endedAt?: string; reason?: string;
  previousRunId?: string; nodes: Record<string, { state: string; attempt: number; attemptId?: string; reason?: string }>;
}
export interface WorkflowAttempt {
  id: string; taskId: string; workflowRunId: string; nodeId: string; attempt: number; sequence: number;
  state: string; node: WorkflowNode; agentRunId: string; evidenceId?: string;
  permissions: AgentPermission; ancestorNodeIds: string[]; inputVersion?: string; resultVersion?: string;
  createdAt: string; endedAt?: string; reason?: string; integrationState: string;
  inputs?: Record<string, { sourceNodeId: string; sourceAttemptId: string; evidenceId: string; output?: string }>;
}
export interface WorkflowProposal {
  id: string; taskId: string; projectId: string; generationId: string; sequence: number; state: string;
  name: string; sourceDigest: string; digest?: string; dsl?: WorkflowDsl; agentDefinitions?: AgentDefinition[];
  errors: string[]; agentRunId: string; modelRequestId: string; evidenceId: string; createdAt: string;
}
export interface WorkflowGeneration {
  id: string; requestId: string; state: string; sequence: number; agentRunId?: string;
  maxCalls: number; createdAt: string; endedAt?: string; reason?: string; proposalId?: string;
}
export type WorkflowPage<Key extends string, T> = { hasMore: boolean; next: number } & Record<Key, T[]>;
