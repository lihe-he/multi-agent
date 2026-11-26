export type UsageWindow = 7 | 30 | 90;
export type UsageUnitName = 'API_REQUEST' | 'CLI_SESSION' | 'UNKNOWN';
export type UsageState = 'DISPATCHING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN';
export type TokenField = 'inputTokens' | 'outputTokens' | 'cachedInputTokens' | 'reasoningTokens';

export interface UsageMetric {
  observedSum: number | 'UNKNOWN';
  knownCount: number;
  unknownCount: number;
  total: number | 'UNKNOWN';
  overflow: boolean;
}

export interface UsageUnit {
  recordCount: number;
  hasRecords: boolean;
  states: Record<UsageState, number>;
  tokens: Record<TokenField, UsageMetric>;
}

export interface UsageAggregate {
  recordCount: number;
  hasRecords: boolean;
  units: Record<UsageUnitName, UsageUnit>;
}

export interface UsageTrendsResponse {
  schemaVersion: 1;
  source: 'MODEL_REQUEST';
  scope: { projectId: string; sessionId: string; taskId: string };
  days: UsageWindow;
  timeZone: 'UTC';
  bucket: 'DAY';
  from: string;
  toExclusive: string;
  asOf: string;
  cost: 'UNKNOWN';
  cliInternalRequests: 'UNKNOWN';
  summary: UsageAggregate;
  buckets: (UsageAggregate & { date: string })[];
}
