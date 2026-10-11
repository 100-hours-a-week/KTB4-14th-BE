#!/usr/bin/env node
import { createServer } from 'node:http';
import { appendFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { backendConfig, runtimeDir } from './common.mjs';

const definitions = [
  ['hikaricp.connections', 'hikaricp_connections_total', 'gauge', 'pool'],
  ...['active', 'idle', 'pending', 'max', 'min'].map((s) => ['hikaricp.connections.' + s, 'hikaricp_connections_' + s, 'gauge', 'pool']),
  ...['acquire', 'usage', 'creation'].map((s) => ['hikaricp.connections.' + s, 'hikaricp_connections_' + s + '_seconds', 'timer', 'pool']),
  ['hikaricp.connections.timeout', 'hikaricp_connections_timeout_total', 'counter', 'pool'],
  ...['used', 'committed', 'max'].map((s) => ['jvm.memory.' + s, 'jvm_memory_' + s + '_bytes', 'gauge', 'area']),
  ['jvm.gc.pause', 'jvm_gc_pause_seconds', 'timer'],
  ...['live', 'daemon', 'peak'].map((s) => ['jvm.threads.' + s, 'jvm_threads_' + s, 'gauge']),
  ['http.server.requests', 'http_server_requests_seconds', 'timer'],
];
const escaped = (value) => String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
function sample(payload, name, kind, labels) {
  const suffix = Object.keys(labels).length ? '{' + Object.entries(labels).map(([k,v]) => k + '="' + escaped(v) + '"').join(',') + '}' : '';
  const stats = new Map((payload.measurements || []).map((m) => [m.statistic, m.value]));
  const required = kind === 'timer' ? ['COUNT','TOTAL_TIME','MAX'] : [kind === 'counter' ? 'COUNT' : 'VALUE'];
  if (required.some((key) => !Number.isFinite(stats.get(key)))) throw new Error('Metric statistic unavailable');
  const lines = [];
  const emit = (metric, type, value) => {
    if (!Number.isFinite(value)) return;
    lines.push('# TYPE ' + metric + ' ' + type, metric + suffix + ' ' + value);
  };
  const prefix = 'audigo_backend_' + name;
  if (kind === 'timer') {
    // Actuator/Micrometer timers are seconds; refuse incompatible units.
    if (payload.baseUnit && payload.baseUnit !== 'seconds') throw new Error('Unsupported timer unit');
    emit(prefix + '_count', 'counter', stats.get('COUNT'));
    emit(prefix + '_sum', 'counter', stats.get('TOTAL_TIME'));
    emit(prefix + '_max', 'gauge', stats.get('MAX'));
  } else emit(prefix, kind, stats.get(kind === 'counter' ? 'COUNT' : 'VALUE'));
  return lines;
}

async function main() {
  const config = backendConfig(); // safety/auth configuration validated before any request
  const dir = runtimeDir();
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const abort = new AbortController();
  const state = { ready: false, authFailed: false, lines: [], names: null, tags: new Map(), errors: new Map(), lastSuccess: 0, lastAttempt: 0, stopped: false };
  async function metricRequest(name, tag) {
    const url = new URL(config.baseUrl + '/actuator/metrics' + (name ? '/' + encodeURIComponent(name) : ''));
    if (tag) url.searchParams.set('tag', tag[0] + ':' + tag[1]);
    const response = await fetch(url, {
      headers: { Authorization: 'Bearer ' + config.token, 'X-Test-Scenario': 'MONITORING' },
      signal: AbortSignal.any([AbortSignal.timeout(config.timeout), abort.signal]), redirect: 'error',
    });
    if (!response.ok) {
      if ([401, 403].includes(response.status)) state.authFailed = true;
      throw new Error('HTTP ' + response.status);
    }
    return response.json();
  }
  function record(error, metric) {
    const message = /^HTTP \d+$/.test(error.message) || ['Unsupported timer unit', 'Metric unavailable', 'Metric statistic unavailable'].includes(error.message)
      ? error.message : error.name === 'TimeoutError' || error.name === 'AbortError' ? 'Request timeout' : 'Request/JSON failure (check network or redirect)';
    const kind = message.includes('unavailable') ? 'missing_metric' : state.authFailed ? 'authentication' : message.startsWith('HTTP ') ? 'http' : message.includes('timeout') ? 'timeout' : 'collection';
    state.errors.set(kind, (state.errors.get(kind) || 0) + 1);
    appendFileSync(resolve(dir, 'collection-errors.jsonl'), JSON.stringify({ timestamp: new Date().toISOString(), source: 'backend', kind, metric, message }) + '\n', { mode: 0o600 });
    console.error('Backend monitoring: ' + metric + ': ' + message + (state.authFailed ? '; refresh Staging JWT and prepare again' : ''));
  }
  async function collect() {
    if (state.authFailed || state.stopped) return;
    state.lastAttempt = Date.now() / 1000;
    const lines = [];
    let failures = 0;
    try {
      if (!state.names) {
        const root = await metricRequest();
        if (!Array.isArray(root.names)) throw new Error('Invalid metrics index');
        state.names = new Set(root.names);
        for (const [name] of definitions) if (!state.names.has(name)) record(new Error('Metric unavailable'), name);
      }
    } catch (error) {
      record(error, 'metrics-index'); state.ready = false; state.lines = []; return;
    }
    for (const [source, target, kind, tagKey] of definitions) {
      if (!state.names.has(source) || state.authFailed || state.stopped) continue;
      try {
        if (!tagKey) {
          lines.push(...sample(await metricRequest(source), target, kind, {}));
          continue;
        }
        if (!state.tags.has(source)) {
          const payload = await metricRequest(source);
          let values = payload.availableTags?.find((t) => t.tag === tagKey)?.values || [];
          if (tagKey === 'area') values = values.filter((v) => ['heap', 'nonheap'].includes(v));
          if (values.length > 20) throw new Error('Too many monitoring tag values');
          state.tags.set(source, values);
          if (!values.length) { lines.push(...sample(payload, target, kind, {})); continue; }
        }
        const values = state.tags.get(source);
        if (!values.length) lines.push(...sample(await metricRequest(source), target, kind, {}));
        for (const value of values) {
          if (state.stopped || state.authFailed) break;
          lines.push(...sample(await metricRequest(source, [tagKey, value]), target, kind, { [tagKey]: value }));
        }
      } catch (error) { failures++; record(error, source); }
    }
    state.ready = failures === 0 && !state.authFailed && lines.length > 0;
    state.lines = state.ready ? lines : [];
    if (state.ready) state.lastSuccess = Date.now() / 1000;
  }
  function metrics() {
    const fresh = state.ready && Date.now() / 1000 - state.lastSuccess <= Math.max(config.interval / 1000 * 3, 15);
    // Strip repeated TYPE declarations across tagged samples.
    const seen = new Set();
    const lines = state.lines.filter((line) => !line.startsWith('# TYPE ') || !seen.has(line) && !!seen.add(line));
    return [
      '# TYPE audigo_backend_up gauge', 'audigo_backend_up ' + Number(fresh),
      '# TYPE audigo_backend_last_success_timestamp_seconds gauge', 'audigo_backend_last_success_timestamp_seconds ' + state.lastSuccess,
      '# TYPE audigo_backend_last_attempt_timestamp_seconds gauge', 'audigo_backend_last_attempt_timestamp_seconds ' + state.lastAttempt,
      '# TYPE audigo_backend_collection_errors_total counter',
      ...[...state.errors].map(([reason,count]) => 'audigo_backend_collection_errors_total{reason="' + reason + '"} ' + count),
      '# TYPE audigo_backend_metric_available gauge',
      ...definitions.map(([name]) => 'audigo_backend_metric_available{metric="' + name + '"} ' + Number(state.names?.has(name) || false)),
      ...lines, '',
    ].join('\n');
  }
  if (process.argv.includes('--probe')) {
    await collect();
    if (!state.ready) throw new Error(state.authFailed ? 'Staging authentication failed (401/403); no load started' : 'Backend metric probe failed; see collection-errors.jsonl');
    console.log('Backend metric probe passed');
    return;
  }
  const server = createServer((req, res) => {
    if (req.url === '/metrics') {
      res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' }); res.end(metrics());
    } else if (req.url === '/health') {
      res.writeHead(state.ready ? 200 : 503, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ready: state.ready, authenticationFailed: state.authFailed, lastSuccess: state.lastSuccess, unavailableMetrics: definitions.filter(([name]) => !state.names?.has(name)).map(([name]) => name), pid: process.pid, instanceId: process.env.MONITORING_INSTANCE_ID || '' }));
    } else { res.writeHead(404); res.end(); }
  });
  await new Promise((yes, no) => { server.once('error', no); server.listen(config.port, '127.0.0.1', yes); });
  console.log('Monitoring bridge listening at http://127.0.0.1:' + config.port);
  let wake;
  const stop = () => { state.stopped = true; abort.abort(); if (wake) wake(); server.close(); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
  while (!state.stopped) {
    const started = Date.now();
    await collect();
    if (!state.stopped) await new Promise((yes) => { const timer = setTimeout(yes, Math.max(0, config.interval - (Date.now() - started))); wake = () => { clearTimeout(timer); yes(); }; });
  }
}
main().catch((error) => { console.error('Monitoring bridge failed: ' + error.message); process.exitCode = 1; });
