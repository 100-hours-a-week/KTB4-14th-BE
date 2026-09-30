import { SharedArray } from 'k6/data';

const dataFile = __ENV.TEST_DATA_FILE || '../data/test-ids.json';

const testData = new SharedArray('load-test-users', () => {
    const parsed = JSON.parse(open(dataFile));
    if (!Array.isArray(parsed.users) || parsed.users.length === 0) {
        throw new Error(`${dataFile} must contain at least one user`);
    }
    return parsed.users;
});

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

export function requireField(user, field) {
    if (user[field] === undefined || user[field] === null || user[field] === '') {
        throw new Error(`Test user ${user.userId || __VU} is missing ${field}`);
    }
    return user[field];
}
