import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import execution from 'k6/execution';

const base = __ENV.BASE_URL || 'http://localhost:8080';
const vus = Number(__ENV.VUS || 10);
const iterations = Number(__ENV.ITERATIONS || 100);
const sharedPair = __ENV.SHARED_PAIR === 'true';
const completed = new Counter('logical_transfers');
const invariantFailures = new Rate('invariant_failures');
const latency = new Trend('transfer_latency', true);
export const options = {
  scenarios: { transfers: { executor: 'per-vu-iterations', vus, iterations, maxDuration: '5m' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    invariant_failures: ['rate==0'],
    transfer_latency: ['p(95)<1000', 'p(99)<2000'],
    dropped_iterations: ['count==0'],
  },
};
const headers = {'Content-Type': 'application/json'};
function post(path, body, key) {
  return http.post(`${base}/api${path}`, JSON.stringify(body), {headers: {...headers, ...(key && {'Idempotency-Key': key})}, tags: {name: path}});
}
function body(response, status) {
  if (!check(response, {[`HTTP ${status}`]: r => r.status === status})) fail(response.body);
  return response.json();
}
export function setup() {
  const run = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const source = '018f0000-0000-7000-8000-000000000001';
  const pairs = [];
  for (let i = 0; i < (sharedPair ? 1 : vus); i++) {
    const a = body(post('/accounts', {name: `Load sender ${run}-${i}`, currency: 'INR'}), 201);
    const b = body(post('/accounts', {name: `Load recipient ${run}-${i}`, currency: 'INR'}), 201);
    body(post('/transfers', {fromAccountId: source, toAccountId: a.id, amountMinor: '10000'}, `${run}-fund-${i}`), 201);
    pairs.push({a: a.id, b: b.id});
  }
  return {run, pairs};
}
export default function(data) {
  const index = execution.vu.idInTest - 1;
  const iteration = execution.vu.iterationInScenario;
  const pair = data.pairs[sharedPair ? 0 : index];
  const payload = {fromAccountId: iteration % 2 === 0 ? pair.a : pair.b, toAccountId: iteration % 2 === 0 ? pair.b : pair.a, amountMinor: '1'};
  const key = `${data.run}-${index}-${iteration}`;
  const params = {headers: {...headers, 'Idempotency-Key': key}, tags: {name: 'POST /api/transfers'}};
  const responses = http.batch([
    ['POST', `${base}/api/transfers`, JSON.stringify(payload), params],
    ['POST', `${base}/api/transfers`, JSON.stringify(payload), params],
  ]);
  for (const response of responses) latency.add(response.timings.duration);
  const valid = check(responses, {
    'both parallel requests return 201': rs => rs.every(r => r.status === 201),
    'both responses identify one transfer': rs => rs.every(r => r.status === 201) && rs[0].json('id') === rs[1].json('id'),
    'one new response and one replay': rs => rs.map(r => r.headers['Idempotency-Replayed']).sort().join(',') === 'false,true',
  });
  invariantFailures.add(!valid);
  if (valid) completed.add(1);
}
export function teardown(data) {
  for (const pair of data.pairs) {
    const a = body(http.get(`${base}/api/accounts/${pair.a}`), 200);
    const b = body(http.get(`${base}/api/accounts/${pair.b}`), 200);
    // These bounded balances remain within Number's exact integer range.
    const expectedB = (iterations % 2) * (sharedPair ? vus : 1);
    const expectedCount = iterations * (sharedPair ? vus : 1);
    invariantFailures.add(!check([a, b], {
      'per-account balance matches every logical movement': values => Number(values[0].balanceMinor) === 10000 - expectedB && Number(values[1].balanceMinor) === expectedB,
    }));
    const transfers = body(http.get(`${base}/api/transfers?accountId=${pair.b}&size=1`), 200);
    invariantFailures.add(!check(transfers, {'exactly one transfer per parallel pair': value => value.totalElements === expectedCount}));
    const entries = body(http.get(`${base}/api/accounts/${pair.b}/entries?size=1`), 200);
    invariantFailures.add(!check(entries, {'exactly one recipient journal entry per transfer': value => value.totalElements === expectedCount}));
  }
  const reconciliation = body(http.get(`${base}/api/system/reconciliation`), 200);
  invariantFailures.add(!check(reconciliation, {'ledger balances and nets to zero': result => result.balanced && result.mismatches.length === 0 && result.currencies.every(c => c.netMinor === '0')}));
}
export function handleSummary(data) {
  const summary = {runAt: new Date().toISOString(), vus, sharedPair, iterationsPerVu: iterations, logicalTransfersExpected: vus * iterations, ...data};
  return {[`/results/${__ENV.RESULT_FILE || 'latest.json'}`]: JSON.stringify(summary, null, 2), stdout: JSON.stringify({logicalTransfers: data.metrics.logical_transfers?.values, latency: data.metrics.transfer_latency?.values, checks: data.metrics.checks?.values, invariantFailures: data.metrics.invariant_failures?.values}, null, 2) + '\n'};
}
