import http from 'k6/http';
import { check, sleep } from 'k6';
// Opt-in, isolated environment only. Never run against production without an approved load window.
if (!__ENV.BASE_URL || !__ENV.PUBLIC_GROUPAGE_ID) throw new Error('BASE_URL and PUBLIC_GROUPAGE_ID required');
if (__ENV.ALLOW_LOAD !== 'isolated-environment') throw new Error('Explicit isolated-environment opt-in required');
export const options = { vus: 5, duration: '30s', thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'] } };
export default function () {
 const response = http.get(`${__ENV.BASE_URL}/api/public/groupings/${encodeURIComponent(__ENV.PUBLIC_GROUPAGE_ID)}`, { tags: { name: 'public-groupage' } });
 check(response, { 'public groupage readable': r => r.status === 200 });
 sleep(1);
}
