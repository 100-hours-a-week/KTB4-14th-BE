import { get } from '../../lib/api.js';
import { checkApiResponse } from '../../lib/checks.js';
import { currentUser, requireField } from '../../lib/test-data.js';
import { measurementParams } from '../../lib/single-api.js';

export function runSingleRead(path, name) {
    const user = currentUser();
    const token = requireField(user, 'accessToken');
    checkApiResponse(get(typeof path === 'function' ? path(user) : path, 'API-SINGLE', token, name, measurementParams), 200);
}
