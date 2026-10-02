const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const tick = () => new Promise(resolve => setImmediate(resolve));

function page(active, created) {
  const values = [], refs = [], props = new Map();
  let stateIndex = 0, refIndex = 0, effects = [];
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/page.tsx`, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const output = { exports: {} };
  new Function('require', 'module', 'exports', compiled)(id => {
    if (id === 'react') return { ...React,
      useState: initial => {
        const index = stateIndex++;
        if (!(index in values)) values[index] = initial;
        return [values[index], next => { values[index] = typeof next === 'function' ? next(values[index]) : next; }];
      },
      useRef: initial => { const index = refIndex++; refs[index] ??= { current: initial }; return refs[index]; },
      useEffect: effect => effects.push(effect),
    };
    if (id === '@/lib/api') return { api: async (url, options) => {
      if (url === '/api/v1/mock-interviews') return options?.method === 'POST' ? created : { active };
      return url.endsWith('interview-packages') ? [{ id: 'pack' }] : [];
    } };
    if (id.startsWith('@/components/') || id === 'next/link') return id;
    if (id === 'react/jsx-runtime') {
      const runtime = require(id);
      const capture = jsx => (tag, properties, key) => { props.set(tag, properties); return jsx(tag, properties, key); };
      return { ...runtime, jsx: capture(runtime.jsx), jsxs: capture(runtime.jsxs) };
    }
    return require(id);
  }, output, output.exports);
  function render() {
    stateIndex = refIndex = 0;
    effects = [];
    output.exports.default();
    effects.at(-1)(); // Run the question announcement after each session update.
  }
  render();
  return { props, render, mount: () => { effects[0](); effects[2](); } };
}

test('resuming never announces a new first question; new interviews still do', async () => {
  const first = { id: 'first', questionKind: 'MAIN', state: 'OPEN', answerText: '' };
  const session = { id: 'session', status: 'RUNNING', currentQuestionIndex: 1, currentQuestion: first, questions: [first], task: null };
  for (const [index, kind] of [[1, 'MAIN'], [1, 'FOLLOW_UP'], [3, 'MAIN']]) {
    const restored = { ...session, currentQuestionIndex: index, currentQuestion: { ...first, questionKind: kind } };
    const view = page(restored);
    view.mount(); await tick(); view.render();
    const dialog = view.props.get('@/components/ConfirmDialog');
    assert.equal(dialog.open, true);
    assert.equal(dialog.confirmLabel, '继续面试');
    dialog.onConfirm();
    view.render(); view.render();
    assert.equal(view.props.get('@/components/Toast').notice, '已恢复上一次未完成的文本模拟。');
    assert.equal(view.props.get('@/components/ConfirmDialog').open, false);
  }
  const fresh = page(null, session);
  fresh.mount(); await tick(); fresh.render();
  await fresh.props.get('form').onSubmit({ preventDefault() {} });
  fresh.render(); fresh.render();
  assert.equal(fresh.props.get('@/components/Toast').notice, 'AI 已生成第一题。');
  fresh.props.get('@/components/Toast').onDismissNotice();
  fresh.render(); fresh.render();
  assert.equal(fresh.props.get('@/components/Toast').notice, '');
});
