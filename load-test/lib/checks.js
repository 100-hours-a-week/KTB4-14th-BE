import { check } from 'k6';


// HTTP Status Code만 확인하는 간단한 검증 함수
//
// 기본 예상 Status는 200이다.
//
// 사용 예:
// checkStatus(response);       → 200 확인
// checkStatus(response, 204);  → 204 확인
export function checkStatus(response, expectedStatus = 200) {

    return check(response, {

        // 실제 응답 Status가 기대한 Status와 같은지 확인한다.
        [`status is ${expectedStatus}`]: (res) =>
            res.status === expectedStatus,
    });
}


// 정상적인 HTTP 응답이 왔는지 기본적인 항목을 함께 확인한다.
//
// 아직 응답시간 기준(p95 < 500ms 등)은 넣지 않는다.
// Smoke/Baseline 테스트 결과를 확인한 뒤
// 실제 성능 목표를 기준으로 Threshold를 별도로 결정한다.
export function checkSuccess(response, expectedStatus = 200) {

    return check(response, {

        // 기대한 HTTP Status인지 확인
        [`status is ${expectedStatus}`]: (res) =>
            res.status === expectedStatus,

        // k6가 정상적으로 응답시간을 측정했는지 확인
        'response time is recorded': (res) =>
            res.timings.duration >= 0,

        // 응답 Body가 존재하는지 확인
        //
        // 204 No Content처럼 Body가 없는 API에는
        // checkSuccess 대신 checkStatus를 사용한다.
        'response body exists': (res) =>
            res.body !== null && res.body !== undefined,
    });
}

// Audigo API 공통 응답 형식({ message, data })까지 확인한다.
export function checkApiResponse(response, expectedStatus = 200) {
    return check(response, {
        [`status is ${expectedStatus}`]: (res) => res.status === expectedStatus,
        'response time is recorded': (res) => res.timings.duration >= 0,
        'response has API envelope': (res) => {
            try {
                const body = res.json();
                return body
                    && typeof body.message === 'string'
                    && Object.prototype.hasOwnProperty.call(body, 'data');
            } catch (_) {
                return false;
            }
        },
    });
}

export function responseData(response) {
    try {
        return response.json('data');
    } catch (_) {
        return null;
    }
}
