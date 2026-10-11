import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const runtimeDir = () => resolve(process.env.MONITORING_RUNTIME_DIR || resolve(ROOT, 'monitoring/runtime'));
export const localUrl = (value) => {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.search || url.hash) {
    throw new Error('Monitoring service URLs must use loopback HTTP without credentials/query/fragment');
  }
  return url.origin;
};
export function validateStagingUrl(value) {
  const url = new URL(value);
  const host = url.hostname.toLowerCase().replace(/\.+$/, '');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) {
    throw new Error('BASE_URL must be an HTTP(S) origin without credentials/query/fragment');
  }
  if (host === 'audigo.kr' || host.endsWith('.audigo.kr') && host !== 'staging.audigo.kr') {
    throw new Error('Production Audigo hosts are blocked; use the confirmed Staging address');
  }
  return url.origin;
}
export function positiveNumber(name, fallback, max = 3600) {
  const value = Number(process.env[name] || fallback);
  if (!Number.isFinite(value) || value <= 0 || value > max) throw new Error(name + ' must be >0 and <=' + max);
  return value;
}
export function backendConfig() {
  if (process.env.CONFIRM_STAGING !== 'true') throw new Error('CONFIRM_STAGING=true is required');
  const stagingUrl = validateStagingUrl(process.env.BASE_URL);
  const baseUrl = validateStagingUrl(process.env.MONITORING_BACKEND_URL || stagingUrl);
  const parsed = new URL(baseUrl);
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname);
  if (baseUrl !== stagingUrl && !loopback) throw new Error('MONITORING_BACKEND_URL may only override with a loopback SSH tunnel');
  if (parsed.protocol === 'http:' && !loopback && process.env.MONITORING_ALLOW_INSECURE_HTTP !== 'true') {
    throw new Error('Remote HTTP exposes the Staging JWT. Use HTTPS/Backend SSH tunnel, or explicitly set MONITORING_ALLOW_INSECURE_HTTP=true for isolated test credentials');
  }
  let token = process.env.ACCESS_TOKEN?.trim();
  if (!token) {
    const file = resolve(ROOT, process.env.TEST_DATA_FILE || 'data/test-ids.json');
    let users;
    try { users = JSON.parse(readFileSync(file, 'utf8')).users; } catch { throw new Error('Could not read valid test-ids JSON; credentials are not logged'); }
    token = users?.find((u) => typeof u?.accessToken === 'string' && u.accessToken.trim())?.accessToken.trim();
  }
  if (!token || token.startsWith('Bearer ') || /[\r\n]/.test(token)) throw new Error('A raw Staging access token is required in ACCESS_TOKEN or test-ids.json');
  return {
    baseUrl, token,
    interval: positiveNumber('MONITORING_INTERVAL_SECONDS', 5) * 1000,
    timeout: positiveNumber('MONITORING_REQUEST_TIMEOUT_MS', 3000, 30000),
    port: positiveNumber('MONITORING_BRIDGE_PORT', 19105, 65535),
  };
}
export async function fetchJson(url, options = {}, timeout = 12000) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(timeout) });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  return response.json();
}
