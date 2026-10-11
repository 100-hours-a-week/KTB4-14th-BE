#!/usr/bin/env node
import { chmodSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const BACKEND_ROOT = resolve(import.meta.dirname, '../../..');
const REGIONS_SQL_PATH = resolve(BACKEND_ROOT, 'src/main/resources/data.regions.sql');
const DATA_SQL_PATH = resolve(BACKEND_ROOT, 'src/main/resources/data.sql');
const TEST_IDS_PATH = resolve(BACKEND_ROOT, 'load-test/data/test-ids.json');

const DEFAULT_USER_COUNT = 50;
const DEFAULT_TRAVELS_PER_USER = 20;
const DAYS_PER_REPRESENTATIVE_TRIP = 2;
const ITEMS_PER_DAY = 5;
const BASE_CREATED_AT = Date.UTC(2026, 9, 4, 0, 0, 0);
const FUTURE_TRIP_BASE_DATE = Date.UTC(2030, 4, 1, 10, 0, 0);
const PAST_TRIP_BASE_DATE = Date.UTC(2026, 8, 1, 10, 0, 0);

function usage(message) {
    if (message) console.error(`Error: ${message}`);
    console.error(
        'Usage: node scripts/initData/generate-local-read-fixture.mjs '
        + '[--users 50] [--travels-per-user 20]'
    );
    console.error('');
    console.error('Before running for the first time, keep the original region seed separately:');
    console.error('  cp ../src/main/resources/data.sql ../src/main/resources/data.regions.sql');
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
        users: Number(process.env.LOCAL_FIXTURE_USERS || DEFAULT_USER_COUNT),
        travelsPerUser: Number(process.env.LOCAL_FIXTURE_TRAVELS_PER_USER || DEFAULT_TRAVELS_PER_USER),
    };

    for (let index = 0; index < argumentsList.length; index += 1) {
        const argument = argumentsList[index];
        const value = argumentsList[index + 1];
        if (argument === '--users') {
            options.users = positiveInteger(value, '--users');
            index += 1;
        } else if (argument === '--travels-per-user') {
            options.travelsPerUser = positiveInteger(value, '--travels-per-user');
            index += 1;
        } else if (argument === '--help' || argument === '-h') {
            usage();
        } else {
            usage(`unknown option: ${argument}`);
        }
    }

    positiveInteger(options.users, '--users');
    positiveInteger(options.travelsPerUser, '--travels-per-user');
    return options;
}

function readRegionsSql() {
    try {
        return readFileSync(REGIONS_SQL_PATH, 'utf8').trimEnd();
    } catch (error) {
        usage(`could not read ${REGIONS_SQL_PATH}: ${error.message}`);
    }
}

function quote(value) {
    if (value === null || value === undefined) return 'NULL';
    return `'${String(value).replaceAll("'", "''")}'`;
}

function mysqlDateTime(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return [
        date.getUTCFullYear(),
        pad(date.getUTCMonth() + 1),
        pad(date.getUTCDate()),
    ].join('-') + ' ' + [
        pad(date.getUTCHours()),
        pad(date.getUTCMinutes()),
        pad(date.getUTCSeconds()),
    ].join(':');
}

function mysqlDate(date) {
    return mysqlDateTime(date).slice(0, 10);
}

function addDays(date, days) {
    const next = new Date(date);
    next.setUTCDate(next.getUTCDate() + days);
    return next;
}

function writeAtomically(path, content, mode = 0o644) {
    mkdirSync(dirname(path), { recursive: true });
    const temporaryPath = `${path}.tmp-${process.pid}`;
    writeFileSync(temporaryPath, content, { encoding: 'utf8', mode });
    renameSync(temporaryPath, path);
    chmodSync(path, mode);
}

function insertLine(table, columns, values) {
    return `INSERT INTO ${table} (${columns.join(', ')}) VALUES (${values.join(', ')});`;
}

