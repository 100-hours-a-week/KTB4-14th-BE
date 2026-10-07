import { SharedArray } from 'k6/data';

const dataFile = __ENV.TEST_DATA_FILE || '../data/test-ids.json';

const testData = new SharedArray('load-test-users', () => {
    const parsed = JSON.parse(open(dataFile));
    if (!Array.isArray(parsed.users) || parsed.users.length === 0) {
        throw new Error(`${dataFile} must contain at least one user`);
    }
    return parsed.users;
});

export function testUserCount() {
    return testData.length;
}

// 읽기 시나리오는 VU가 데이터 수보다 많아도 계정을 순환할 수 있다.
// 쓰기/SSE 시나리오는 같은 사용자 또는 일정 항목의 동시 사용을 막는다.
export function currentUser({ unique = false } = {}) {
    if (unique && __VU > testData.length) {
        throw new Error(
            `Scenario needs at least ${__VU} users, but ${testData.length} are configured in ${dataFile}`
        );
    }

    return testData[(__VU - 1) % testData.length];
}

// 쓰기 테스트에서는 VU가 재사용되어도 각 iteration이 서로 다른 항목을 사용해야 한다.
// iterationInTest는 시나리오 전체에서 증가하므로 한 실행 내 중복 완료 처리를 막는다.
export function userForIteration(iterationInTest, { unique = false } = {}) {
    if (!Number.isSafeInteger(iterationInTest) || iterationInTest < 0) {
        throw new Error(`Invalid scenario iteration index: ${iterationInTest}`);
    }
    if (unique && iterationInTest >= testData.length) {
        throw new Error(
            `Scenario iteration ${iterationInTest + 1} needs another unique user, but only ${testData.length} are configured`
        );
    }
    return testData[iterationInTest % testData.length];
}

export function requireField(user, field) {
    if (user[field] === undefined || user[field] === null || user[field] === '') {
        throw new Error(`Test user ${user.userId || __VU} is missing ${field}`);
    }
    return user[field];
}
