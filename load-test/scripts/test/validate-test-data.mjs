#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ROOT_DIR = resolve(import.meta.dirname, '..');

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: node scripts/validate-test-data.mjs '
        + '[--data data/test-ids.json] [--expected-count 150] [--min-token-ttl-seconds 1800]'
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
        expectedCount: 150,
        minTokenTtlSeconds: 1_800,
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--data') {
            if (!value) usage('--data requires a file path');
            options.data = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--expected-count') {
            options.expectedCount = positiveInteger(value, '--expected-count');
            index += 1;
        } else if (argument === '--min-token-ttl-seconds') {
            options.minTokenTtlSeconds = nonNegativeInteger(value, '--min-token-ttl-seconds');
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }
    return options;
}

function readTestData(path) {
    try {
        return JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid JSON from ${path}: ${error.message}`);
    }
}

function jwtPayload(token, userId) {
    if (typeof token !== 'string' || token.split('.').length !== 3) {
        usage(`userId ${userId} has an invalid accessToken`);
    }
    try {
        return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    } catch (error) {
        usage(`userId ${userId} has an unreadable JWT payload: ${error.message}`);
    }
}

function requireUniquePositiveIds(users, field) {
    const values = users.map((user) => positiveInteger(user[field], `${field} for userId ${user.userId}`));
    if (new Set(values).size !== values.length) usage(`duplicate ${field} in test data`);
}

const options = readArguments(process.argv.slice(2));
const testData = readTestData(options.data);
if (!testData || !Array.isArray(testData.users)) usage(`${options.data} must contain a users array`);
if (testData.users.length !== options.expectedCount) {
    usage(`expected ${options.expectedCount} users, but found ${testData.users.length}`);
}

for (const field of ['userId', 'travelPlanId', 'itineraryItemId', 'generationJobId']) {
    requireUniquePositiveIds(testData.users, field);
}

const now = Math.floor(Date.now() / 1_000);
let minimumExpiry = Number.MAX_SAFE_INTEGER;
for (const user of testData.users) {
    const payload = jwtPayload(user.accessToken, user.userId);
    if (payload.sub !== String(user.userId)) {
        usage(`userId ${user.userId} token subject is ${payload.sub}`);
    }
    if (payload.token_type !== 'access') usage(`userId ${user.userId} token_type is not access`);
    if (!Number.isSafeInteger(payload.exp)) usage(`userId ${user.userId} token has no valid exp`);
    minimumExpiry = Math.min(minimumExpiry, payload.exp);
}

const remainingSeconds = minimumExpiry - now;
if (remainingSeconds < options.minTokenTtlSeconds) {
    usage(
        `the earliest token expires in ${remainingSeconds}s; at least ${options.minTokenTtlSeconds}s is required`
    );
}

console.log(`Validated ${testData.users.length} users with unique resource IDs.`);
console.log(`Earliest token expiry: ${new Date(minimumExpiry * 1_000).toISOString()}`);
console.log(`Minimum token TTL remaining: ${remainingSeconds}s.`);
