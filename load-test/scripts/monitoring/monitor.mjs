#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, unlinkSync, openSync, closeSync, chmodSync } from 'node:fs';
import { resolve, relative } from 'node:path';
import { ROOT, runtimeDir, backendConfig, localUrl, fetchJson, positiveNumber } from './common.mjs';

const action = process.argv[2];
const dir = runtimeDir();
const bridgePort = positiveNumber('MONITORING_BRIDGE_PORT', 19105, 65535);
const bridgeUrl = 'http://127.0.0.1:' + bridgePort;
const promUrl = localUrl(process.env.MONITORING_PROMETHEUS_URL || 'http://127.0.0.1:19090');
const grafUrl = localUrl(process.env.MONITORING_GRAFANA_URL || 'http://127.0.0.1:13000');
const mysqlUrl = localUrl(process.env.MONITORING_MYSQL_URL || 'http://127.0.0.1:19104');
const readySeconds = positiveNumber('MONITORING_READY_TIMEOUT_SECONDS', 60);
let signalCode = 0, activeChild = null, wake = null, locked = false;
const stamp = () => new Date().toISOString();
const duration = (name, fallback) => {
  const n = Number(process.env[name] ?? fallback);
  if (!Number.isFinite(n) || n < 0 || n > 3600) throw new Error(name + ' must be 0..3600');
  return n;
};
function atomic(file, value, mode = 0o600) {
  const temporary = file + '.' + process.pid + '.tmp';
  writeFileSync(temporary, value, { mode });
  renameSync(temporary, file);
}
function initialize() {
  mkdirSync(resolve(dir, 'targets'), { recursive: true, mode: 0o700 });
  chmodSync(dir, 0o700);
  chmodSync(resolve(dir, 'targets'), 0o755);
  for (const name of ['backend', 'mysql']) if (!existsSync(resolve(dir, 'targets', name + '.json'))) atomic(resolve(dir, 'targets', name + '.json'), '[]\n', 0o644);
  for (const name of ['backend','mysql']) chmodSync(resolve(dir,'targets',name+'.json'), 0o644);
  const envFile = resolve(dir, 'stack.env');
  if (!existsSync(envFile)) {
    // Generated hex password avoids .env quoting problems; never printed to logs.
    atomic(envFile, [
      'PROMETHEUS_IMAGE=prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e',
      'GRAFANA_IMAGE=grafana/grafana:13.2.3@sha256:b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572',
      'MONITORING_GRAFANA_PASSWORD=' + randomBytes(24).toString('hex'), '',
    ].join('\n'));
  }
  chmodSync(envFile, 0o600);
  process.env.MONITORING_RUNTIME_DIR = dir;
}
function targets(enabled) {
  for (const [name, port] of [['backend', bridgePort], ['mysql', new URL(mysqlUrl).port || 80]]) {
    atomic(resolve(dir, 'targets', name + '.json'), JSON.stringify(enabled ? [{ targets: ['host.docker.internal:' + port] }] : []) + '\n', 0o644);
  }
}
function lock() {
  const file = resolve(dir, 'operation.lock');
  if (existsSync(file)) {
    const pid = Number(readFileSync(file, 'utf8'));
    let alive = true;
    try { process.kill(pid, 0); } catch (e) { if (e.code === 'ESRCH') alive = false; }
    if (alive || !Number.isSafeInteger(pid) || pid <= 0) throw new Error('Another monitoring operation is active; do not run concurrent tests');
    unlinkSync(file); // a crashed prior owner, not a running process
  }
  const fd = openSync(file, 'wx', 0o600); writeFileSync(fd, String(process.pid)); closeSync(fd); locked = true;
}
function unlock() {
  if (locked) { try { unlinkSync(resolve(dir, 'operation.lock')); } catch {} locked = false; }
}
function interrupt(name) {
  signalCode ||= name === 'SIGINT' ? 130 : 143;
  if (activeChild) { try { process.kill(-activeChild.pid, name); } catch {} }
  if (wake) wake();
}
process.on('SIGINT', () => interrupt('SIGINT'));
process.on('SIGTERM', () => interrupt('SIGTERM'));
async function sleep(ms) {
  if (signalCode) return;
  await new Promise((yes) => { const timer = setTimeout(() => { wake = null; yes(); }, ms); wake = () => { clearTimeout(timer); wake = null; yes(); }; });
}
function checkSignal() { if (signalCode) throw new Error('Monitoring execution interrupted'); }
async function command(executable, args, { supervised = false, env = process.env } = {}) {
  return new Promise((yes, no) => {
    const child = spawn(executable, args, { cwd: ROOT, env, stdio: 'inherit', detached: true });
    if (supervised) activeChild = child;
    child.once('error', (e) => { if (activeChild === child) activeChild = null; no(e); });
    child.once('exit', (code, sig) => { if (activeChild === child) activeChild = null; yes(code ?? (sig === 'SIGINT' ? 130 : 143)); });
  });
}
async function compose(args) {
  const rc = await command('docker', ['compose', '-p', 'audigo-loadtest-monitoring', '--env-file', resolve(dir, 'stack.env'), '-f', resolve(ROOT, 'monitoring/compose.yaml'), ...args]);
  if (rc) throw new Error('Docker Compose failed (exit ' + rc + ')');
}
async function bridgeStop() {
  const file = resolve(dir, 'bridge.pid.json');
  if (!existsSync(file)) return;
  const owner = JSON.parse(readFileSync(file, 'utf8'));
  let health;
  try { health = await fetchJson(bridgeUrl + '/health', {}, 2000); } catch {
    // /health can be 503; ownership still must be read from its JSON before killing.
    try { const r = await fetch(bridgeUrl + '/health', { redirect: 'error', signal: AbortSignal.timeout(2000) }); health = await r.json(); } catch {}
  }
  if (health && (health.pid !== owner.pid || health.instanceId !== owner.instanceId)) throw new Error('Bridge ownership mismatch; refusing to stop another process');
  if (!health) {
    let alive = true; try { process.kill(owner.pid, 0); } catch (e) { if (e.code === 'ESRCH') alive = false; }
    if (alive) throw new Error('Bridge ownership cannot be verified; refusing to kill an unrelated/reused PID');
  } else {
    process.kill(owner.pid, 'SIGTERM');
    let stopped = false;
    for (let i = 0; i < 60; i++) {
      try { process.kill(owner.pid, 0); } catch (e) { if (e.code === 'ESRCH') { stopped = true; break; } }
      await new Promise((yes) => setTimeout(yes, 100));
    }
    if (!stopped) throw new Error('Bridge did not stop; ownership file retained. Targets disabled; inspect bridge.log before another run');
  }
  unlinkSync(file);
}
async function bridgeStart() {
  const config = backendConfig();
  if (new URL(config.baseUrl).protocol === 'http:' && !['localhost','127.0.0.1','[::1]'].includes(new URL(config.baseUrl).hostname)) console.warn('WARNING: explicitly allowed HTTP sends isolated Staging JWT unencrypted; HTTPS or Backend SSH tunnel is recommended');
  await bridgeStop();
  const instanceId = randomUUID();
  const fd = openSync(resolve(dir, 'bridge.log'), 'a', 0o600);
  const child = spawn(process.execPath, [resolve(ROOT, 'scripts/monitoring/bridge.mjs')], {
    cwd: ROOT, detached: true, stdio: ['ignore', fd, fd], env: { ...process.env, MONITORING_INSTANCE_ID: instanceId },
  });
  closeSync(fd);
  child.unref();
  atomic(resolve(dir, 'bridge.pid.json'), JSON.stringify({ pid: child.pid, instanceId }) + '\n');
  await wait('Backend collection readiness', async () => {
    const response = await fetch(bridgeUrl + '/health', { redirect: 'error', signal: AbortSignal.timeout(2000) });
    const body = await response.json();
    if (body.authenticationFailed) throw new Error('Staging authentication failed (401/403); regenerate JWT; no load started');
    return response.ok && body.ready && body.instanceId === instanceId;
  });
}
async function wait(description, probe, timeout = readySeconds) {
  const end = Date.now() + timeout * 1000;
  let last = '';
  while (Date.now() < end) {
    checkSignal();
    try { if (await probe()) return; } catch (e) { last = e.message; if (last.includes('401/403')) throw e; }
    await sleep(500);
  }
  throw new Error(description + ' timed out' + (last ? ': ' + last : ''));
}
async function mysqlCheck() {
  const response = await fetch(mysqlUrl + '/metrics', { redirect: 'error', signal: AbortSignal.timeout(5000) });
  if (!response.ok || !/^mysql_up\s+1\s*$/m.test(await response.text())) throw new Error('MySQL metrics unavailable/mysql_up!=1; open the SSH tunnel first');
}
async function stackReady() {
  await compose(['up', '-d']);
  await wait('Prometheus readiness', async () => {
    const r = await fetch(promUrl + '/-/ready', { signal: AbortSignal.timeout(2000), redirect: 'error' }); return r.ok;
  }, 120);
  await wait('Grafana readiness', async () => (await fetchJson(grafUrl + '/api/health', {}, 2000)).database === 'ok', 120);
}
async function scrapeReady() {
  const url = new URL(promUrl + '/api/v1/query');
  url.searchParams.set('query', '{__name__=~"up|audigo_backend_up|mysql_up",job=~"audigo-(backend|mysql)"}');
  await wait('Prometheus Backend/MySQL scraping', async () => {
    const body = await fetchJson(url, {}, 3000);
    return [['audigo-backend','up'],['audigo-backend','audigo_backend_up'],['audigo-mysql','up'],['audigo-mysql','mysql_up']].every(([job, metric]) =>
      body.status === 'success' && body.data?.result?.some((s) => s.metric.job === job && s.metric.__name__ === metric && Number(s.value?.[1]) === 1));
  });
}
async function startCollection() { await mysqlCheck(); await bridgeStart(); await stackReady(); targets(true); await scrapeReady(); }
async function pause() { targets(false); await bridgeStop(); }
async function run() {
  const args = process.argv.slice(3);
  const reset = args[0] === '--reset'; if (reset) args.shift();
  const scenario = args.shift();
  const abs = resolve(ROOT, scenario || '');
  if (!scenario || !relative(resolve(ROOT, 'scenarios'), abs).endsWith('.js') || relative(resolve(ROOT, 'scenarios'), abs).startsWith('..') || !existsSync(abs)) throw new Error('Usage: monitor.sh run [--reset] scenarios/<scenario>.js [k6 options]');
  if (reset && ['CONFIRM_STAGING','ALLOW_WRITE_TESTS','CONFIRM_COMPLETION_RESET'].some((n) => process.env[n] !== 'true')) throw new Error('--reset requires CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true');
  backendConfig();
  const pre = duration('MONITORING_PRE_SECONDS', 30), post = duration('MONITORING_POST_SECONDS', 30);
  const id = process.env.TEST_RUN_ID || 'monitored-' + stamp().replace(/[:.]/g, '-') + '-' + process.pid;
  if (!/^[A-Za-z0-9._-]+$/.test(id)) throw new Error('Invalid TEST_RUN_ID');
  const result = resolve(ROOT, 'results', id);
  if (existsSync(result)) throw new Error('Result directory already exists; choose a new TEST_RUN_ID');
  const meta = { runId: id, scenario: relative(ROOT, abs), baseUrl: new URL(process.env.BASE_URL).origin, backendMetricsUrl: backendConfig().baseUrl, startedAt: stamp(), collectionStartedAt: null, loadStartedAt: null, loadEndedAt: null, collectionEndedAt: null, executionExitCode: null, exportExitCode: null, interrupted: false, preSeconds: pre, postSeconds: post, intervalSeconds: positiveNumber('MONITORING_INTERVAL_SECONDS',5), phases: [], notes: 'Read-only monitoring requests are separate from k6 performance measurements. UTC timestamps; Grafana Asia/Seoul.' };
  const phases = resolve(dir, id + '-phases.jsonl');
  let rc = 0;
  try {
    console.log('monitoring_run_id=' + id);
    await startCollection();
    meta.collectionStartedAt = stamp();
    console.log('Collecting pre-test metrics: ' + pre + 's');
    await sleep(pre * 1000); checkSignal();
    meta.loadStartedAt = stamp();
    const runner = reset ? 'scripts/test/run-completion-with-reset.sh' : 'scripts/test/run-k6.sh';
    const env = { ...process.env, TEST_RUN_ID: id, LOAD_TEST_SKIP_DOTENV: 'true', MONITORING_PHASE_FILE: phases };
    if (!env.K6_WEB_DASHBOARD_EXPORT) env.K6_WEB_DASHBOARD_EXPORT = resolve(result, 'dashboard.html');
    rc = await command(resolve(ROOT, runner), [relative(ROOT, abs), ...args], { supervised: true, env });
    meta.executionExitCode = rc;
    meta.loadEndedAt = stamp();
    if (!signalCode) { console.log('Collecting post-test metrics: ' + post + 's'); await sleep(post * 1000); }
  } catch (e) {
    meta.error = e.message; rc ||= signalCode || 1; console.error('Monitoring run: ' + e.message);
  } finally {
    meta.collectionEndedAt = stamp(); meta.interrupted = !!signalCode;
    // Result dir is created AFTER runner starts: preserve its collision protection.
    mkdirSync(resolve(result, 'monitoring'), { recursive: true, mode: 0o700 });
    if (existsSync(phases)) {
      meta.phases = readFileSync(phases, 'utf8').split('\n').filter(Boolean).flatMap((line) => { try { return [JSON.parse(line)]; } catch { return []; } });
      const performanceEnd = meta.phases.find((p) => p.phase === 'performance_end');
      if (performanceEnd) meta.loadEndedAt = performanceEnd.timestamp;
      unlinkSync(phases);
    }
    try {
      meta.exportExitCode = await command(process.execPath, [resolve(ROOT,'scripts/monitoring/export.mjs'), '--run-dir', result, '--start', meta.collectionStartedAt || meta.startedAt, '--end', meta.collectionEndedAt, ...(!meta.collectionStartedAt ? ['--collection-not-started'] : [])]);
    } catch (e) { meta.exportExitCode = 1; meta.exportError = e.message; }
    try { await pause(); } catch (e) { meta.cleanupError = e.message; rc ||= 1; }
    atomic(resolve(result, 'monitoring/run.json'), JSON.stringify(meta, null, 2) + '\n');
    console.log('Results: ' + relative(ROOT, result) + '; Grafana: ' + grafUrl);
  }
  return signalCode || rc || meta.exportExitCode || 0;
}
async function main() {
  if (!['prepare','run','view','status','stop'].includes(action)) throw new Error('Usage: monitor.sh {prepare|run [--reset] <scenario>|view|status|stop}');
  initialize();
  if (action === 'status') {
    console.log('Grafana=' + grafUrl + '\nPrometheus=' + promUrl + '\nRuntime=' + dir);
    for (const name of ['backend','mysql']) console.log(name + '_targets=' + readFileSync(resolve(dir,'targets',name+'.json'),'utf8').trim());
    await compose(['ps']); return;
  }
  lock();
  try {
    if (action === 'run') { process.exitCode = await run(); return; }
    if (action === 'prepare') {
      backendConfig();
      try { await startCollection(); console.log('Monitoring readiness passed'); }
      finally { await pause(); }
      console.log('Collection paused. Grafana=' + grafUrl + '; admin password file=' + resolve(dir,'stack.env'));
    } else if (action === 'view') { await pause(); await stackReady(); console.log('Historical viewing only: ' + grafUrl); }
    else { await pause(); await compose(['stop']); console.log('Monitoring stopped; persistent volumes preserved; SSH/EC2 unchanged'); }
  } finally { unlock(); }
}
main().catch((e) => { console.error('Monitoring: ' + e.message); process.exitCode = signalCode || 1; });
