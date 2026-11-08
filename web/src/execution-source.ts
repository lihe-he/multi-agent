export function executionSource(deployment?: string) {
  return deployment === 'LOCAL_PROCESS' ? '本机独立 Worker' : deployment === 'CORE_PROCESS' ? '本机核心进程' : '历史记录未提供执行方式';
}
