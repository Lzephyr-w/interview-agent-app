const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { test } = require("node:test");
const ts = require("typescript");

const source = readFileSync(require.resolve("./api.ts"), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const apiExports = {};
new Function("require", "exports", compiled)(
  () => ({ getSession: () => ({ accessToken: "test" }), signOut: () => {} }),
  apiExports,
);
const { api } = apiExports;

test("identical writes share a request until it settles", async () => {
  let calls = 0;
  let release;
  global.fetch = async () => {
    calls++;
    await new Promise((resolve) => { release = resolve; });
    return { ok: true, status: 200, json: async () => ({ saved: true }) };
  };
  const options = { method: "POST", body: '{"value":1}' };
  const first = api("/test/save", options);
  const second = api("/test/save", options);
  assert.strictEqual(first, second);
  assert.equal(calls, 1);
  release();
  await Promise.all([first, second]);
  const third = api("/test/save", options);
  assert.equal(calls, 2);
  release();
  await third;
});

test("different writes remain independent", async () => {
  let calls = 0;
  global.fetch = async () => {
    calls++;
    return { ok: true, status: 200, json: async () => ({}) };
  };
  await Promise.all([
    api("/test/other", { method: "POST", body: '{"value":1}' }),
    api("/test/other", { method: "POST", body: '{"value":2}' }),
  ]);
  assert.equal(calls, 2);
});

test("a failed write can be retried", async () => {
  let calls = 0;
  global.fetch = async () => {
    calls++;
    return calls === 1
      ? { ok: false, status: 500, json: async () => ({ message: "failed" }) }
      : { ok: true, status: 200, json: async () => ({ saved: true }) };
  };
  const options = { method: "POST" };
  await assert.rejects(api("/test/retry", options));
  await api("/test/retry", options);
  assert.equal(calls, 2);
});