function travelPlanIdFor(userId, tripIndex, travelsPerUser) {
    return ((userId - 1) * travelsPerUser) + tripIndex;
}

function regionIdFor(travelPlanId) {
    return ((travelPlanId - 1) % 25) + 1;
}

function companionFor(tripIndex) {
    const companions = [
        { type: 'SOLO', headcount: 1 },
        { type: 'COUPLE', headcount: 2 },
        { type: 'FRIEND', headcount: 3 },
        { type: 'FAMILY', headcount: 4 },
    ];
    return companions[(tripIndex - 1) % companions.length];
}

function dateForTrip(userId, tripIndex) {
    const base = tripIndex <= 10 ? FUTURE_TRIP_BASE_DATE : PAST_TRIP_BASE_DATE;
    const offsetDays = (userId - 1) + (tripIndex - 1);
    return addDays(new Date(base), offsetDays);
}

function appendCoreTripRows(sql, userId, tripIndex, travelsPerUser) {
    const travelPlanId = travelPlanIdFor(userId, tripIndex, travelsPerUser);
    const companion = companionFor(tripIndex);
    const arrival = dateForTrip(userId, tripIndex);
    const departure = addDays(arrival, tripIndex % 3 === 0 ? 3 : 2);
    const createdAt = mysqlDateTime(new Date(BASE_CREATED_AT - (travelPlanId * 60_000)));
    const confirmedAt = mysqlDateTime(new Date(BASE_CREATED_AT - (travelPlanId * 45_000)));
    const updatedAt = confirmedAt;

    sql.push(insertLine('travel_plans', [
        'id',
        'user_id',
        'region_id',
        'arrival_datetime',
        'departure_datetime',
        'headcount',
        'companion_type',
        'status',
        'confirmed_at',
        'created_at',
        'updated_at',
    ], [
        travelPlanId,
        userId,
        regionIdFor(travelPlanId),
        quote(mysqlDateTime(arrival)),
        quote(mysqlDateTime(departure)),
        companion.headcount,
        quote(companion.type),
        quote('COMPLETED'),
        quote(confirmedAt),
        quote(createdAt),
        quote(updatedAt),
    ]));

    sql.push(insertLine('travel_preferences', [
        'id',
        'travel_plan_id',
        'pace_type',
        'transport_type',
        'budget_min',
        'budget_max',
        'budget_type',
        'distance_preference',
        'extra_request',
        'created_at',
        'updated_at',
    ], [
        travelPlanId,
        travelPlanId,
        quote(tripIndex % 2 === 0 ? 'RELAXED' : 'BALANCED'),
        quote('PUBLIC_TRANSPORT'),
        100000,
        200000,
        quote('KRW'),
        50,
        quote(`local-read-fixture user=${userId} trip=${tripIndex}`),
        quote(createdAt),
        quote(updatedAt),
    ]));

    sql.push(insertLine('travel_preference_themes', [
        'id',
        'travel_preference_id',
        'theme',
    ], [
        travelPlanId,
        travelPlanId,
        quote(tripIndex % 2 === 0 ? 'FOOD' : 'CULTURE'),
    ]));

    sql.push(insertLine('travel_preference_foods', [
        'id',
        'travel_preference_id',
        'food_type',
    ], [
        travelPlanId,
        travelPlanId,
        quote(tripIndex % 2 === 0 ? 'WESTERN' : 'KOREAN'),
    ]));

    sql.push(insertLine('ai_generation_jobs', [
        'id',
        'travel_plan_id',
        'job_type',
        'status',
        'error_message',
        'started_at',
        'ended_at',
        'created_at',
    ], [
        travelPlanId,
        travelPlanId,
        quote('TRAVEL_ITINERARY'),
        quote('COMPLETED'),
        'NULL',
        quote(createdAt),
        quote(updatedAt),
        quote(createdAt),
    ]));

    return { arrival, createdAt, travelPlanId, updatedAt };
}

