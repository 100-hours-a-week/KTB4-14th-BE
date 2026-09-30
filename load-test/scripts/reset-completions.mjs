#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ROOT_DIR = resolve(import.meta.dirname, '..');
const DEFAULT_DELAY_MS = 100;

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_COMPLETION_RESET=true '
        + 'node scripts/reset-completions.mjs '
        + '[--data data/test-ids.json] [--user-id <id> | --count <n> | --summary <file>] '
        + '[--delay-ms 100]'
    );
    process.exit(1);
}

function nonNegativeInteger(value, label) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < 0) usage(`${label} must be a non-negative integer`);
    return number;
}

function positiveInteger(value, label) {
    const number = nonNegativeInteger(value, label);
    if (number === 0) usage(`${label} must be greater than zero`);
    return number;
}

function readArguments(argumentsList) {
    const options = {
        data: resolve(ROOT_DIR, 'data/test-ids.json'),
        delayMs: nonNegativeInteger(process.env.RESET_COMPLETION_DELAY_MS || DEFAULT_DELAY_MS, 'delay'),
        count: null,
        summary: null,
        userId: null,
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--data') {
            if (!value) usage('--data requires a file path');
            options.data = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--user-id') {
            options.userId = positiveInteger(value, '--user-id');
            index += 1;
        } else if (argument === '--count') {
            options.count = positiveInteger(value, '--count');
            index += 1;
        } else if (argument === '--summary') {
            if (!value) usage('--summary requires a file path');
            options.summary = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--delay-ms') {
            options.delayMs = nonNegativeInteger(value, '--delay-ms');
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }
    const selectors = [options.userId, options.count, options.summary].filter((value) => value !== null);
    if (selectors.length > 1) usage('--user-id, --count, and --summary are mutually exclusive');
    return options;
}

function requireResetGuard() {
    if (process.env.CONFIRM_STAGING !== 'true') usage('CONFIRM_STAGING=true is required');
    if (process.env.ALLOW_WRITE_TESTS !== 'true') usage('ALLOW_WRITE_TESTS=true is required');
    if (process.env.CONFIRM_COMPLETION_RESET !== 'true') {
        usage('CONFIRM_COMPLETION_RESET=true is required');
    }
}

function stagingBaseUrl() {
    const value = process.env.BASE_URL;
    if (!value) usage('BASE_URL is required');

    let parsed;
    try {
        parsed = new URL(value);
    } catch {
        usage(`BASE_URL must be an absolute URL: ${value}`);
    }
    if (!['http:', 'https:'].includes(parsed.protocol)) usage('BASE_URL must use HTTP(S)');
    if (parsed.hostname.toLowerCase() === 'api.audigo.kr') usage('Reset against production is blocked');
    return value.replace(/\/$/, '');
}

function countFromSummary(path) {
    let summary;
    try {
        summary = JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid k6 summary JSON from ${path}: ${error.message}`);
    }
    const count = summary?.metrics?.completion_attempts?.count
        ?? summary?.metrics?.iterations?.count;
    if (!Number.isSafeInteger(count) || count <= 0) {
        usage(`${path} does not contain a positive completion attempt count`);
    }
    return count;
}

function readUsers(path, { selectedUserId, selectedCount }) {
    let parsed;
    try {
        parsed = JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid test data JSON from ${path}: ${error.message}`);
    }
    if (!parsed || !Array.isArray(parsed.users) || parsed.users.length === 0) {
        usage(`${path} must contain a non-empty users array`);
    }

    let users = selectedUserId === null
        ? parsed.users
        : parsed.users.filter((user) => user.userId === selectedUserId);
    if (users.length === 0) usage(`userId ${selectedUserId} was not found in ${path}`);
    if (selectedCount !== null) {
        if (selectedCount > users.length) {
            usage(`reset count ${selectedCount} exceeds the ${users.length} configured users`);
        }
        users = users.slice(0, selectedCount);
    }

    const userIds = new Set();
    const itemIds = new Set();
    for (const user of users) {
        if (!Number.isSafeInteger(user.userId) || user.userId <= 0) usage('every user requires a valid userId');
        if (!user.accessToken || typeof user.accessToken !== 'string') usage(`userId ${user.userId} is missing accessToken`);
        if (!Number.isSafeInteger(user.itineraryItemId) || user.itineraryItemId <= 0) {
            usage(`userId ${user.userId} is missing a valid itineraryItemId`);
        }
        if (userIds.has(user.userId)) usage(`duplicate userId ${user.userId}`);
        if (itemIds.has(user.itineraryItemId)) usage(`duplicate itineraryItemId ${user.itineraryItemId}`);
        userIds.add(user.userId);
        itemIds.add(user.itineraryItemId);
    }
    return users;
}

function sleep(milliseconds) {
    return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

async function resetCompletion(baseUrl, runId, user) {
    const path = `/api/itinerary-items/${user.itineraryItemId}/completion`;
    let response;
    try {
        response = await fetch(`${baseUrl}${path}`, {
            method: 'PATCH',
            redirect: 'manual',
            headers: {
                Authorization: `Bearer ${user.accessToken}`,
                'Content-Type': 'application/json',
                'X-Test-Run-Id': runId,
                'X-Test-Scenario': 'RESET-COMPLETION',
            },
            body: JSON.stringify({ is_completed: false }),
        });
    } catch (error) {
        const cause = error?.cause;
        const detail = cause?.code
            ? `${cause.code}: ${cause.message || 'network connection failed'}`
            : cause?.message || error.message;
        throw new Error(`userId ${user.userId}: could not reach BASE_URL: ${detail}`);
    }

    let envelope;
    try {
        envelope = await response.json();
    } catch {
        envelope = null;
    }
    if (!response.ok) {
        const message = envelope?.message || envelope?.error || envelope?.error_message;
        throw new Error(`userId ${user.userId}: reset failed with HTTP ${response.status}${message ? `: ${message}` : ''}`);
    }

    const data = envelope?.data;
    if (!data || data.itinerary_item_id !== user.itineraryItemId
        || data.is_completed !== false || data.completed_at !== null) {
        throw new Error(`userId ${user.userId}: reset response does not confirm incomplete state`);
    }
}

async function main() {
    const options = readArguments(process.argv.slice(2));
    requireResetGuard();
    const baseUrl = stagingBaseUrl();
    const selectedCount = options.summary === null ? options.count : countFromSummary(options.summary);
    const users = readUsers(options.data, {
        selectedUserId: options.userId,
        selectedCount,
    });
    const runId = process.env.TEST_RUN_ID || `reset-completion-${new Date().toISOString().replace(/[-:.]/g, '')}`;

    console.log(`Resetting ${users.length} completion item(s) sequentially...`);
    if (options.summary !== null) console.log(`Selected from k6 summary: ${options.summary}`);
    for (let index = 0; index < users.length; index += 1) {
        await resetCompletion(baseUrl, runId, users[index]);
        const completed = index + 1;
        if (completed === users.length || completed % 10 === 0) {
            console.log(`Reset ${completed}/${users.length}`);
        }
        if (options.delayMs > 0 && completed < users.length) await sleep(options.delayMs);
    }
    console.log(`Completion reset finished: ${users.length}/${users.length}.`);
    console.log(`run_id=${runId}`);
}

main().catch((error) => {
    console.error(`Completion reset failed: ${error.message}`);
    process.exit(1);
});
