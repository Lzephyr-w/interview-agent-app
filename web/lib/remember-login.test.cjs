const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { test } = require("node:test");
const ts = require("typescript");

const compiled = ts.transpileModule(readFileSync(require.resolve("./remember-login.ts"), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
function loadModule() {
  const exports = {};
  new Function("exports", compiled)(exports);
  return exports;
}

test("remembered credentials survive reload, stay encrypted, and can be forgotten", async () => {
  let saved;
  let unavailable = false;
  global.indexedDB = {
    open() {
      const request = {};
      queueMicrotask(() => {
        if (unavailable) {
          request.error = new Error("Storage unavailable");
          request.onerror();
          return;
        }
        request.result = {
          close() {},
          transaction() {
            const transaction = {};
            const operation = (action) => {
              const result = {};
              queueMicrotask(() => {
                result.result = action();
                transaction.oncomplete();
              });
              return result;
            };
            transaction.objectStore = () => ({
              get: () => operation(() => structuredClone(saved)),
              put: (value) => operation(() => { saved = structuredClone(value); }),
              delete: () => operation(() => { saved = undefined; }),
            });
            return transaction;
          },
        };
        request.onsuccess();
      });
      return request;
    },
  };
  try {
    const credentials = { email: "remember-check@example.invalid", password: "测试密码-123" };
    let api = loadModule();
    assert.equal(await api.loadRememberedLogin(), null);
    await api.rememberLogin(credentials.email, credentials.password);
    assert.equal(saved.key.extractable, false);
    await assert.rejects(crypto.subtle.exportKey("raw", saved.key));
    assert.equal(new TextDecoder().decode(saved.data).includes(credentials.password), false);
    api = loadModule();
    assert.deepEqual(await api.loadRememberedLogin(), credentials);
    const firstIv = saved.iv;
    await api.rememberLogin(credentials.email, "changed-password");
    assert.notDeepEqual(saved.iv, firstIv);
    assert.equal((await api.loadRememberedLogin()).password, "changed-password");
    new Uint8Array(saved.data)[0] ^= 1;
    assert.equal(await api.loadRememberedLogin(), null);
    assert.equal(saved, undefined);
    await api.rememberLogin(credentials.email, credentials.password);
    await api.forgetRememberedLogin();
    assert.equal(await api.loadRememberedLogin(), null);
    unavailable = true;
    assert.equal(await api.loadRememberedLogin(), null);
    await assert.rejects(api.rememberLogin(credentials.email, credentials.password), /取消“记住密码”后重试/);
  } finally {
    delete global.indexedDB;
  }
});