function appendRepresentativeItineraryRows(sql, counters, trip) {
    const itemIds = [];
    const placeTypes = ['TOURISM', 'RESTAURANT', 'TOURISM', 'TOURISM', 'ACCOMMODATION'];
    const times = [
        ['09:00:00', '10:00:00'],
        ['10:30:00', '11:30:00'],
        ['12:00:00', '13:00:00'],
        ['14:00:00', '15:00:00'],
        ['16:00:00', '17:00:00'],
    ];

    for (let dayNumber = 1; dayNumber <= DAYS_PER_REPRESENTATIVE_TRIP; dayNumber += 1) {
        const dayId = counters.dayId++;
        const travelDate = mysqlDate(addDays(trip.arrival, dayNumber - 1));

        sql.push(insertLine('itinerary_days', [
            'id',
            'travel_plan_id',
            'day_number',
            'travel_date',
            'created_at',
        ], [
            dayId,
            trip.travelPlanId,
            dayNumber,
            quote(travelDate),
            quote(trip.createdAt),
        ]));

        for (let sequence = 1; sequence <= ITEMS_PER_DAY; sequence += 1) {
            const placeId = counters.placeId++;
            const travelPlanPlaceId = counters.travelPlanPlaceId++;
            const itemId = counters.itemId++;
            const providerPlaceId = `local-${trip.travelPlanId}-${dayNumber}-${sequence}`;

            sql.push(insertLine('places', [
                'id',
                'provider',
                'provider_place_id',
                'created_at',
            ], [
                placeId,
                quote('KAKAO'),
                quote(providerPlaceId),
                quote(trip.createdAt),
            ]));

            sql.push(insertLine('travel_plan_places', [
                'id',
                'travel_plan_id',
                'place_id',
                'place_type',
                'source',
                'place_order',
            ], [
                travelPlanPlaceId,
                trip.travelPlanId,
                placeId,
                quote(placeTypes[sequence - 1]),
                quote('AI_RECOMMENDED'),
                ((dayNumber - 1) * ITEMS_PER_DAY) + sequence,
            ]));

            sql.push(insertLine('itinerary_items', [
                'id',
                'itinerary_day_id',
                'travel_plan_place_id',
                'sequence',
                'start_time',
                'end_time',
                'item_type',
                'is_completed',
                'completed_at',
                'created_at',
                'updated_at',
            ], [
                itemId,
                dayId,
                travelPlanPlaceId,
                sequence,
                quote(times[sequence - 1][0]),
                quote(times[sequence - 1][1]),
                quote('PLACE'),
                false,
                'NULL',
                quote(trip.createdAt),
                quote(trip.updatedAt),
            ]));

            itemIds.push({ dayNumber, itemId, sequence });
        }
    }

    let routeOrder = 1;
    for (let dayNumber = 1; dayNumber <= DAYS_PER_REPRESENTATIVE_TRIP; dayNumber += 1) {
        const dayItems = itemIds.filter((item) => item.dayNumber === dayNumber);
        for (let index = 0; index < dayItems.length - 1; index += 1) {
            const routeSegmentId = counters.routeSegmentId++;
            const routeLegId = counters.routeLegId++;
            const mode = routeOrder % 2 === 0 ? 'BUS' : 'WALK';
            const transportType = mode === 'BUS' ? 'PUBLIC_TRANSPORT' : 'WALK';

            sql.push(insertLine('route_segments', [
                'id',
                'travel_plan_id',
                'from_itinerary_item_id',
                'to_itinerary_item_id',
                'transport_type',
                'duration_minutes',
                'distance_meter',
                'total_fare_amount',
                '`order`',
                'created_at',
                'updated_at',
            ], [
                routeSegmentId,
                trip.travelPlanId,
                dayItems[index].itemId,
                dayItems[index + 1].itemId,
                quote(transportType),
                mode === 'BUS' ? 18 : 9,
                mode === 'BUS' ? 4200 : 700,
                mode === 'BUS' ? 1500 : 0,
                routeOrder,
                quote(trip.createdAt),
                quote(trip.updatedAt),
            ]));

            sql.push(insertLine('route_segment_legs', [
                'id',
                'route_segment_id',
                'sequence',
                'mode',
                'bus_numbers',
                'subway_lines',
                'boarding_stop_name',
                'boarding_station_number',
                'alighting_stop_name',
                'alighting_station_number',
                'duration_minute',
                'distance_meter',
            ], [
                routeLegId,
                routeSegmentId,
                1,
                quote(mode),
                quote(mode === 'BUS' ? JSON.stringify([`BUS-${100 + routeOrder}`]) : JSON.stringify([])),
                quote(JSON.stringify([])),
                mode === 'BUS' ? quote(`Stop ${routeOrder}A`) : 'NULL',
                'NULL',
                mode === 'BUS' ? quote(`Stop ${routeOrder}B`) : 'NULL',
                'NULL',
                mode === 'BUS' ? 18 : 9,
                mode === 'BUS' ? 4200 : 700,
            ]));

            routeOrder += 1;
        }
    }

    return itemIds[0].itemId;
}

