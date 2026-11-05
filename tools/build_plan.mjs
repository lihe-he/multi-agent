import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [markdownPath, moduleRoot] = process.argv.slice(2);
if (!markdownPath || !moduleRoot) throw new Error('Expected markdown path and dependency module directory');
const { marked } = await import(pathToFileURL(path.join(moduleRoot, 'marked/lib/marked.esm.js')).href);
const escapeHtml = value => value.replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const source = await fs.readFile(markdownPath, 'utf8');
const version = source.match(/^版本：\s*(\S+)\s*$/m)?.[1];
const updatedAt = source.match(/^更新日期：\s*(.+?)\s*$/m)?.[1];
if (!version || !updatedAt) throw new Error('Markdown must declare its version and update date');
let section = 0;
const headings = [];
const converted = source.replace(/^## (.+)$/gm, (_, title) => {
  const id = `section-${++section}`;
  headings.push({ id, title });
  return `<h2 id="${id}">${escapeHtml(title)}</h2>`;
});
const body = marked.parse(converted);
const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>多 Agent 智能研发工作台计划书</title>
<style>
:root{color-scheme:light;--ink:#18222d;--muted:#5d6976;--line:#d5dee7;--accent:#176366;--paper:#fff;--canvas:#f2f5f7}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:28px}body{margin:0;color:var(--ink);background:var(--canvas);font:16px/1.85 'Microsoft YaHei','PingFang SC','Noto Sans CJK SC',sans-serif}
a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
nav{position:fixed;inset:0 auto 0 0;width:276px;overflow:auto;padding:30px 22px;background:#fff;border-right:1px solid var(--line)}
.nav-title{font-size:19px;font-weight:700;margin:0 0 3px}.nav-note{font-size:13px;color:var(--muted);margin:0 0 18px}
nav ol{margin:0;padding:0;list-style:none}nav a{display:block;font-size:13px;line-height:1.65;padding:6px 8px;border-radius:5px;color:#33414e}nav a:hover{background:#edf5f4;text-decoration:none}
main{margin:32px 36px 60px 312px;max-width:1100px;background:var(--paper);padding:48px 54px;border:1px solid var(--line);border-radius:8px}
.actions{display:flex;align-items:center;gap:16px;justify-content:flex-end;margin-bottom:20px;font-size:13px}
button{font:inherit;cursor:pointer;background:#fff;color:var(--accent);border:1px solid #b4c8cb;border-radius:5px;padding:5px 13px}button:hover{background:#edf5f4}
h1{font-size:32px;line-height:1.4;color:#000;font-weight:700;margin:0 0 24px;letter-spacing:.01em}h2{font-size:24px;line-height:1.45;color:#000;margin:56px 0 20px;padding:0;scroll-margin-top:28px}h3{font-size:18px;color:#000;margin:30px 0 12px;line-height:1.5}
p{margin:0 0 16px}ul,ol{padding-left:1.6em;margin:12px 0 22px}li{padding-left:3px;margin:7px 0}strong{font-weight:700}
table{border-collapse:collapse;width:100%;margin:18px 0 26px;font-size:14px;line-height:1.7;table-layout:fixed}th,td{border:1px solid #d9d9d9;padding:11px 13px;vertical-align:middle;overflow-wrap:anywhere}th{background:#e7eef5;text-align:left;color:#000;font-weight:700}tbody tr:nth-child(even){background:#f8fafc}
table th:first-child,table td:first-child{width:25%}table:has(th:nth-child(4)) th:first-child,table:has(th:nth-child(4)) td:first-child{width:19%}#section-18~table th:first-child,#section-18~table td:first-child{width:23%}
code{font-family:Consolas,monospace;background:#f1f4f6;padding:1px 4px;border-radius:3px;overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere}
.source-footer{margin-top:48px;color:var(--muted);font-size:13px}.top-link{display:block;margin-top:25px;font-size:13px}
@media(max-width:1100px){nav{width:234px;padding:24px 16px}main{margin:20px 20px 40px 254px;padding:32px}h1{font-size:29px}}
@media(max-width:760px){nav{position:relative;width:auto;border-right:0;border-bottom:1px solid var(--line);max-height:300px}main{margin:14px;padding:24px 20px}h1{font-size:26px}h2{font-size:22px}body{font-size:15px}th,td{padding:8px;font-size:12px}.actions{justify-content:flex-start;flex-wrap:wrap}}
@media print{@page{size:A4;margin:18mm 17mm 20mm}body{background:#fff;font-size:10.5pt;line-height:1.65}nav,.actions,.top-link{display:none}main{margin:0;padding:0;border:0;max-width:none}h1{font-size:23pt}h2{font-size:16pt;margin:24pt 0 10pt;break-after:avoid}h3{font-size:12pt;break-after:avoid}p,li{orphans:3;widows:3}table{font-size:9pt;table-layout:auto}tr{break-inside:avoid}thead{display:table-header-group}th,td{padding:6pt 7pt}a{color:#18222d}.source-footer{font-size:9pt}}
</style>
</head>
<body>
<nav aria-label="章节目录"><p class="nav-title">研发工作台计划书</p><p class="nav-note">版本 ${escapeHtml(version)} · ${escapeHtml(updatedAt)}</p><ol>${headings.map(h => `<li><a href="#${h.id}">${escapeHtml(h.title)}</a></li>`).join('')}</ol></nav>
<main id="top"><div class="actions"><a href="./${encodeURIComponent(path.basename(markdownPath))}">打开 Markdown 主稿</a><button type="button" onclick="window.print()">打印或另存为 PDF</button></div><article id="plan-content">${body}</article><p class="source-footer">本阅读版由同目录 Markdown 主稿生成，内容保持一致。</p><a class="top-link" href="#top">返回顶部</a></main>
</body></html>`;
const output = markdownPath.replace(/\.md$/i, '.html');
await fs.writeFile(output, html, 'utf8');
console.log(JSON.stringify({output, version, updatedAt, sections:headings.length, sourceCharacters:source.length, htmlBytes:Buffer.byteLength(html)},null,2));
