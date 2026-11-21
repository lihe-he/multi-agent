import { expect, test } from '@playwright/test';

test('CLI 路由可配置操作系统沙箱，并在切换 API 时明确关闭', async ({ page }) => {
  const route = { id: 'cli-route', name: '受限 Codex', engine: 'CODEX', executable: 'codex', enabled: true, requireOsSandbox: true, osSandboxNetwork: 'ALLOW' };
  const saved: any[] = [];
  await page.route('**/api/**', async requestRoute => {
    const request = requestRoute.request();
    const path = new URL(request.url()).pathname.replace('/api', '');
    if (path === '/state') return requestRoute.fulfill({ json: { projects: [], tasks: [], routes: [route], profiles: [], memories: [], system: {} } });
    if (path === '/routes' && request.method() === 'POST') {
      const body = request.postDataJSON(); saved.push(body);
      return requestRoute.fulfill({ json: { ...route, ...body } });
    }
    return requestRoute.fulfill({ json: {} });
  });

  await page.goto('/');
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: /编辑配置/ }).click();

  const sandbox = page.getByRole('checkbox', { name: /要求操作系统沙箱/ });
  const network = page.getByLabel('沙箱网络访问', { exact: true });
  await expect(sandbox).toBeChecked();
  await expect(network).toHaveValue('ALLOW');
  await page.getByRole('button', { name: '保存运行器', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  expect(saved[0]).toMatchObject({ engine: 'CODEX', requireOsSandbox: true, osSandboxNetwork: 'ALLOW' });

  await page.getByRole('button', { name: /编辑配置/ }).click();
  await page.getByLabel('运行引擎', { exact: true }).selectOption('API');
  await expect(sandbox).not.toBeChecked();
  await expect(sandbox).toBeDisabled();
  await expect(page.getByText('此设置仅适用于 Codex CLI 和 Claude Code。')).toBeVisible();
  await expect(network).toBeDisabled();
  await page.getByLabel('模型', { exact: true }).fill('api-model');
  await page.getByRole('button', { name: '保存运行器', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  expect(saved[1]).toMatchObject({ engine: 'API', requireOsSandbox: false, osSandboxNetwork: 'DENY' });
});
