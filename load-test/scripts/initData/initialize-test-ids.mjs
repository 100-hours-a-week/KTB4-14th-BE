#!/usr/bin/env node
import { chmodSync, existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ROOT_DIR = resolve(import.meta.dirname, '..');

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: node scripts/initialize-test-ids.mjs '
        + '[--ids-file data/loadtest-user-ids.txt] '
        + '[--output data/test-ids.json] [--expected-count 150] [--force]'
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
        expectedCount: 150,
        force: false,
        idsFile: resolve(ROOT_DIR, 'data/loadtest-user-ids.txt'),
        output: resolve(ROOT_DIR, 'data/test-ids.json'),
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--ids-file') {
            if (!value) usage('--ids-file requires a file path');
            options.idsFile = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--output') {
            if (!value) usage('--output requires a file path');
            options.output = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--expected-count') {
            options.expectedCount = positiveInteger(value, '--expected-count');
            index += 1;
        } else if (argument === '--force') {
            options.force = true;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }
    return options;
}

function readUserIds(path, expectedCount) {
    let content;
    try {
        content = readFileSync(path, 'utf8');
    } catch (error) {
        usage(`could not read ${path}: ${error.message}`);
    }

    const values = content.trim().split(/[\s,]+/).filter(Boolean);
    if (values.length !== expectedCount) {
        usage(`${path} must contain exactly ${expectedCount} user IDs, but found ${values.length}`);
    }

    const userIds = values.map((value) => positiveInteger(value, `invalid user ID ${value}`));
    const uniqueIds = new Set(userIds);
    if (uniqueIds.size !== userIds.length) usage(`${path} contains duplicate user IDs`);
    return userIds;
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

const options = readArguments(process.argv.slice(2));
if (existsSync(options.output) && !options.force) {
    usage(`${options.output} already exists; use --force only when intentionally rebuilding all test data`);
}

const userIds = readUserIds(options.idsFile, options.expectedCount);
const testData = {
    users: userIds.map((userId) => ({
        userId,
        accessToken: '',
        travelPlanId: null,
        itineraryItemId: null,
        generationJobId: null,
    })),
};

writeAtomically(options.output, testData);
console.log(`Initialized ${userIds.length} users in ${options.output}.`);
console.log('Tokens and resource IDs are intentionally empty.');