function buildFixture(options) {
    const sql = [];
    const testUsers = [];
    const counters = {
        dayId: 1,
        itemId: 1,
        placeId: 1,
        routeLegId: 1,
        routeSegmentId: 1,
        travelPlanPlaceId: 1,
    };

    sql.push('-- Local read-performance fixture.');
    sql.push('-- Generated data shape: users -> travel_plans -> representative itineraries/routes.');
    sql.push('');

    for (let userId = 1; userId <= options.users; userId += 1) {
        const createdAt = mysqlDateTime(new Date(BASE_CREATED_AT - (userId * 1_000)));
        sql.push(insertLine('users', [
            'id',
            'nickname',
            'profile_image_url',
            'status',
            'created_at',
            'updated_at',
            'deleted_at',
        ], [
            userId,
            quote(`loaduser${String(userId).padStart(3, '0')}`),
            'NULL',
            quote('ACTIVE'),
            quote(createdAt),
            quote(createdAt),
            'NULL',
        ]));
    }
    sql.push('');

    for (let userId = 1; userId <= options.users; userId += 1) {
        let representativeTrip = null;

        for (let tripIndex = 1; tripIndex <= options.travelsPerUser; tripIndex += 1) {
            const trip = appendCoreTripRows(sql, userId, tripIndex, options.travelsPerUser);
            if (tripIndex === 1) {
                representativeTrip = trip;
            }
        }

        const itineraryItemId = appendRepresentativeItineraryRows(sql, counters, representativeTrip);
        testUsers.push({
            userId,
            accessToken: '',
            travelPlanId: representativeTrip.travelPlanId,
            itineraryItemId,
            generationJobId: representativeTrip.travelPlanId,
        });
    }

    return {
        sql,
        testData: { users: testUsers },
    };
}

const options = readArguments(process.argv.slice(2));
const regionsSql = readRegionsSql();
const fixture = buildFixture(options);

const dataSql = [
    '-- Generated by load-test/scripts/initData/generate-local-read-fixture.mjs',
    '-- Keep src/main/resources/data.regions.sql as the clean region seed source.',
    regionsSql,
    '',
    ...fixture.sql,
].join('\n') + '\n';

writeAtomically(DATA_SQL_PATH, dataSql);
writeAtomically(TEST_IDS_PATH, `${JSON.stringify(fixture.testData, null, 2)}\n`, 0o600);

console.log(`Generated ${options.users} users.`);
console.log(`Generated ${options.users * options.travelsPerUser} travel plans.`);
console.log(`Generated ${options.users} representative itineraries for detail-read tests.`);
console.log(`Wrote ${DATA_SQL_PATH}`);
console.log(`Wrote ${TEST_IDS_PATH}`);
