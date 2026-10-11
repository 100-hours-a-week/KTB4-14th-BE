#!/usr/bin/env node
import { chmodSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ROOT_DIR = resolve(import.meta.dirname, '../..');
const DEFAULT_TIMEOUT_SECONDS = 300;
const DEFAULT_POLL_INTERVAL_SECONDS = 1;

function usage(message) {
    if (message) {
        console.error(`Error: ${message}`);
    }
    console.error(
        'Usage: CONFIRM_STAGING=true ALLOW_WRITE_TESTS=true CONFIRM_AI_MOCK=true '
        + 'node scripts/initData/seed-test-user.mjs --user-id <id> '
        + '[--data data/test-ids.json] [--request data/create-request.json] '
        + '[--timeout-seconds 300] [--poll-interval-seconds 1] [--replace | --no-record]'
    );
    process.exit(1);
}

function positiveInteger(value, label) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number <= 0) {
        usage(`${label} must be a positive integer`);
    }
    return number;
}

function readArguments(argumentsList) {
    const options = {
        data: resolve(ROOT_DIR, 'data/test-ids.json'),
        request: resolve(ROOT_DIR, 'data/create-request.json'),
        pollIntervalSeconds: DEFAULT_POLL_INTERVAL_SECONDS,
        record: true,
        replace: false,
        timeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
        userId: null,
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--user-id') {
            options.userId = positiveInteger(value, '--user-id');
            index += 1;
        } else if (argument === '--data') {
            if (!value) usage('--data requires a file path');
            options.data = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--request') {
            if (!value) usage('--request requires a file path');
            options.request = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--timeout-seconds') {
            options.timeoutSeconds = positiveInteger(value, '--timeout-seconds');
            index += 1;
        } else if (argument === '--poll-interval-seconds') {
            options.pollIntervalSeconds = positiveInteger(value, '--poll-interval-seconds');
            index += 1;
        } else if (argument === '--replace') {
            options.replace = true;
        } else if (argument === '--no-record') {
            options.record = false;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }

    if (!options.userId) usage('--user-id is required');
    if (options.replace && !options.record) {
        usage('--replace and --no-record cannot be used together');
    }
    return options;
}

