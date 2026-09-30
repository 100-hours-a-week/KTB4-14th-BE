// k6 실행 시 전달받는 환경변수를 읽어
// 부하테스트 전체에서 공통으로 사용할 설정을 관리한다.

// 테스트 대상 Backend 주소
// 예: https://staging-api.audigo.kr
const BASE_URL = __ENV.BASE_URL;

// 테스트 사용자에게 미리 발급한 Access Token
// 카카오 로그인을 k6에서 직접 수행하지 않고,
// Staging JWT Secret으로 발급한 테스트용 JWT를 사용한다.
const ACCESS_TOKEN = __ENV.ACCESS_TOKEN;

// 하나의 부하테스트 실행을 식별하기 위한 ID
// 별도로 전달하지 않으면 실행 시점 기준으로 자동 생성한다.
const TEST_RUN_ID = __ENV.TEST_RUN_ID || `local-${Date.now()}`;


// BASE_URL 없이 테스트가 실행되는 것을 방지한다.
if (!BASE_URL) {
    throw new Error('BASE_URL is required');
}

// k6 JavaScript runtime에서는 브라우저 URL API를 보장하지 않으므로,
// HTTP(S) 절대 URL과 host만 가볍게 검증한다.
const urlMatch = BASE_URL.match(/^https?:\/\/([^/:?#]+)(?::\d+)?(?:[/?#]|$)/i);
if (!urlMatch) {
    throw new Error(`BASE_URL must be an absolute URL: ${BASE_URL}`);
}

// 설계상 운영 API에는 어떠한 부하 요청도 보내지 않는다. 이 차단은
// 환경변수로 우회할 수 없으며, Staging 실행 의도를 명시해야 한다.
if (urlMatch[1].toLowerCase() === 'api.audigo.kr') {
    throw new Error(`Load test against production is blocked: ${BASE_URL}`);
}

if (__ENV.CONFIRM_STAGING !== 'true') {
    throw new Error('CONFIRM_STAGING=true is required');
}


// 다른 파일에서 사용할 공통 설정
export const config = {

    // 마지막 "/"를 제거해서
    // `${baseUrl}/api/...` 형태로 URL을 일정하게 만든다.
    baseUrl: BASE_URL.replace(/\/$/, ''),

    // 테스트용 JWT
    accessToken: ACCESS_TOKEN,

    // 현재 부하테스트 실행 식별자
    testRunId: TEST_RUN_ID,
};
