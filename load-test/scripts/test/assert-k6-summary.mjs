#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function printUsage(output = console.error) {
    output(
        'Usage: node scripts/test/assert-k6-summary.mjs --summary <file> '
        + '[--mode standard|sse|sse-load|sse-handshake|p01-notification] '
        + '[--min-sse-attempts 1] [--expected-notifications <count>]'
    );
}

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    printUsage();
    process.exit(1);
}

function positiveInteger(value, label) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number <= 0) usage(`${label} must be a positive integer`);
    return number;
}

function readArguments(argumentsList) {
    const options = {
        expectedNotifications: null,
        minSseAttempts: 1,
        mode: 'standard',
        summary: null,
    };
    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--summary') {
            if (!value) usage('--summary requires a file path');
            options.summary = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--mode') {
            if (!['standard', 'sse', 'sse-load', 'sse-handshake', 'p01-notification'].includes(value)) {
                usage('--mode must be standard, sse, sse-load, sse-handshake, or p01-notification');
            }
            // `sse` was the original public mode. Keep it as the SSE load-mode alias.
            options.mode = value === 'sse' ? 'sse-load' : value;
            index += 1;
        } else if (argument === '--min-sse-attempts') {
            options.minSseAttempts = positiveInteger(value, '--min-sse-attempts');
            index += 1;
        } else if (argument === '--expected-notifications') {
            options.expectedNotifications = positiveInteger(value, '--expected-notifications');
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            printUsage(console.log);
            process.exit(0);
        } else {
            usage(`unknown option: ${argument}`);
        }
    }
    if (!options.summary) usage('--summary is required');
    if (options.mode === 'p01-notification' && options.expectedNotifications === null) {
        usage('--expected-notifications is required for p01-notification mode');
    }
    return options;
}

function readSummary(path) {
    try {
        return JSON.parse(readFileSync(path, 'utf8'));
    } catch (error) {
        usage(`could not read valid summary JSON from ${path}: ${error.message}`);
    }
}

function metricCount(metrics, metricName, summaryPath) {
    const count = metrics[metricName]?.count;
    if (!Number.isFinite(count)) {
        usage(`${summaryPath} has no ${metricName} counter`);
    }
    return count;
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
    const attempts = metricCount(metrics, 'sse_connection_attempts', options.summary);
    const opened = metricCount(metrics, 'sse_connection_opened', options.summary);
    const connectedEvents = metricCount(metrics, 'sse_connected_events', options.summary);
    // A zero-valued Counter can be omitted by k6 in old summaries. New SSE flows
    // initialize this counter explicitly, but treating an absent legacy counter as
    // zero preserves compatibility without weakening a non-zero error failure.
    const connectionErrors = metrics.sse_connection_errors?.count ?? 0;

    if (attempts < options.minSseAttempts) {
        usage(`${options.summary} has ${attempts} SSE attempt(s); expected at least ${options.minSseAttempts}`);
    }
    if (opened < attempts) {
        usage(`${options.summary} has ${opened} opened SSE connection(s) for ${attempts} attempt(s)`);
    }
    if (connectedEvents < attempts) {
        usage(`${options.summary} has ${connectedEvents} connected event(s) for ${attempts} attempt(s)`);
    }
    if (connectionErrors !== 0) {
        usage(`${options.summary} has ${connectionErrors} SSE connection error(s)`);
    }

    if (options.mode === 'sse-handshake') {
        const handshake200 = metricCount(metrics, 'sse_handshake_200', options.summary);
        if (handshake200 < attempts) {
            usage(`${options.summary} has ${handshake200} SSE handshake 200 response(s) for ${attempts} attempt(s)`);
        }
        console.log(
            `SSE handshake summary passed: attempts=${attempts}, opened=${opened}, connectedEvents=${connectedEvents}, handshake200=${handshake200}, connectionErrors=0.`
        );
    } else if (options.mode === 'p01-notification') {
        const expected = options.expectedNotifications;
        const notificationWaits = metricCount(metrics, 'p01_notification_waits', options.summary);
        const notificationEvents = metricCount(metrics, 'p01_notification_events_received', options.summary);
        const creationAttempts = metricCount(metrics, 'p01_creation_attempts', options.summary);
        const generationCompleted = metricCount(metrics, 'p01_generation_completed', options.summary);
        const failureRate = metrics.http_req_failed?.value ?? 0;

        if (failureRate !== 0) {
            usage(`${options.summary} has HTTP failure rate ${failureRate}`);
        }

        if (attempts !== expected || opened !== expected || connectedEvents !== expected) {
            usage(
                `${options.summary} SSE setup is not 1:1: attempts=${attempts}, opened=${opened}, `
                + `connectedEvents=${connectedEvents}, expected=${expected}`
            );
        }
        if (notificationWaits !== expected || notificationEvents !== expected) {
            usage(
                `${options.summary} notification delivery is not 1:1: waits=${notificationWaits}, `
                + `events=${notificationEvents}, expected=${expected}`
            );
        }
        if (creationAttempts !== expected || generationCompleted !== expected) {
            usage(
                `${options.summary} P-01 completion is not 1:1: creations=${creationAttempts}, `
                + `completed=${generationCompleted}, expected=${expected}`
            );
        }
        console.log(
            `P-01 notification summary passed: users=${expected}, generationCompleted=${generationCompleted}, `
            + `notificationEvents=${notificationEvents}, connectionErrors=0.`
        );
    } else {
        console.log(
            `SSE load summary passed: attempts=${attempts}, opened=${opened}, connectedEvents=${connectedEvents}, connectionErrors=0, failedChecks=0, droppedIterations=0.`
        );
    }
}
