const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

(async () => {
  const [moduleRoot, htmlPath, output, markdownArgument] = process.argv.slice(2);
  if (!moduleRoot || !htmlPath || !output) throw new Error('Expected dependency module directory, HTML path and check output directory');
  const markdownPath = markdownArgument || htmlPath.replace(/\.html$/i, '.md');
  const { chromium } = require(path.join(moduleRoot, 'playwright'));
  const { marked } = await import(pathToFileURL(path.join(moduleRoot, 'marked/lib/marked.esm.js')).href);
  const source = fs.readFileSync(markdownPath, 'utf8');
  const version = source.match(/^版本：\s*(\S+)\s*$/m)?.[1];
  const updatedAt = source.match(/^更新日期：\s*(.+?)\s*$/m)?.[1];
  if (!version || !updatedAt) throw new Error('Markdown must declare its version and update date');
  const sourceHeadings = [...source.matchAll(/^## (.+)$/gm)].map(match => match[1].trim());
  const sourceCases = [...source.matchAll(/^\|\s*(T\d{2})\s*\|/gm)].map(match => match[1]);
  const expectedCases = Array.from({ length: 36 }, (_, index) => `T${String(index + 1).padStart(2, '0')}`);
  const expectedBody = marked.parse(source);
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1500, height: 1100 }, deviceScaleFactor: 1 });
    await page.goto(pathToFileURL(htmlPath).href);
    await page.evaluate(() => document.fonts.ready);
    const audit = await page.evaluate(({ expectedBody, sourceHeadings, sourceCases, expectedCases, version, updatedAt }) => {
      const normalize = text => text.replace(/\s+/g, ' ').trim();
      const expected = document.createElement('template');
      expected.innerHTML = expectedBody;
      const article = document.querySelector('#plan-content');
      const headings = [...document.querySelectorAll('#plan-content h2')];
      const links = [...document.querySelectorAll('nav ol a')];
      const caseIds = [...document.querySelectorAll('#plan-content td:first-child')].map(td => td.textContent.trim()).filter(text => /^T\d{2}$/.test(text));
      const cells = [...document.querySelectorAll('td, th')];
      const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
      return {
        version,
        updatedAt,
        headings: headings.length,
        headingsMatchSource: equal(headings.map(h => h.textContent.trim()), sourceHeadings),
        sectionNumbersContinuous: sourceHeadings.length === 19 && sourceHeadings.every((title, index) => title.startsWith(`${index + 1} `)),
        tables: document.querySelectorAll('#plan-content table').length,
        cases: caseIds.length,
        caseIds,
        casesContinuous: equal(sourceCases, expectedCases) && equal(caseIds, expectedCases),
        versionAndDateMatch: document.querySelector('.nav-note')?.textContent.trim() === `版本 ${version} · ${updatedAt}`,
        bodyMatchesSource: !!article && normalize(article.textContent) === normalize(expected.content.textContent),
        navigationMatches: links.length === headings.length && links.every((link, index) => link.getAttribute('href') === `#${headings[index].id}` && link.textContent.trim() === sourceHeadings[index]),
        brokenAnchors: links.filter(link => !document.getElementById(link.getAttribute('href').slice(1))).map(link => link.textContent),
        duplicateIds: [...document.querySelectorAll('[id]')].map(element => element.id).filter((id, index, ids) => ids.indexOf(id) !== index),
        overflowingCells: cells.filter(cell => cell.scrollWidth > cell.clientWidth + 2).length,
        horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth,
        bodyText: article?.innerText.length || 0
      };
    }, { expectedBody, sourceHeadings, sourceCases, expectedCases, version, updatedAt });
    await page.screenshot({ path: path.join(output, 'plan-top.png') });
    await page.locator('#section-18').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, 'plan-acceptance.png') });
    await page.setViewportSize({ width: 430, height: 1000 });
    await page.evaluate(() => window.scrollTo(0, 0));
    Object.assign(audit, await page.evaluate(() => ({
      mobileOverflow: document.documentElement.scrollWidth > window.innerWidth,
      mobileOverflowingCells: [...document.querySelectorAll('td, th')].filter(cell => cell.scrollWidth > cell.clientWidth + 2).length
    })));
    await page.screenshot({ path: path.join(output, 'plan-mobile.png') });
    fs.writeFileSync(path.join(output, 'layout-check.json'), JSON.stringify(audit, null, 2));
    console.log(JSON.stringify(audit, null, 2));
    if (audit.headings !== 19 || !audit.headingsMatchSource || !audit.sectionNumbersContinuous || !audit.casesContinuous || !audit.versionAndDateMatch || !audit.bodyMatchesSource || !audit.navigationMatches || audit.brokenAnchors.length || audit.duplicateIds.length || audit.overflowingCells || audit.horizontalOverflow || audit.mobileOverflow || audit.mobileOverflowingCells) process.exitCode = 1;
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
