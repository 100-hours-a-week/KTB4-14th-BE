#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fetchJson, localUrl, runtimeDir, positiveNumber } from './common.mjs';

function arg(name) { const i = process.argv.indexOf(name); if (i < 0 || !process.argv[i + 1]) throw new Error(name + ' is required'); return process.argv[i + 1]; }
async function main() {
  const dir = resolve(arg('--run-dir'), 'monitoring');
  const start = Date.parse(arg('--start')) / 1000;
  const end = Date.parse(arg('--end')) / 1000;
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || end - start > 86400) throw new Error('Invalid monitoring time range (maximum 24h)');
  const base = localUrl(process.env.MONITORING_PROMETHEUS_URL || 'http://127.0.0.1:19090');
  const step = positiveNumber('MONITORING_EXPORT_STEP_SECONDS', 5, 300);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const summary = { start: new Date(start * 1000).toISOString(), end: new Date(end * 1000).toISOString(), stepSeconds: step, exportedAt: new Date().toISOString(), sources: {}, collectionErrorCount: 0 };
  const errors = [];
  const notStarted = process.argv.includes('--collection-not-started');
  for (const source of ['backend', 'mysql']) {
    const url = new URL(base + '/api/v1/query_range');
    url.search = new URLSearchParams({ query: '{job="audigo-' + source + '"}', start: String(start), end: String(end), step: String(step), timeout: '10s' });
    let data;
    try {
      if (notStarted) throw new Error('Collection did not become ready');
      data = await fetchJson(url);
      if (data.status !== 'success' || !Array.isArray(data.data?.result)) throw new Error('Prometheus query failed');
      const series = data.data.result;
      const samples = series.reduce((n,s) => n + (s.values?.length || 0), 0);
      const healthNames = source === 'backend' ? ['up','audigo_backend_up'] : ['up','mysql_up'];
      const hasMissingHealth = healthNames.some((name) => !series.some((s) => s.metric.__name__ === name));
      const badHealth = series.filter((s) => healthNames.includes(s.metric.__name__)).some((s) => s.values?.some(([,v]) => Number(v) !== 1));
      const availability = series.filter((s) => s.metric.__name__ === 'audigo_backend_metric_available');
      const unavailableMetrics = availability.filter((s) => s.values?.some(([,v]) => Number(v) !== 1)).map((s) => s.metric.metric);
      const coverageUnknown = source === 'backend' && !availability.length;
      const lastSuccess = series.find((s) => s.metric.__name__ === 'audigo_backend_last_success_timestamp_seconds');
      const staleSamples = (lastSuccess?.values || []).filter(([t,v]) => Number(t) - Number(v) > Math.max(Number(process.env.MONITORING_INTERVAL_SECONDS || 5) * 3, 15)).length;
      summary.sources[source] = { series: series.length, samples, unavailableMetrics, coverageUnknown, staleSamples, incomplete: !samples || hasMissingHealth || badHealth || coverageUnknown || !!unavailableMetrics.length || staleSamples > 0 };
    } catch {
      data = { status: 'error', error: notStarted ? 'Collection did not become ready; no previous-run series queried' : 'Monitoring query failed; no data fabricated' };
      summary.sources[source] = { series: 0, samples: 0, incomplete: true };
      errors.push({ timestamp: new Date().toISOString(), source, kind: 'export', message: 'Prometheus query failed' });
    }
    writeFileSync(resolve(dir, source + '-series.json'), JSON.stringify(data, null, 2) + '\n', { mode: 0o600 });
  }
  try {
    const journal = readFileSync(resolve(runtimeDir(), 'collection-errors.jsonl'), 'utf8');
    for (const line of journal.split('\n').filter(Boolean)) {
      try { const e = JSON.parse(line); const t = Date.parse(e.timestamp) / 1000; if (t >= start && t <= end) errors.push(e); } catch { /* malformed final line from interruption */ }
    }
  } catch (e) { if (e.code !== 'ENOENT') errors.push({ timestamp: new Date().toISOString(), source: 'collector', kind: 'export', message: 'Could not read error journal' }); }
  summary.collectionErrorCount = errors.length;
  summary.complete = !errors.length && Object.values(summary.sources).every((s) => !s.incomplete);
  summary.note = 'Snapshots, not a Grafana import format. Use persistent Prometheus volume and the UTC time range. No percentile can be inferred from timer count/sum/max.';
  writeFileSync(resolve(dir, 'collection-errors.jsonl'), errors.map((e) => JSON.stringify(e) + '\n').join(''), { mode: 0o600 });
  writeFileSync(resolve(dir, 'summary.json'), JSON.stringify(summary, null, 2) + '\n', { mode: 0o600 });
  console.log('Monitoring exported: ' + dir + '; complete=' + summary.complete);
  if (!summary.complete) process.exitCode = 2;
}
main().catch((e) => { console.error('Monitoring export failed: ' + e.message); process.exitCode = 1; });
