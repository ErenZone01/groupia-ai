const { test } = require('node:test');
const assert = require('node:assert/strict');
const headers = require('./security-headers.cjs');
test('media CSP includes blob previews and exact configured origin only', () => {
  const csp = headers('test-nonce', true, 'https://images.example.test/products')['Content-Security-Policy'];
  assert.ok(csp.includes("img-src 'self' data: blob: https://images.example.test;"));
  assert.ok(!csp.includes('https:;'));
  assert.ok(headers('test', false, '')['Content-Security-Policy'].includes("img-src 'self' data: blob:;"));
});
test('media CSP rejects unsafe configuration', () => {
  for (const value of ['https://*.example.test', 'http://images.example.test', 'https://user:pass@images.example.test', 'https://images.example.test/?query=x'])
    assert.throws(() => headers('test', true, value));
});
