#!/usr/bin/env node
import { createHmac } from 'node:crypto';
import { chmodSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const DEFAULT_TTL_SECONDS = 86_400;
const ROOT_DIR = resolve(import.meta.dirname, '../..');

function usage(message) {
    if (message) {
        console.error(`Error: ${message}`);
    }
    console.error(
        'Usage: STAGING_JWT_SECRET=... node scripts/initData/generate-test-tokens.mjs '
        + '[--input data/test-ids.json] [--output data/test-ids.json] [--ttl 86400]'
    );
    process.exit(1);
}

function readArguments(argumentsList) {
    const options = {
        input: resolve(ROOT_DIR, 'data/test-ids.json'),
        output: resolve(ROOT_DIR, 'data/test-ids.json'),
        ttl: Number(process.env.JWT_TTL_SECONDS || DEFAULT_TTL_SECONDS),
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--input') {
            if (!value) {
                usage('--input requires a file path');
            }
            options.input = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--output') {
            if (!value) {
                usage('--output requires a file path');
            }
            options.output = resolve(process.cwd(), value);
            index += 1;
        } else if (argument === '--ttl') {
            options.ttl = Number(value);
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }

    if (!Number.isSafeInteger(options.ttl) || options.ttl <= 0) {
        usage('TTL must be a positive integer in seconds');
    }
    return options;
}

function base64UrlJson(value) {
    return Buffer.from(JSON.stringify(value)).toString('base64url');
}

function createAccessToken(userId, jwtSecret, issuedAt, ttlSeconds) {
    const header = base64UrlJson({ alg: 'HS256', typ: 'JWT' });
    const payload = base64UrlJson({
        sub: String(userId),
        iat: issuedAt,
        exp: issuedAt + ttlSeconds,
        token_type: 'access',
    });
    const unsignedToken = `${header}.${payload}`;
    const signature = createHmac('sha256', jwtSecret)
        .update(unsignedToken, 'utf8')
        .digest('base64url');
    return `${unsignedToken}.${signature}`;
}

function readTestData(inputPath) {
    let parsed;
    try {
        parsed = JSON.parse(readFileSync(inputPath, 'utf8'));
    } catch (error) {
        usage(`could not read valid JSON from ${inputPath}: ${error.message}`);
    }

    if (!parsed || !Array.isArray(parsed.users) || parsed.users.length === 0) {
        usage(`${inputPath} must contain a non-empty users array`);
    }
    for (const user of parsed.users) {
        if (!Number.isSafeInteger(user.userId) || user.userId <= 0) {
            usage('each users[] entry requires a positive integer userId');
        }
    }
    return parsed;
}

function writeAtomically(outputPath, content) {
    const temporaryPath = `${outputPath}.tmp-${process.pid}`;
    try {
        writeFileSync(temporaryPath, content, { encoding: 'utf8', mode: 0o600 });
        renameSync(temporaryPath, outputPath);
        chmodSync(outputPath, 0o600);
    } catch (error) {
        usage(`could not write ${outputPath}: ${error.message}`);
    }
}

const jwtSecret = process.env.STAGING_JWT_SECRET;
if (!jwtSecret) {
    usage('STAGING_JWT_SECRET is required; do not store it in .env or Git');
}

const options = readArguments(process.argv.slice(2));
const testData = readTestData(options.input);
const issuedAt = Math.floor(Date.now() / 1000);
const expiresAt = issuedAt + options.ttl;
const updatedData = {
    ...testData,
    users: testData.users.map((user) => ({
        ...user,
        accessToken: createAccessToken(user.userId, jwtSecret, issuedAt, options.ttl),
    })),
};

writeAtomically(options.output, `${JSON.stringify(updatedData, null, 2)}\n`);
console.log(`Issued ${updatedData.users.length} Staging access token(s).`);
console.log(`Output: ${options.output}`);
console.log(`Expires: ${new Date(expiresAt * 1000).toISOString()}`);
