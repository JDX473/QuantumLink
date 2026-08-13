#!/usr/bin/env node
/**
 * QuantumLink 压测控制台
 * 用法: node scripts/loadtest-console.js [port]      (默认 8899)
 * 打开 http://127.0.0.1:8899 :配置参数 → 启动压测 → SSE 实时进度 → P50/P90/P99 曲线
 *
 * 依赖: im-loadtest/target/im-loadtest-1.0.0-SNAPSHOT.jar(先 mvn package 构建)
 *       JDK17(JAVA_HOME 或 PATH 里的 java;默认 java 是 JDK8 会报 UnsupportedClassVersionError)
 */
const http = require('http');
const { spawn } = require('child_process');
const path = require('path');

const PORT = parseInt(process.argv[2] || '8899');
const ROOT = path.resolve(__dirname, '..');
const JAR = path.join(ROOT, 'im-loadtest', 'target', 'im-loadtest-1.0.0-SNAPSHOT.jar');
const fs = require('fs');
// 压测 jar 是 JDK17 编译,必须 JDK17 跑。优先项目 JDK17,其次 JAVA_HOME,最后 PATH(Windows 上 PATH 常是 JDK8,会 UnsupportedClassVersionError)
const JDK17 = process.platform === 'win32' && fs.existsSync('D:/jdk17/bin/java.exe') ? 'D:/jdk17/bin/java.exe' : null;
const JAVA = JDK17 || (process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java');

const runs = new Map(); // runId -> { args, child, progress:[], final, startedAt }

// ==================== 启动一次压测 ====================
function startRun(args) {
  const runId = 'run-' + Date.now();
  const run = { args, child: null, progress: [], final: '', startedAt: Date.now() };
  runs.set(runId, run);
  const child = spawn(JAVA, ['-jar', JAR, ...args], { cwd: ROOT });
  run.child = child;
  let buf = '';
  child.stdout.on('data', (d) => {
    buf += d.toString();
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (line.startsWith('PROGRESS|')) {
        const parts = line.split('|');
        if (parts.length >= 7) {
          run.progress.push({
            el: +parts[1], sent: +parts[2], ack: +parts[3],
            p50: +parts[4], p90: +parts[5], p99: +parts[6],
          });
        }
      } else if (line.includes('连接 :') || line.includes('消息 :') || line.includes('吞吐') || line.includes('延迟')) {
        run.final += line + '\n';
      }
    }
  });
  child.on('exit', () => { run.child = null; });
  return runId;
}

// ==================== HTTP 服务 ====================
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');

  // 页面
  if (req.method === 'GET' && url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(PAGE);
    return;
  }

  // 启动压测
  if (req.method === 'POST' && url.pathname === '/api/run') {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      try {
        const p = JSON.parse(body);
        const args = [
          String(p.connections || 100), String(p.rate || 5), String(p.duration || 30),
          p.host || '127.0.0.1', p.ports || '19001,19002', p.quiet ? 'true' : 'false',
        ];
        const runId = startRun(args);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ runId }));
      } catch (e) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: String(e) }));
      }
    });
    return;
  }

  // 停止压测
  if (req.method === 'POST' && url.pathname === '/api/stop') {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      try {
        const { runId } = JSON.parse(body);
        const run = runs.get(runId);
        if (run && run.child) run.child.kill();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('{}');
      } catch { res.writeHead(400); res.end(); }
    });
    return;
  }

  // SSE 实时进度
  if (req.method === 'GET' && url.pathname === '/api/progress') {
    const runId = url.searchParams.get('runId');
    const run = runs.get(runId);
    if (!run) { res.writeHead(404); res.end('no run'); return; }
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
    });
    const push = () => res.write(`data: ${JSON.stringify({
      progress: run.progress, final: run.final, running: !!run.child,
    })}\n\n`);
    push();
    const timer = setInterval(push, 1000);
    req.on('close', () => clearInterval(timer));
    return;
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(PORT, () => {
  console.log(`QuantumLink 压测控制台: http://127.0.0.1:${PORT}`);
  console.log(`  jar = ${JAR}`);
  console.log(`  java = ${JAVA}(必须 JDK17,否则 UnsupportedClassVersionError)`);
});

