#!/usr/bin/env node
import { createServer } from 'node:http';

const host = '127.0.0.1';
const port = Number(process.env.TAGO_STUB_PORT || 18080);
const delayMs = Number(process.env.TAGO_STUB_DELAY_MS || 0);
if (!Number.isSafeInteger(port) || port < 1024 || port > 65535
        || !Number.isSafeInteger(delayMs) || delayMs < 0 || delayMs > 5000) {
    throw new Error('TAGO_STUB_PORT must be 1024..65535 and TAGO_STUB_DELAY_MS must be 0..5000');
}

const calls = { cities: 0, stops: 0, arrivals: 0, unknown: 0 };
const body = (items) => ({ response: {
    header: { resultCode: '00', resultMsg: 'NORMAL SERVICE' },
    body: { totalCount: items.length, items: { item: items } },
} });

const server = createServer(async (request, response) => {
    const url = new URL(request.url, `http://${host}:${port}`);
    if (url.pathname === '/__admin/metrics' && request.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(calls));
        return;
    }
    if (url.pathname === '/__admin/reset' && request.method === 'POST') {
        for (const key of Object.keys(calls)) calls[key] = 0;
        response.writeHead(204);
        response.end();
        return;
    }

    let payload;
    if (request.method === 'GET' && url.pathname === '/stops/getCtyCodeList') {
        calls.cities += 1;
        payload = body([{ citycode: '11', cityname: '서울특별시' }]);
    } else if (request.method === 'GET' && url.pathname === '/stops/getSttnNoList') {
        calls.stops += 1;
        const stopName = url.searchParams.get('nodeNm') || '';
        const match = /^캐시정류장(\d{3})$/.exec(stopName);
        payload = body(match && url.searchParams.get('cityCode') === '11'
            ? [{ citycode: '11', nodeid: `CACHE-${match[1]}`, nodenm: stopName, nodeno: match[1] }]
            : []);
    } else if (request.method === 'GET' && url.pathname === '/arrivals') {
        calls.arrivals += 1;
        payload = body(url.searchParams.get('cityCode') === '11'
                && /^CACHE-\d{3}$/.test(url.searchParams.get('nodeId') || '')
            ? [{ routeid: 'CACHE-643', routeno: '643', arrprevstationcnt: 3, vehicletp: '일반', arrtime: 480 }]
            : []);
    } else {
        calls.unknown += 1;
        response.writeHead(404);
        response.end();
        return;
    }
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
    response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify(payload));
});

server.listen(port, host, () => console.log(`TAGO test stub: http://${host}:${port}`));
