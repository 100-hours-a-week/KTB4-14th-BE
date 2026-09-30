#!/usr/bin/env node
import { chmodSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const ROOT_DIR = resolve(import.meta.dirname, '../..');
const MYSQL_DATETIME = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: node scripts/initData/generate-past-travel-sql.mjs '
        + '--arrival "YYYY-MM-DD HH:mm:ss" --departure "YYYY-MM-DD HH:mm:ss" '
        + '[--data data/test-ids.json] '
        + '[--user-id <id> | --from-index 1 [--to-index 150]] '
        + '[--logs-dir results/past-seed] '
        + '[--output results/past-seed/update-past-travels.sql]'
    );
    process.exit(1);
}

function positiveInteger(value, label) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number <= 0) usage(`${label} must be a positive integer`);
    return number;
}

function mysqlDatetime(value, label) {
    if (!value || !MYSQL_DATETIME.test(value)) {
        usage(`${label} must use YYYY-MM-DD HH:mm:ss`);
    }
    return value;
}

function readArguments(argumentsList) {
    const options = {
        arrival: null,
        data: resolve(ROOT_DIR, 'data/test-ids.json'),
        departure: null,
        fromIndex: 1,
        logsDir: resolve(ROOT_DIR, 'results/past-seed'),
        output: resolve(ROOT_DIR, 'results/past-seed/update-past-travels.sql'),
        toIndex: null,
        userId: null,
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--arrival') {
            options.arrival = mysqlDatetime(value, '--arrival');
            index += 1;
        } else if (argument === '--departure') {
            options.departure = mysqlDatetime(value, '--departure');
            index += 1;
        } else if (argument === '--data') {
            if (!value) usage('--data requires a file path');
            options.data = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--user-id') {
            options.userId = positiveInteger(value, '--user-id');
            index += 1;
        } else if (argument === '--from-index') {
            options.fromIndex = positiveInteger(value, '--from-index');
            index += 1;
        } else if (argument === '--to-index') {
            options.toIndex = positiveInteger(value, '--to-index');
            index += 1;
        } else if (argument === '--logs-dir') {
            if (!value) usage('--logs-dir requires a directory path');
            options.logsDir = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--output') {
            if (!value) usage('--output requires a file path');
            options.output = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }

    if (!options.arrival) usage('--arrival is required');
    if (!options.departure) usage('--departure is required');
    if (options.arrival >= options.departure) usage('--arrival must be earlier than --departure');
    if (options.toIndex !== null && options.fromIndex > options.toIndex) {
        usage('--from-index must not exceed --to-index');
    }
    if (options.userId !== null && (options.fromIndex !== 1 || options.toIndex !== null)) {
        usage('--user-id cannot be combined with --from-index or --to-index');
    }
    return options;
}

function selectedUserIds(options) {
    let parsed;
    try {
        parsed = JSON.parse(readFileSync(options.data, 'utf8'));
    } catch (error) {
        usage(`could not read valid test data JSON from ${options.data}: ${error.message}`);
    }
    if (!parsed || !Array.isArray(parsed.users) || parsed.users.length === 0) {
        usage(`${options.data} must contain a non-empty users array`);
    }

    const userIds = parsed.users.map((user) => positiveInteger(user.userId, 'userId in test data'));
    if (new Set(userIds).size !== userIds.length) usage(`${options.data} contains duplicate user IDs`);

    if (options.userId !== null) {
        if (!userIds.includes(options.userId)) usage(`userId ${options.userId} was not found in ${options.data}`);
        return [options.userId];
    }

    const toIndex = options.toIndex === null ? userIds.length : options.toIndex;
    if (toIndex > userIds.length) usage(`--to-index ${toIndex} exceeds ${userIds.length} configured users`);
    if (options.fromIndex > userIds.length) {
        usage(`--from-index ${options.fromIndex} exceeds ${userIds.length} configured users`);
    }
    return userIds.slice(options.fromIndex - 1, toIndex);
}

