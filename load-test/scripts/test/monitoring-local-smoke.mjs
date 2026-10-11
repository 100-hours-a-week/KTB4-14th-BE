#!/usr/bin/env node
/**
 * Local-only black-box smoke for scripts/monitoring/{bridge,export}.mjs.
 * It never contacts Staging, MySQL, Docker, or a real Prometheus server.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { chmod, mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const loadTestRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const bridgeFile = resolve(loadTestRoot, 'scripts/monitoring/bridge.mjs');
const exportFile = resolve(loadTestRoot, 'scripts/monitoring/export.mjs');
const commonFile = resolve(loadTestRoot, 'scripts/monitoring/common.mjs');
const monitorShell = resolve(loadTestRoot, 'scripts/monitoring/monitor.sh');

function delay(ms) { return new Promise((resolveDelay) => setTimeout(resolveDelay, ms)); }
function listen(server) {
  return new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(0, '127.0.0.1', () => resolveListen(server.address().port));
  });
}
function close(server) { return new Promise((resolveClose) => server.close(resolveClose)); }
function json(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(value));
}
async function get(url) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(2_000) });
  return { status: response.status, text: await response.text() };
}
function runNode(file, args, env) {
  const child = spawn(process.execPath, [file, ...args], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const done = new Promise((resolveDone, rejectDone) => {
    child.once('error', rejectDone);
    child.once('exit', (code, signal) => resolveDone({ code, signal, stdout, stderr }));
  });
  return { child, done, output: () => ({ stdout, stderr }) };
}
function runShell(file, args, env) {
  const child = spawn('bash', [file, ...args], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const done = new Promise((resolveDone, rejectDone) => {
    child.once('error', rejectDone);
    child.once('exit', (code, signal) => resolveDone({ code, signal, stdout, stderr }));
  });
  return { child, done, output: () => ({ stdout, stderr }) };
}
async function waitFor(description, predicate, timeout = 5_000) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try { if (await predicate()) return; } catch (error) { lastError = error; }
    await delay(25);
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? `: ${lastError.message}` : ''}`);
}
async function stop(processHandle) {
  if (processHandle.child.exitCode !== null) return processHandle.done;
  processHandle.child.kill('SIGTERM');
  const result = await Promise.race([
    processHandle.done,
    delay(2_000).then(() => null),
  ]);
  if (result) return result;
  processHandle.child.kill('SIGKILL');
  return processHandle.done;
}

const rootNames = [
  'hikaricp.connections.active',
  'hikaricp.connections.acquire',
  'hikaricp.connections.timeout',
  'jvm.memory.used',
  'jvm.memory.max',
  'jvm.gc.pause',
  'http.server.requests',
];
const timer = (count, total, max) => ({ baseUnit: 'seconds', measurements: [
  { statistic: 'COUNT', value: count },
  { statistic: 'TOTAL_TIME', value: total },
  { statistic: 'MAX', value: max },
] });
function metricFixture(name, tag) {
  if (name === 'hikaricp.connections.active') {
    if (!tag) return { availableTags: [{ tag: 'pool', values: ['TestPool'] }], measurements: [{ statistic: 'VALUE', value: 3 }] };
    return { measurements: [{ statistic: 'VALUE', value: 3 }] };
  }
  if (name === 'hikaricp.connections.acquire') {
    if (!tag) return { availableTags: [{ tag: 'pool', values: ['TestPool'] }], ...timer(5, 2, 1) };
    return timer(5, 2, 1);
  }
  if (name === 'hikaricp.connections.timeout') return { measurements: [{ statistic: 'COUNT', value: 7 }] };
  if (name === 'jvm.memory.used') {
    if (!tag) return { availableTags: [{ tag: 'area', values: ['heap', 'nonheap'] }], measurements: [{ statistic: 'VALUE', value: 120 }] };
    return { measurements: [{ statistic: 'VALUE', value: tag === 'area:heap' ? 100 : 20 }] };
  }
  if (name === 'jvm.memory.max') {
    if (!tag) return { availableTags: [{ tag: 'area', values: ['heap', 'nonheap'] }], measurements: [{ statistic: 'VALUE', value: 200 }] };
    return { measurements: [{ statistic: 'VALUE', value: tag === 'area:heap' ? 160 : 40 }] };
  }
  if (name === 'jvm.gc.pause') return timer(2, 0.25, 0.2);
  if (name === 'http.server.requests') return timer(9, 0.9, 0.3);
  return { measurements: [{ statistic: 'VALUE', value: 0 }] };
}
async function startActuatorMock({ status = 200, rootDelayMs = 0, names = rootNames } = {}) {
  const seen = [];
  const server = createServer(async (req, res) => {
    seen.push({ url: req.url, authorization: req.headers.authorization || '' });
    const url = new URL(req.url, 'http://127.0.0.1');
    if (status === 302) {
      res.writeHead(302, { Location: '/redirect-target' });
      return res.end();
    }
    if (status !== 200) return json(res, status, { error: 'denied' });
    if (url.pathname === '/actuator/metrics') {
      if (rootDelayMs) await delay(rootDelayMs);
      return json(res, 200, { names });
    }
    if (url.pathname === '/api/regions') return json(res, 200, { data: [] });
    const prefix = '/actuator/metrics/';
    if (url.pathname.startsWith(prefix)) return json(res, 200, metricFixture(decodeURIComponent(url.pathname.slice(prefix.length)), url.searchParams.get('tag')));
    return json(res, 404, {});
  });
  return { server, port: await listen(server), seen };
}

async function startMysqlMock() {
  const server = createServer((req, res) => {
    if (req.url === '/metrics') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      return res.end('mysql_up 1\n');
    }
    res.writeHead(404); res.end();
  });
  return { server, port: await listen(server) };
}

async function startPrometheusMock() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/-/ready') { res.writeHead(200); return res.end('ready'); }
    if (url.pathname === '/api/v1/query') return json(res, 200, { status: 'success', data: { result: [
      { metric: { job: 'audigo-backend', __name__: 'up' }, value: [1, '1'] },
      { metric: { job: 'audigo-backend', __name__: 'audigo_backend_up' }, value: [1, '1'] },
      { metric: { job: 'audigo-mysql', __name__: 'up' }, value: [1, '1'] },
      { metric: { job: 'audigo-mysql', __name__: 'mysql_up' }, value: [1, '1'] },
    ] } });
    if (url.pathname === '/api/v1/query_range') {
      const job = url.searchParams.get('query') === '{job="audigo-backend"}' ? 'audigo-backend' : 'audigo-mysql';
      const metric = job === 'audigo-backend' ? 'audigo_backend_up' : 'mysql_up';
      return json(res, 200, { status: 'success', data: { result: [
        { metric: { job, __name__: 'up' }, values: [[1, '1']] },
        { metric: { job, __name__: metric }, values: [[1, '1']] },
        ...(job === 'audigo-backend' ? [
          { metric: { job, __name__: 'audigo_backend_metric_available', metric: 'hikaricp.connections.active' }, values: [[1, '1']] },
          { metric: { job, __name__: 'audigo_backend_last_success_timestamp_seconds' }, values: [[1, '1']] },
        ] : []),
      ] } });
    }
    res.writeHead(404); res.end();
  });
  return { server, port: await listen(server) };
}

async function startGrafanaMock() {
  const server = createServer((req, res) => {
    if (req.url === '/api/health') return json(res, 200, { database: 'ok' });
    res.writeHead(404); res.end();
  });
  return { server, port: await listen(server) };
}

async function freePort() {
  const server = createServer();
  const port = await listen(server);
  await close(server);
  return port;
}

async function writeFakeDocker(temp) {
  const fakeBin = resolve(temp, 'fake-bin');
  await mkdir(fakeBin, { recursive: true });
  await writeFile(resolve(fakeBin, 'docker'), '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "${FAKE_DOCKER_LOG:?}"\nexit 0\n');
  await chmod(resolve(fakeBin, 'docker'), 0o755);
  return fakeBin;
}

async function writeFakeK6(temp) {
  const file = resolve(temp, 'fake-k6');
  await writeFile(file, `#!/usr/bin/env bash
if [[ "\${1:-}" == version ]]; then echo 'fake-k6 v0'; exit 0; fi
printf '%s\\n' "\$*" >> "\${FAKE_K6_LOG:?}"
trap 'exit 130' INT TERM
while :; do sleep 1; done
`);
  await chmod(file, 0o755);
  return file;
}

async function testBridgeHappyPath(temp) {
  const mock = await startActuatorMock({ rootDelayMs: 250 });
  const runtime = resolve(temp, 'bridge-runtime');
  const dataFile = resolve(temp, 'users.json');
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'test-token-only' }] }));
  const port = await new Promise(async (resolvePort) => {
    const probe = createServer(); const value = await listen(probe); await close(probe); resolvePort(value);
  });
  const bridge = runNode(bridgeFile, [], {
    CONFIRM_STAGING: 'true', BASE_URL: `http://127.0.0.1:${mock.port}`,
    TEST_DATA_FILE: dataFile, MONITORING_RUNTIME_DIR: runtime,
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_BRIDGE_PORT: String(port), ACCESS_TOKEN: '',
  });
  try {
    await waitFor('bridge listener', async () => (await get(`http://127.0.0.1:${port}/health`)).status === 503);
    await waitFor('successful bridge collection', async () => (await get(`http://127.0.0.1:${port}/health`)).status === 200);
    const metrics = await get(`http://127.0.0.1:${port}/metrics`);
    assert.equal(metrics.status, 200);
    assert.match(metrics.text, /^audigo_backend_up 1$/m);
    assert.match(metrics.text, /^audigo_backend_hikaricp_connections_active\{pool="TestPool"\} 3$/m);
    assert.match(metrics.text, /^audigo_backend_hikaricp_connections_acquire_seconds_count\{pool="TestPool"\} 5$/m);
    assert.match(metrics.text, /^audigo_backend_hikaricp_connections_acquire_seconds_sum\{pool="TestPool"\} 2$/m);
    assert.match(metrics.text, /^audigo_backend_hikaricp_connections_acquire_seconds_max\{pool="TestPool"\} 1$/m);
    assert.match(metrics.text, /^audigo_backend_hikaricp_connections_timeout_total 7$/m);
    assert.match(metrics.text, /^audigo_backend_jvm_memory_used_bytes\{area="heap"\} 100$/m);
    assert.match(metrics.text, /^audigo_backend_jvm_memory_used_bytes\{area="nonheap"\} 20$/m);
    assert.match(metrics.text, /^audigo_backend_jvm_gc_pause_seconds_count 2$/m);
    assert.match(metrics.text, /^audigo_backend_http_server_requests_seconds_sum 0.9$/m);
    assert.doesNotMatch(metrics.text, /quantile|test-token-only/i, 'bridge must not invent percentiles or leak the token');
    assert.ok(mock.seen.every((entry) => entry.authorization === 'Bearer test-token-only'), 'bridge should authenticate every Actuator request');
    console.log('✓ bridge exports discovered Hikari/JVM/timer metrics without token leakage');
  } finally {
    const result = await stop(bridge);
    await close(mock.server);
    const output = result.stdout + result.stderr;
    assert.doesNotMatch(output, /test-token-only/, 'bridge stdout/stderr must not leak tokens');
  }
}

async function testBridgeAuthFailure(temp) {
  const mock = await startActuatorMock({ status: 403 });
  const runtime = resolve(temp, 'auth-runtime');
  const dataFile = resolve(temp, 'auth-users.json');
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'not-a-secret' }] }));
  const portServer = createServer(); const port = await listen(portServer); await close(portServer);
  const bridge = runNode(bridgeFile, [], {
    CONFIRM_STAGING: 'true', BASE_URL: `http://127.0.0.1:${mock.port}`,
    TEST_DATA_FILE: dataFile, MONITORING_RUNTIME_DIR: runtime,
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_BRIDGE_PORT: String(port), ACCESS_TOKEN: '',
  });
  try {
    await waitFor('403 bridge health', async () => (await get(`http://127.0.0.1:${port}/health`)).status === 503);
    const health = JSON.parse((await get(`http://127.0.0.1:${port}/health`)).text);
    assert.equal(health.ready, false);
    assert.equal(health.authenticationFailed, true);
    const journal = await readFile(resolve(runtime, 'collection-errors.jsonl'), 'utf8');
    assert.match(journal, /"kind":"authentication"/);
    assert.doesNotMatch(journal, /not-a-secret/);
    console.log('✓ bridge reports 403 as a non-ready authentication failure without token leakage');
  } finally {
    await stop(bridge);
    await close(mock.server);
  }
}

async function testBridgeRedirectFailure(temp) {
  const mock = await startActuatorMock({ status: 302 });
  const runtime = resolve(temp, 'redirect-runtime');
  const dataFile = resolve(temp, 'redirect-users.json');
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'redirect-test-token' }] }));
  const portServer = createServer(); const port = await listen(portServer); await close(portServer);
  const bridge = runNode(bridgeFile, [], {
    CONFIRM_STAGING: 'true', BASE_URL: `http://127.0.0.1:${mock.port}`,
    TEST_DATA_FILE: dataFile, MONITORING_RUNTIME_DIR: runtime,
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_BRIDGE_PORT: String(port), ACCESS_TOKEN: '',
  });
  try {
    await waitFor('redirect bridge health failure', async () => (await get(`http://127.0.0.1:${port}/health`)).status === 503);
    const journal = await readFile(resolve(runtime, 'collection-errors.jsonl'), 'utf8');
    assert.match(journal, /"kind":"collection"/);
    assert.equal(mock.seen.length, 1, 'redirect must not trigger a second request');
    console.log('✓ bridge rejects redirecting Actuator responses and records a local error event');
  } finally {
    await stop(bridge);
    await close(mock.server);
  }
}

async function testBridgeMissingMetricCoverage(temp) {
  const mock = await startActuatorMock({ names: ['hikaricp.connections.active'] });
  const runtime = resolve(temp, 'missing-runtime');
  const dataFile = resolve(temp, 'missing-users.json');
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'missing-local-token' }] }));
  const port = await freePort();
  const bridge = runNode(bridgeFile, [], {
    CONFIRM_STAGING: 'true', BASE_URL: `http://127.0.0.1:${mock.port}`,
    TEST_DATA_FILE: dataFile, MONITORING_RUNTIME_DIR: runtime,
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_BRIDGE_PORT: String(port), ACCESS_TOKEN: '',
  });
  try {
    await waitFor('bridge readiness with partial metric index', async () => (await get(`http://127.0.0.1:${port}/health`)).status === 200);
    const health = JSON.parse((await get(`http://127.0.0.1:${port}/health`)).text);
    assert.equal(health.ready, true, 'available metrics should remain scrapeable when optional meters are absent');
    assert.ok(health.unavailableMetrics.includes('jvm.gc.pause'));
    const metrics = (await get(`http://127.0.0.1:${port}/metrics`)).text;
    assert.match(metrics, /^audigo_backend_metric_available\{metric="hikaricp\.connections\.active"\} 1$/m);
    assert.match(metrics, /^audigo_backend_metric_available\{metric="jvm\.gc\.pause"\} 0$/m);
    const journal = await readFile(resolve(runtime, 'collection-errors.jsonl'), 'utf8');
    assert.match(journal, /"kind":"missing_metric"/);
    assert.doesNotMatch(journal, /missing-local-token/);
    console.log('✓ bridge keeps available metrics ready while exposing missing-meter coverage explicitly');
  } finally {
    await stop(bridge);
    await close(mock.server);
  }
}

async function testExportPartialFailure(temp) {
  const requests = [];
  const prom = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    requests.push(url);
    const query = url.searchParams.get('query');
    if (query === '{job="audigo-backend"}') return json(res, 200, { status: 'success', data: { result: [
      { metric: { __name__: 'up', job: 'audigo-backend' }, values: [[1, '1']] },
      { metric: { __name__: 'audigo_backend_up', job: 'audigo-backend' }, values: [[1, '1']] },
      { metric: { __name__: 'audigo_backend_hikaricp_connections_active', job: 'audigo-backend' }, values: [[1, '3']] },
      { metric: { __name__: 'audigo_backend_metric_available', job: 'audigo-backend', metric: 'hikaricp.connections.active' }, values: [[1, '1']] },
      { metric: { __name__: 'audigo_backend_last_success_timestamp_seconds', job: 'audigo-backend' }, values: [[1, '1']] },
    ] } });
    res.writeHead(500, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ status: 'error' }));
  });
  const port = await listen(prom);
  const runtime = resolve(temp, 'export-runtime');
  await mkdir(runtime, { recursive: true });
  await writeFile(resolve(runtime, 'collection-errors.jsonl'), JSON.stringify({ timestamp: '2026-01-01T00:00:03.000Z', source: 'backend', kind: 'http', message: 'HTTP 500' }) + '\n');
  const runDir = resolve(temp, 'result');
  const exportRun = runNode(exportFile, ['--run-dir', runDir, '--start', '2026-01-01T00:00:00.000Z', '--end', '2026-01-01T00:00:10.000Z'], {
    MONITORING_PROMETHEUS_URL: `http://127.0.0.1:${port}`,
    MONITORING_RUNTIME_DIR: runtime,
    MONITORING_EXPORT_STEP_SECONDS: '5',
  });
  try {
    const result = await exportRun.done;
    assert.equal(result.code, 2, result.stderr);
    const output = resolve(runDir, 'monitoring');
    const [backend, mysql, errorsText, summary] = await Promise.all([
      'backend-series.json', 'mysql-series.json', 'collection-errors.jsonl', 'summary.json',
    ].map(async (name) => readFile(resolve(output, name), 'utf8')));
    const errors = errorsText.split('\n').filter(Boolean).map((line) => JSON.parse(line));
    const backendJson = JSON.parse(backend);
    const mysqlJson = JSON.parse(mysql);
    const summaryJson = JSON.parse(summary);
    assert.equal(backendJson.status, 'success');
    assert.equal(mysqlJson.status, 'error');
    assert.equal(summaryJson.complete, false);
    assert.equal(summaryJson.sources.backend.incomplete, false);
    assert.equal(summaryJson.sources.mysql.incomplete, true);
    assert.ok(errors.some((entry) => entry.source === 'mysql' && entry.kind === 'export'));
    assert.ok(requests.length === 2 && requests.every((url) => url.pathname === '/api/v1/query_range'), 'export must use Prometheus query_range for both sources');
    assert.ok(requests.every((url) => url.searchParams.get('step') === '5' && url.searchParams.get('timeout') === '10s'));
    console.log('✓ export preserves backend output and records a MySQL query gap on partial failure');
  } finally { await close(prom); }
}

async function testExportRejectsUnknownCoverage(temp) {
  const prom = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const backend = url.searchParams.get('query') === '{job="audigo-backend"}';
    const job = backend ? 'audigo-backend' : 'audigo-mysql';
    const health = backend ? 'audigo_backend_up' : 'mysql_up';
    return json(res, 200, { status: 'success', data: { result: [
      { metric: { __name__: 'up', job }, values: [[1, '1']] },
      { metric: { __name__: health, job }, values: [[1, '1']] },
      // Deliberately omit audigo_backend_metric_available from Backend only.
    ] } });
  });
  const port = await listen(prom);
  const runtime = resolve(temp, 'coverage-runtime');
  const runDir = resolve(temp, 'coverage-result');
  const exportRun = runNode(exportFile, ['--run-dir', runDir, '--start', '2026-01-01T00:00:00.000Z', '--end', '2026-01-01T00:00:10.000Z'], {
    MONITORING_PROMETHEUS_URL: `http://127.0.0.1:${port}`, MONITORING_RUNTIME_DIR: runtime,
  });
  try {
    const result = await exportRun.done;
    assert.equal(result.code, 2, result.stderr);
    const summary = JSON.parse(await readFile(resolve(runDir, 'monitoring/summary.json'), 'utf8'));
    assert.equal(summary.complete, false);
    assert.equal(summary.sources.backend.coverageUnknown, true);
    assert.equal(summary.sources.backend.incomplete, true);
    assert.equal(summary.sources.mysql.incomplete, false);
    console.log('✓ export marks historical Backend data incomplete when metric-availability coverage is absent');
  } finally { await close(prom); }
}

async function testMonitorPrepareAndRunSafety(temp) {
  const actuator = await startActuatorMock();
  const mysql = await startMysqlMock();
  const prometheus = await startPrometheusMock();
  const grafana = await startGrafanaMock();
  const fakeBin = await writeFakeDocker(temp);
  const runtime = resolve(temp, 'monitor-runtime');
  const dataFile = resolve(temp, 'monitor-users.json');
  const dockerLog = resolve(temp, 'fake-docker.log');
  const bridgePort = await freePort();
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'monitor-local-token' }] }));
  const env = {
    MONITORING_SKIP_DOTENV: 'true', CONFIRM_STAGING: 'true',
    BASE_URL: `http://127.0.0.1:${actuator.port}`, TEST_DATA_FILE: dataFile,
    MONITORING_RUNTIME_DIR: runtime, MONITORING_BRIDGE_PORT: String(bridgePort),
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_READY_TIMEOUT_SECONDS: '5',
    MONITORING_PROMETHEUS_URL: `http://127.0.0.1:${prometheus.port}`,
    MONITORING_GRAFANA_URL: `http://127.0.0.1:${grafana.port}`,
    MONITORING_MYSQL_URL: `http://127.0.0.1:${mysql.port}`,
    FAKE_DOCKER_LOG: dockerLog, PATH: `${fakeBin}:${process.env.PATH}`,
  };
  try {
    const prepare = await runShell(monitorShell, ['prepare'], env).done;
    assert.equal(prepare.code, 0, prepare.stdout + prepare.stderr);
    assert.match(prepare.stdout, /Monitoring readiness passed/);
    assert.equal((await readFile(resolve(runtime, 'targets/backend.json'), 'utf8')).trim(), '[]');
    assert.equal((await readFile(resolve(runtime, 'targets/mysql.json'), 'utf8')).trim(), '[]');
    assert.match(await readFile(dockerLog, 'utf8'), /compose .* up -d/);
    assert.doesNotMatch(prepare.stdout + prepare.stderr, /monitor-local-token/);

    const requestCount = actuator.seen.length;
    const already = 'monitoring-existing-result';
    await mkdir(resolve(loadTestRoot, 'results', already), { recursive: true });
    try {
      const collision = await runShell(monitorShell, ['run', 'scenarios/api/itinerary-ramping-arrival.js'], { ...env, TEST_RUN_ID: already }).done;
      assert.notEqual(collision.code, 0, 'run must refuse an existing result directory');
      assert.match(collision.stderr, /Result directory already exists/);
      assert.equal(actuator.seen.length, requestCount, 'collision must fail before any backend collection/load request');
    } finally {
      await rm(resolve(loadTestRoot, 'results', already), { recursive: true, force: true });
    }

    const reset = await runShell(monitorShell, ['run', '--reset', 'scenarios/api/itinerary-ramping-arrival.js'], env).done;
    assert.notEqual(reset.code, 0, 'reset mode requires explicit write/reset guards');
    assert.match(reset.stderr, /--reset requires CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true/);
    assert.equal(actuator.seen.length, requestCount, 'unsafe reset must fail before any backend request');
    console.log('✓ monitor prepare pauses file-SD targets and run safety errors occur before Staging requests');
  } finally {
    // The command's own cleanup should remove bridge ownership; this extra stop is local-only best effort.
    await runShell(monitorShell, ['stop'], env).done.catch(() => undefined);
    await close(actuator.server);
    await close(mysql.server);
    await close(prometheus.server);
    await close(grafana.server);
  }
}

async function testMonitorInterruptCleanup(temp) {
  const actuator = await startActuatorMock();
  const mysql = await startMysqlMock();
  const prometheus = await startPrometheusMock();
  const grafana = await startGrafanaMock();
  const fakeBin = await writeFakeDocker(temp);
  const fakeK6 = await writeFakeK6(temp);
  const runtime = resolve(temp, 'interrupt-runtime');
  const dataFile = resolve(temp, 'interrupt-users.json');
  const dockerLog = resolve(temp, 'interrupt-docker.log');
  const k6Log = resolve(temp, 'interrupt-k6.log');
  const bridgePort = await freePort();
  const runId = `monitor-interrupt-${process.pid}-${Date.now()}`;
  const result = resolve(loadTestRoot, 'results', runId);
  await writeFile(dataFile, JSON.stringify({ users: [{ accessToken: 'interrupt-local-token' }] }));
  const env = {
    MONITORING_SKIP_DOTENV: 'true', CONFIRM_STAGING: 'true',
    BASE_URL: `http://127.0.0.1:${actuator.port}`, TEST_DATA_FILE: dataFile,
    MONITORING_RUNTIME_DIR: runtime, MONITORING_BRIDGE_PORT: String(bridgePort),
    MONITORING_INTERVAL_SECONDS: '1', MONITORING_REQUEST_TIMEOUT_MS: '1000',
    MONITORING_READY_TIMEOUT_SECONDS: '5', MONITORING_PRE_SECONDS: '0', MONITORING_POST_SECONDS: '0',
    MONITORING_PROMETHEUS_URL: `http://127.0.0.1:${prometheus.port}`,
    MONITORING_GRAFANA_URL: `http://127.0.0.1:${grafana.port}`,
    MONITORING_MYSQL_URL: `http://127.0.0.1:${mysql.port}`,
    FAKE_DOCKER_LOG: dockerLog, FAKE_K6_LOG: k6Log, K6_BIN: fakeK6,
    K6_WEB_DASHBOARD: 'false', TEST_RUN_ID: runId, PATH: `${fakeBin}:${process.env.PATH}`,
  };
  try {
    const run = runShell(monitorShell, ['run', 'scenarios/api/itinerary-ramping-arrival.js'], env);
    await waitFor('fake k6 start', async () => (await readFile(k6Log, 'utf8')).includes('run'));
    run.child.kill('SIGINT');
    const resultProcess = await Promise.race([
      run.done,
      delay(10_000).then(() => { throw new Error('monitor run did not finish after SIGINT'); }),
    ]);
    assert.equal(resultProcess.code, 130, resultProcess.stdout + resultProcess.stderr);
    assert.equal((await readFile(resolve(runtime, 'targets/backend.json'), 'utf8')).trim(), '[]');
    assert.equal((await readFile(resolve(runtime, 'targets/mysql.json'), 'utf8')).trim(), '[]');
    const runMeta = JSON.parse(await readFile(resolve(result, 'monitoring/run.json'), 'utf8'));
    assert.equal(runMeta.interrupted, true);
    assert.equal(runMeta.executionExitCode, 130, 'runner process group should receive the forwarded SIGINT');
    assert.match(await readFile(resolve(result, 'monitoring/summary.json'), 'utf8'), /"complete": true/);
    assert.doesNotMatch(resultProcess.stdout + resultProcess.stderr, /interrupt-local-token/);
    console.log('✓ monitor forwards SIGINT, exports partial-time metrics, and disables file-SD targets during cleanup');
  } finally {
    await runShell(monitorShell, ['stop'], env).done.catch(() => undefined);
    await rm(result, { recursive: true, force: true });
    await close(actuator.server);
    await close(mysql.server);
    await close(prometheus.server);
    await close(grafana.server);
  }
}

async function testProductionGuard() {
  const { backendConfig, validateStagingUrl } = await import(commonFile);
  assert.throws(() => validateStagingUrl('https://api.audigo.kr'), /Production Audigo hosts are blocked/);
  assert.throws(() => validateStagingUrl('https://audigo.kr'), /Production Audigo hosts are blocked/);
  assert.throws(() => validateStagingUrl('https://staging.audigo.kr/path'), /BASE_URL must be an HTTP\(S\) origin/);
  assert.equal(validateStagingUrl('http://127.0.0.1:19105'), 'http://127.0.0.1:19105');
  const saved = Object.fromEntries(['CONFIRM_STAGING', 'BASE_URL', 'MONITORING_BACKEND_URL', 'MONITORING_ALLOW_INSECURE_HTTP', 'ACCESS_TOKEN'].map((name) => [name, process.env[name]]));
  try {
    Object.assign(process.env, { CONFIRM_STAGING: 'true', BASE_URL: 'http://staging.example.test', ACCESS_TOKEN: 'local-test-token' });
    delete process.env.MONITORING_BACKEND_URL;
    delete process.env.MONITORING_ALLOW_INSECURE_HTTP;
    assert.throws(() => backendConfig(), /Remote HTTP exposes the Staging JWT/);
    process.env.MONITORING_ALLOW_INSECURE_HTTP = 'true';
    assert.equal(backendConfig().baseUrl, 'http://staging.example.test');
    process.env.BASE_URL = 'https://staging.example.test';
    process.env.MONITORING_BACKEND_URL = 'http://127.0.0.1:18080';
    delete process.env.MONITORING_ALLOW_INSECURE_HTTP;
    assert.equal(backendConfig().baseUrl, 'http://127.0.0.1:18080');
    process.env.MONITORING_BACKEND_URL = 'https://other.example.test';
    assert.throws(() => backendConfig(), /may only override with a loopback SSH tunnel/);
  } finally {
    for (const [name, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[name]; else process.env[name] = value;
    }
  }
  console.log('✓ production and unencrypted remote HTTP are rejected without an HTTP request');
}

const temp = await mkdtemp(resolve(tmpdir(), 'audigo-monitoring-local-smoke-'));
try {
  await testProductionGuard();
  await testBridgeHappyPath(temp);
  await testBridgeAuthFailure(temp);
  await testBridgeRedirectFailure(temp);
  await testBridgeMissingMetricCoverage(temp);
  await testExportPartialFailure(temp);
  await testExportRejectsUnknownCoverage(temp);
  await testMonitorPrepareAndRunSafety(temp);
  await testMonitorInterruptCleanup(temp);
  console.log('Monitoring local smoke passed.');
} finally {
  await rm(temp, { recursive: true, force: true });
}
