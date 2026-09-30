#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: node scripts/assert-k6-summary.mjs --summary <file> '
        + '[--mode standard|sse] [--min-sse-attempts 1]'
    );
    process.exit(1);
}

function positiveInteger(value, label) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number <= 0) usage(`${label} must be a positive integer`);
    return number;
}

function readArguments(argumentsList) {
    const options = { minSseAttempts: 1, mode: 'standard', summary: null };
    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--summary') {
            if (!value) usage('--summary requires a file path');
            options.summary = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--mode') {
            if (!['standard', 'sse'].includes(value)) usage('--mode must be standard or sse');
            options.mode = value;
            index += 1;
        } else if (argument === '--min-sse-attempts') {
            options.minSseAttempts = positiveInteger(value, '--min-sse-attempts');
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }
    if (!options.summary) usage('--summary is required');
    return options;
}

function readSummary(path) {
    try {
        return JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid summary JSON from ${path}: ${error.message}`);
    }
}

const options = readArguments(process.argv.slice(2));
const summary = readSummary(options.summary);
const metrics = summary?.metrics;
if (!metrics || typeof metrics !== 'object') usage(`${options.summary} has no metrics object`);

const checkFailures = metrics.checks?.fails ?? 0;
const droppedIterations = metrics.dropped_iterations?.count ?? 0;
if (checkFailures !== 0) usage(`${options.summary} has ${checkFailures} failed check(s)`);
if (droppedIterations !== 0) usage(`${options.summary} has ${droppedIterations} dropped iteration(s)`);

if (options.mode === 'standard') {
    const requestCount = metrics.http_reqs?.count ?? 0;
    const iterationCount = metrics.iterations?.count ?? 0;
    const failureRate = metrics.http_req_failed?.value ?? 0;
    if (requestCount <= 0) usage(`${options.summary} has no HTTP requests`);
    if (iterationCount <= 0) usage(`${options.summary} has no completed iterations`);
    if (failureRate !== 0) usage(`${options.summary} has HTTP failure rate ${failureRate}`);
    console.log(
        `Summary passed: requests=${requestCount}, iterations=${iterationCount}, failedChecks=0, httpFailureRate=0.`
    );
} else {
    const attempts = metrics.sse_connection_attempts?.count ?? 0;
    if (attempts < options.minSseAttempts) {
        usage(`${options.summary} has ${attempts} SSE attempt(s); expected at least ${options.minSseAttempts}`);
    }
    console.log(`SSE summary passed: attempts=${attempts}, failedChecks=0, droppedIterations=0.`);
}