function readSeedRecords(options, userIds) {
    const records = [];
    const travelPlanIds = new Set();

    for (const userId of userIds) {
        const path = resolve(options.logsDir, `user-${userId}.log`);
        let content;
        try {
            content = readFileSync(path, 'utf8');
        } catch (error) {
            usage(`could not read ${path}: ${error.message}`);
        }

        const userMatches = [...content.matchAll(
            /Created an unrecorded completed travel for userId (\d+)\./g
        )];
        if (userMatches.length !== 1 || Number(userMatches[0][1]) !== userId) {
            usage(`${path} does not contain exactly one completed result for userId ${userId}`);
        }

        const matches = [...content.matchAll(
            /travelPlanId=(\d+), generationJobId=(\d+), itineraryItemId=(\d+)\./g
        )];
        if (matches.length !== 1) {
            usage(`${path} must contain exactly one completed --no-record result, but found ${matches.length}`);
        }

        const travelPlanId = positiveInteger(matches[0][1], `travelPlanId in ${path}`);
        const generationJobId = positiveInteger(matches[0][2], `generationJobId in ${path}`);
        const itineraryItemId = positiveInteger(matches[0][3], `itineraryItemId in ${path}`);
        if (travelPlanIds.has(travelPlanId)) usage(`duplicate travelPlanId ${travelPlanId} in seed logs`);
        travelPlanIds.add(travelPlanId);
        records.push({ generationJobId, itineraryItemId, travelPlanId, userId });
    }
    return records;
}

function sqlFor(records, options) {
    const values = records
        .map(({ userId, travelPlanId }) => `    (${userId}, ${travelPlanId})`)
        .join(',\n');

    return `-- Generated from ${options.logsDir}/user-<id>.log
-- STAGING ONLY. This file intentionally does not COMMIT.
-- Expected target count: ${records.length}
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS loadtest_past_travel_targets;
CREATE TEMPORARY TABLE loadtest_past_travel_targets (
    user_id BIGINT NOT NULL PRIMARY KEY,
    travel_plan_id BIGINT NOT NULL UNIQUE
);

INSERT INTO loadtest_past_travel_targets (user_id, travel_plan_id) VALUES
${values};

UPDATE travel_plans AS travel
JOIN loadtest_past_travel_targets AS target
  ON target.travel_plan_id = travel.id
 AND target.user_id = travel.user_id
SET travel.arrival_datetime = '${options.arrival}',
    travel.departure_datetime = '${options.departure}'
WHERE travel.status = 'COMPLETED';

SELECT ROW_COUNT() AS changed_past_travel_count;

SELECT COUNT(*) AS verified_past_travel_count
FROM travel_plans AS travel
JOIN loadtest_past_travel_targets AS target
  ON target.travel_plan_id = travel.id
 AND target.user_id = travel.user_id
WHERE travel.status = 'COMPLETED'
  AND travel.arrival_datetime = '${options.arrival}'
  AND travel.departure_datetime = '${options.departure}';

SELECT target.user_id,
       target.travel_plan_id,
       travel.status,
       travel.arrival_datetime,
       travel.departure_datetime
FROM loadtest_past_travel_targets AS target
LEFT JOIN travel_plans AS travel
  ON travel.id = target.travel_plan_id
 AND travel.user_id = target.user_id
WHERE travel.id IS NULL
   OR travel.status <> 'COMPLETED'
   OR travel.arrival_datetime <> '${options.arrival}'
   OR travel.departure_datetime <> '${options.departure}';

-- If verified_past_travel_count is ${records.length} and the final query returns no rows:
-- COMMIT;
-- Otherwise:
-- ROLLBACK;
`;
}

function writeAtomically(path, content) {
    mkdirSync(dirname(path), { recursive: true });
    const temporaryPath = `${path}.tmp-${process.pid}`;
    try {
        writeFileSync(temporaryPath, content, { encoding: 'utf8', mode: 0o600 });
        renameSync(temporaryPath, path);
        chmodSync(path, 0o600);
    } catch (error) {
        usage(`could not write ${path}: ${error.message}`);
    }
}

const options = readArguments(process.argv.slice(2));
const userIds = selectedUserIds(options);
const records = readSeedRecords(options, userIds);
writeAtomically(options.output, sqlFor(records, options));

console.log(`Generated guarded SQL for ${records.length} past travel plan(s).`);
console.log(`Output: ${options.output}`);
console.log('The SQL intentionally requires an explicit COMMIT after verification.');