// ==================== 页面(内嵌,无外部依赖) ====================
const PAGE = `<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>QuantumLink 压测控制台</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: #0d1117; color: #c9d1d9; font-family: "Segoe UI", "Microsoft YaHei", sans-serif; padding: 24px; }
  h1 { font-size: 20px; color: #58a6ff; margin-bottom: 16px; }
  .panel { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
  .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end; }
  .field { display: flex; flex-direction: column; gap: 4px; }
  .field label { font-size: 12px; color: #8b949e; }
  .field input { background: #0d1117; border: 1px solid #30363d; color: #c9d1d9; border-radius: 6px; padding: 6px 10px; width: 130px; }
  button { background: #238636; color: #fff; border: none; border-radius: 6px; padding: 8px 20px; cursor: pointer; font-size: 14px; }
  button.stop { background: #da3633; }
  button:disabled { opacity: .5; cursor: not-allowed; }
  .cards { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
  .card { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 10px 16px; min-width: 110px; }
  .card .k { font-size: 11px; color: #8b949e; }
  .card .v { font-size: 20px; font-weight: 600; color: #58a6ff; margin-top: 2px; }
  canvas { width: 100%; height: 260px; background: #0d1117; border: 1px solid #30363d; border-radius: 8px; }
  pre { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 12px; font-size: 12px; color: #7ee787; white-space: pre-wrap; max-height: 200px; overflow: auto; }
  .legend { font-size: 12px; color: #8b949e; margin: 6px 2px; }
  .legend span { margin-right: 14px; }
  .p50 { color: #58a6ff; } .p90 { color: #d29922; } .p99 { color: #f85149; }
</style>
</head>
<body>
<h1>QuantumLink 压测控制台</h1>

<div class="panel">
  <div class="row">
    <div class="field"><label>连接数</label><input id="connections" value="100"></div>
    <div class="field"><label>每连接速率(条/s)</label><input id="rate" value="5"></div>
    <div class="field"><label>时长(秒)</label><input id="duration" value="30"></div>
    <div class="field"><label>connect host</label><input id="host" value="127.0.0.1"></div>
    <div class="field"><label>端口(逗号分隔)</label><input id="ports" value="19001,19002"></div>
    <div class="field" style="flex-direction:row; align-items:center; gap:6px;">
      <input type="checkbox" id="quiet" checked style="width:auto;"><label for="quiet" style="font-size:13px;color:#c9d1d9;">静默模式</label>
    </div>
    <button id="startBtn" onclick="startRun()">开始压测</button>
    <button id="stopBtn" class="stop" onclick="stopRun()" disabled>停止</button>
  </div>
  <div class="legend" style="margin-top:8px;">延迟口径: 发消息 → ACK-STORE 端到端(ms)</div>
</div>

<div class="panel">
  <div class="cards">
    <div class="card"><div class="k">已运行</div><div class="v" id="cElapsed">0s</div></div>
    <div class="card"><div class="k">握手成功/总</div><div class="v" id="cConn">-</div></div>
    <div class="card"><div class="k">发送</div><div class="v" id="cSent">0</div></div>
    <div class="card"><div class="k">ACK-STORE</div><div class="v" id="cAck">0</div></div>
    <div class="card"><div class="k">送达率</div><div class="v" id="cRate">-</div></div>
    <div class="card"><div class="k" class="p50">P50</div><div class="v" id="cP50">-</div></div>
    <div class="card"><div class="k" class="p90">P90</div><div class="v" id="cP90">-</div></div>
    <div class="card"><div class="k" class="p99">P99</div><div class="v" id="cP99">-</div></div>
  </div>
  <canvas id="chart" width="1000" height="260"></canvas>
  <div class="legend"><span class="p50">■ P50</span><span class="p90">■ P90</span><span class="p99">■ P99</span></div>
</div>

<div class="panel"><pre id="final">等待压测...</pre></div>

<script>
let runId = null;
let es = null;
let data = [];   // [{el, p50, p90, p99}]

function $(id) { return document.getElementById(id); }

async function startRun() {
  const body = {
    connections: +$('connections').value,
    rate: +$('rate').value,
    duration: +$('duration').value,
    host: $('host').value.trim(),
    ports: $('ports').value.trim(),
    quiet: $('quiet').checked,
  };
  $('startBtn').disabled = true;
  $('stopBtn').disabled = false;
  $('final').textContent = '压测启动中...';
  data = [];
  const res = await fetch('/api/run', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const r = await res.json();
  if (!r.runId) { $('final').textContent = '启动失败: ' + JSON.stringify(r); $('startBtn').disabled = false; return; }
  runId = r.runId;
  if (es) es.close();
  es = new EventSource('/api/progress?runId=' + runId);
  es.onmessage = (ev) => {
    const s = JSON.parse(ev.data);
    if (s.progress && s.progress.length) {
      data = s.progress;
      updateCards(s.progress[s.progress.length - 1]);
      drawChart();
    }
    $('final').textContent = s.final || '(运行中, 等待最终汇总...)';
    if (!s.running) { $('startBtn').disabled = false; $('stopBtn').disabled = true; }
  };
}

function stopRun() {
  if (!runId) return;
  fetch('/api/stop', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ runId }) });
}

function updateCards(last) {
  $('cElapsed').textContent = last.el + 's';
  $('cSent').textContent = last.sent;
  $('cAck').textContent = last.ack;
  $('cRate').textContent = last.sent > 0 ? (100 * last.ack / last.sent).toFixed(1) + '%' : '-';
  $('cP50').textContent = last.p50 + 'ms';
  $('cP90').textContent = last.p90 + 'ms';
  $('cP99').textContent = last.p99 + 'ms';
}

function drawChart() {
  const c = $('chart');
  const ctx = c.getContext('2d');
  const W = c.width, H = c.height;
  ctx.clearRect(0, 0, W, H);
  if (data.length < 2) return;
  const maxEl = Math.max(...data.map(d => d.el), 1);
  const maxV = Math.max(...data.map(d => d.p99), 10);
  const px = (el) => 40 + (el / maxEl) * (W - 60);
  const py = (v) => H - 24 - (v / maxV) * (H - 48);
  // 网格
  ctx.strokeStyle = '#21262d'; ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = 24 + (i / 4) * (H - 48);
    ctx.beginPath(); ctx.moveTo(40, y); ctx.lineTo(W - 20, y); ctx.stroke();
    ctx.fillStyle = '#8b949e'; ctx.font = '10px sans-serif';
    ctx.fillText(Math.round(maxV * (1 - i / 4)) + 'ms', 4, y + 3);
  }
  const series = [
    { key: 'p50', color: '#58a6ff' },
    { key: 'p90', color: '#d29922' },
    { key: 'p99', color: '#f85149' },
  ];
  for (const s of series) {
    ctx.strokeStyle = s.color; ctx.lineWidth = 2;
    ctx.beginPath();
    data.forEach((d, i) => {
      const x = px(d.el), y = py(d[s.key]);
      i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
    });
    ctx.stroke();
  }
  ctx.fillStyle = '#8b949e'; ctx.font = '10px sans-serif';
  ctx.fillText('0s', 36, H - 8); ctx.fillText(maxEl + 's', W - 40, H - 8);
}
</script>
</body>
</html>`;
