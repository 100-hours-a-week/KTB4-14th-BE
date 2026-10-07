#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function fail(message) {
    console.error(`Staging authentication preflight failed: ${message}`);
    process.exit(1);
}

if (process.env.CONFIRM_STAGING !== 'true') fail('CONFIRM_STAGING=true is required');
const baseUrl = process.env.BASE_URL?.replace(/\/$/, '');
if (!baseUrl) fail('BASE_URL is required');
let parsedUrl;
try {
    parsedUrl = new URL(baseUrl);
} catch {
    fail(`BASE_URL must be an absolute URL: ${baseUrl}`);
}
if (parsedUrl.hostname.toLowerCase().replace(/\.+$/, '') === 'api.audigo.kr') fail('production BASE_URL is blocked');

const dataPath = resolve(process.cwd(), process.env.TEST_DATA_FILE || 'data/test-ids.json');
let users;
try {
    users = JSON.parse(readFileSync(dataPath, 'utf8')).users;
} catch (error) {
    fail(`could not read ${dataPath}: ${error.message}`);
}
const user = users?.find((entry) => typeof entry?.accessToken === 'string' && entry.accessToken.length > 0);
if (!user) fail(`${dataPath} has no user accessToken`);

let response;
try {
    response = await fetch(`${baseUrl}/api/regions`, {
        headers: {
            Authorization: `Bearer ${user.accessToken}`,
            'X-Test-Scenario': 'AUTH-PREFLIGHT',
            'X-Test-Run-Id': process.env.TEST_RUN_ID || `auth-preflight-${Date.now()}`,
        },
        signal: AbortSignal.timeout(10_000),
    });
} catch (error) {
    fail(`could not reach Staging: ${error.cause?.message || error.message}`);
}
if (response.status === 401 || response.status === 403) {
    fail(`HTTP ${response.status}; regenerate the Staging JWT with the current secret and verify Staging authorization. No k6 load was started.`);
}
if (!response.ok) fail(`GET /api/regions returned HTTP ${response.status}; no k6 load was started.`);
console.log(`Staging authentication preflight passed: HTTP ${response.status}.`);