function requireSeedGuard() {
    if (process.env.CONFIRM_STAGING !== 'true') {
        usage('CONFIRM_STAGING=true is required');
    }
    if (process.env.ALLOW_WRITE_TESTS !== 'true') {
        usage('ALLOW_WRITE_TESTS=true is required because this creates a travel plan');
    }
    if (process.env.CONFIRM_AI_MOCK !== 'true') {
        usage('CONFIRM_AI_MOCK=true is required after verifying the Staging AI Mock route');
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
    if (!['http:', 'https:'].includes(parsed.protocol)) {
        usage(`BASE_URL must use HTTP(S): ${value}`);
    }
    if (parsed.hostname.toLowerCase() === 'api.audigo.kr') {
        usage('Seeding against production is blocked');
    }
    return value.replace(/\/$/, '');
}

function readJson(path, label) {
    try {
        return JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid ${label} JSON from ${path}: ${error.message}`);
    }
}

function findUser(testData, userId) {
    if (!testData || !Array.isArray(testData.users)) {
        usage('test data must contain a users array');
    }
    const user = testData.users.find((entry) => entry.userId === userId);
    if (!user) usage(`userId ${userId} was not found in the test data`);
    if (!user.accessToken || typeof user.accessToken !== 'string') {
        usage(`userId ${userId} is missing accessToken; run generate-test-tokens.mjs first`);
    }
    return user;
}

function hasExistingSeed(user) {
    return ['travelPlanId', 'itineraryItemId', 'generationJobId']
        .some((field) => user[field] !== undefined && user[field] !== null && user[field] !== '');
}

function sleep(milliseconds) {
    return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

function apiError(response, body) {
    const message = body && typeof body === 'object'
        ? body.message || body.error || body.error_message
        : null;
    return `${response.status} ${response.statusText}${message ? `: ${message}` : ''}`;
}

async function request(baseUrl, token, method, path, body) {
    let response;
    try {
        response = await fetch(`${baseUrl}${path}`, {
            method,
            redirect: 'manual',
            headers: {
                Authorization: `Bearer ${token}`,
                'Content-Type': 'application/json',
                'X-Test-Run-Id': process.env.TEST_RUN_ID || `seed-${Date.now()}`,
                'X-Test-Scenario': 'SEED',
            },
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (error) {
        const cause = error?.cause;
        const detail = cause?.code
            ? `${cause.code}: ${cause.message || 'network connection failed'}`
            : cause?.message || error.message;
        throw new Error(`${method} ${path} could not reach the configured BASE_URL: ${detail}`);
    }

    let parsed;
    try {
        parsed = await response.json();
    } catch {
        parsed = null;
    }
    if (!response.ok) {
        throw new Error(`${method} ${path} failed: ${apiError(response, parsed)}`);
    }
    if (!parsed || typeof parsed !== 'object' || !Object.hasOwn(parsed, 'data')) {
        throw new Error(`${method} ${path} returned an invalid API envelope`);
    }
    return parsed.data;
}

function selectedIncompleteItem(itinerary) {
    const days = Array.isArray(itinerary?.days) ? itinerary.days : [];
    for (const day of days) {
        const items = Array.isArray(day.items) ? day.items : [];
        const item = items.find((entry) => entry && entry.is_completed === false
            && Number.isSafeInteger(entry.itinerary_item_id) && entry.itinerary_item_id > 0);
        if (item) return item.itinerary_item_id;
    }
    throw new Error('Generated itinerary has no incomplete itinerary item to use for LT-02');
}

function writeAtomically(path, value) {
    const temporaryPath = `${path}.tmp-${process.pid}`;
    try {
        writeFileSync(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, {
            encoding: 'utf8',
            mode: 0o600,
        });
        renameSync(temporaryPath, path);
        chmodSync(path, 0o600);
    } catch (error) {
        usage(`could not write ${path}: ${error.message}`);
    }
}

async function main() {
    const options = readArguments(process.argv.slice(2));
    requireSeedGuard();
    const baseUrl = stagingBaseUrl();
    const testData = readJson(options.data, 'test data');
    const creationRequest = readJson(options.request, 'creation request');
    const user = findUser(testData, options.userId);

    if (options.record && hasExistingSeed(user) && !options.replace) {
        usage(
            `userId ${options.userId} already has test resource IDs; use --replace only to intentionally create another travel plan`
        );
    }

    console.log(`Creating one future travel plan for userId ${options.userId}...`);
    const created = await request(baseUrl, user.accessToken, 'POST', '/api/travel-plans', creationRequest);
    const travelPlanId = created?.travel_plan_id;
    const generationJobId = created?.generation_job_id;
    if (!Number.isSafeInteger(travelPlanId) || !Number.isSafeInteger(generationJobId)) {
        throw new Error('Create response is missing numeric travel_plan_id or generation_job_id');
    }

    const deadline = Date.now() + options.timeoutSeconds * 1_000;
    console.log(`Waiting for generation job ${generationJobId} (timeout ${options.timeoutSeconds}s)...`);
    while (Date.now() < deadline) {
        const status = await request(
            baseUrl,
            user.accessToken,
            'GET',
            `/api/ai-generation-jobs/${generationJobId}`
        );
        if (status.status === 'COMPLETED') break;
        if (status.status === 'FAILED') {
            throw new Error(`Generation job ${generationJobId} failed: ${status.error_message || 'no error message'}`);
        }
        await sleep(options.pollIntervalSeconds * 1_000);
    }

    const completedStatus = await request(
        baseUrl,
        user.accessToken,
        'GET',
        `/api/ai-generation-jobs/${generationJobId}`
    );
    if (completedStatus.status !== 'COMPLETED') {
        throw new Error(`Generation job ${generationJobId} did not complete within ${options.timeoutSeconds}s`);
    }

    const itinerary = await request(
        baseUrl,
        user.accessToken,
        'GET',
        `/api/travel-plans/${travelPlanId}/itinerary`
    );
    const itineraryItemId = selectedIncompleteItem(itinerary);

    if (options.record) {
        const updatedData = {
            ...testData,
            users: testData.users.map((entry) => entry.userId === options.userId
                ? { ...entry, travelPlanId, generationJobId, itineraryItemId }
                : entry),
        };
        writeAtomically(options.data, updatedData);

        console.log(`Seeded userId ${options.userId}.`);
        console.log(`Recorded travelPlanId=${travelPlanId}, generationJobId=${generationJobId}, itineraryItemId=${itineraryItemId}.`);
        console.log(`Updated: ${options.data}`);
        return;
    }

    console.log(`Created an unrecorded completed travel for userId ${options.userId}.`);
    console.log(`travelPlanId=${travelPlanId}, generationJobId=${generationJobId}, itineraryItemId=${itineraryItemId}.`);
    console.log('test-ids.json was not changed. Use this travelPlanId only for Staging-only past-travel preparation.');
}

main().catch((error) => {
    console.error(`Seed failed: ${error.message}`);
    process.exit(1);
});
