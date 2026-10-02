const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const pack = { id: 'pack', company: '公司', role: '开发', interviewRound: '一面' };
const ready = { id: 'prepared', status: 'RUNNING', sourceMode: 'KNOWLEDGE', generationVersion: 'KNOWLEDGE_INCREMENTAL_V1', prepared: true, currentQuestion: null, task: null };
const tick = () => new Promise(resolve => setImmediate(resolve));

function page(file, seeds, api, search = '?packageId=pack&sourceMode=KNOWLEDGE&categoryIds=B') {
  const values = [], refs = [], effects = [], navigations = [];
  let stateIndex = 0, refIndex = 0;
  const fakeReact = { ...React,
    useState: initial => { const index = stateIndex++; if (!(index in values)) values[index] = index in seeds ? seeds[index] : initial; return [values[index], next => { values[index] = typeof next === 'function' ? next(values[index]) : next; }]; },
    useRef: initial => { const index = refIndex++; refs[index] ??= { current: initial }; return refs[index]; },
    useEffect: effect => effects.push(effect),
  };
  const data = new Map();
  global.window = { location: { search, assign: url => navigations.push(url) }, sessionStorage: { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key) }, addEventListener() {}, removeEventListener() {}, MediaRecorder: function() {} };
  Object.defineProperty(global, 'navigator', { configurable: true, value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) } } });
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/${file}`, 'utf8'), { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
  const output = { exports: {} };
  new Function('require', 'module', 'exports', compiled)(id => {
    if (id === 'react') return fakeReact;
    if (id === 'next/navigation') return { useRouter: () => ({ push: url => navigations.push(url) }) };
    if (id === 'next/link' || id.startsWith('@/components/')) return () => null;
    if (id === '@/lib/api') return { api, ApiError: class extends Error {} };
    if (id === '@/lib/audio') return {};
    return require(id);
  }, output, output.exports);
  let tree = output.exports.default();
  function find(node, text) {
    if (!node) return null;
    if (Array.isArray(node)) return node.map(item => find(item, text)).find(Boolean);
    if (node.type === 'button' && (node.props['aria-label'] === text || JSON.stringify(node.props.children).includes(text))) return node;
    if (node.props?.open && node.props.confirmLabel === text) return { props: { onClick: node.props.onConfirm } };
    return find(node.props?.children, text);
  }
  return { values, data, navigations, effects, button: text => { stateIndex = refIndex = 0; tree = output.exports.default(); const found = find(tree, text); assert.ok(found, text); return found; }, mount: () => effects.map(effect => effect()) };
}

test('knowledge selection and entry navigation never prepare; room initialization prepares once', async () => {
  const calls = [];
  const api = async (url, options) => { calls.push([url, options]); return url.endsWith('prepare') ? ready : url.endsWith('interview-packages') ? [pack] : []; };
  const picker = page('page.tsx', [[pack], [], [], pack.id, 'KNOWLEDGE', ['B']], api);
  picker.mount();
  await picker.button('进入面试室').props.onClick();
  assert.equal(calls.filter(([url]) => url.endsWith('/prepare')).length, 0);
  assert.match(picker.navigations[0], /categoryIds=B/);
  const room = page('room/page.tsx', [pack], api);
  room.mount(); await tick();
  const prepares = calls.filter(([url]) => url.endsWith('/prepare'));
  assert.equal(prepares.length, 1);
  assert.deepEqual(JSON.parse(prepares[0][1].body).categoryIds, ['B']);
  await room.button('开始模拟面试').props.onClick(); await tick();
  assert.equal(calls.filter(([url]) => url.endsWith('/prepare')).length, 1);
  assert.equal(calls.filter(([url]) => url.endsWith('/begin')).length, 1);
});

test('leaving the welcome page cancels pending preparation and ignores its late response', async () => {
  const calls = []; let release;
  const api = async (url, options) => {
    calls.push([url, options]);
    if (url.endsWith('/prepare')) return new Promise(resolve => { release = resolve; });
    if (url.endsWith('interview-packages')) return [pack];
    return {};
  };
  const room = page('room/page.tsx', [pack], api);
  room.mount(); await tick();
  room.button('退出模拟').props.onClick();
  room.button('退出').props.onClick();
  release(ready); await tick();
  assert.equal(room.values[1], undefined);
  assert.ok(calls.some(([url, options]) => url.endsWith('/prepared') && options?.method === 'DELETE'));
  assert.equal(room.data.size, 0);
  assert.deepEqual(room.navigations, ['/ai-mock-interviews']);
});

test('room prepares before package display loads and welcome exit cleans the late response', async () => {
  let releasePackage, releasePrepare; const calls = [];
  const room = page('room/page.tsx', [pack], async (url, options) => {
    calls.push([url, options]);
    if (url.endsWith('/prepare')) return new Promise(resolve => { releasePrepare = resolve; });
    if (url.endsWith('/interview-packages')) return new Promise(resolve => { releasePackage = resolve; });
    return {};
  });
  room.mount();
  assert.equal(calls.filter(([url]) => url.endsWith('/prepare')).length, 1);
  room.button('退出模拟').props.onClick(); room.button('退出').props.onClick(); await tick();
  releasePrepare(ready); await tick();
  releasePackage([pack]); await tick();
  assert.ok(calls.some(([url, options]) => url.endsWith('/prepared') && options?.method === 'DELETE'));
  assert.equal(room.data.size, 0);
  assert.deepEqual(room.navigations, ['/ai-mock-interviews']);
});

test('old prepared versions are replaced; started old sessions still resume', async () => {
  for (const started of [false, true]) {
    const calls = [];
    const room = page('room/page.tsx', [pack], async (url, options) => {
      calls.push([url, options]);
      if (url.endsWith('interview-packages')) return [pack];
      if (url.endsWith('/old') && !options) return { ...ready, id: 'old', generationVersion: 'SIMULATION_AGENT_V1', prepared: !started };
      return ready;
    });
    room.data.set(`ai-mock-${started ? 'session' : 'prepared'}:pack:KNOWLEDGE:B`, 'old');
    room.mount(); await tick();
    assert.equal(calls.filter(([url]) => url.endsWith('/prepare')).length, started ? 0 : 1);
    assert.equal(calls.filter(([, options]) => options?.method === 'DELETE').length, started ? 0 : 1);
    if (started) assert.equal(room.values[3], true);
  }
});
