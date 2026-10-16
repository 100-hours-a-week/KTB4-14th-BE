import { config } from './config.js';


// 인증이 필요한 Backend API 요청에 사용할 Header를 생성한다.
//
// 카카오 OAuth를 k6에서 직접 수행하지 않는다.
// Staging DB에 테스트 사용자를 준비하고,
// 해당 userId로 미리 발급한 테스트용 JWT를 사용한다.
export function authHeaders(accessToken) {

    const token = accessToken || config.accessToken;

    // 인증이 필요한 테스트인데 Access Token이 없다면
    // 잘못된 상태로 부하테스트가 실행되지 않도록 즉시 중단한다.
    if (!token) {
        throw new Error('A per-user accessToken or ACCESS_TOKEN is required');
    }

    return {

        // Backend의 JwtAuthenticationFilter가 읽을 Access Token
        //
        // Backend에서는 JWT의
        // sub → userId
        // token_type → access
        // exp → 만료 여부
        // 를 확인해서 사용자를 인증한다.
        Authorization: `Bearer ${token}`,

        // JSON 요청 Body 사용
        'Content-Type': 'application/json',

        // CloudWatch/Spring 로그에서
        // 현재 부하테스트 실행을 식별하기 위한 값
        'X-Test-Run-Id': config.testRunId,
    };
}
