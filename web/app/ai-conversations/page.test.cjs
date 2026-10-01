const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

test('starter suggestions stay editable and loading preserves the conversation frame', () => {
  const conversation = { id: 'preview', title: '项目练习', contextSources: [] };
  let source = readFileSync(`${__dirname}/page.tsx`, 'utf8');
  for (const [name, value] of Object.entries({ conversations: [conversation], detail: { conversation, messages: [] }, selectedConversationId: conversation.id, loading: false })) {
    source = source.replace(new RegExp('(const \\[' + name + ',[\\s\\S]*?=\\s*)useState[^;]+;'), (_, prefix) => prefix + 'useState(' + JSON.stringify(value) + ');');
  }
  const state = [], nodes = [];
  let index = 0, focused, requests = 0;
  function compile(code, document = {}) {
    const output = { exports: {} };
    const compiled = ts.transpileModule(code, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText;
    new Function('require', 'module', 'exports', 'document', compiled)((id) => {
      if (id === 'react') return { ...React, useEffect() {}, useCallback: (callback) => callback, useRef: (value) => ({ current: value }), useState(initial) {
        const key = index++;
        if (!(key in state)) state[key] = initial;
        return [state[key], (value) => { state[key] = typeof value === 'function' ? value(state[key]) : value; }];
      } };
      if (id === 'react/jsx-runtime') {
        const runtime = require(id);
        const capture = (jsx) => (tag, props, key) => { nodes.push({ tag, props }); return jsx(tag, props, key); };
        return { ...runtime, jsx: capture(runtime.jsx), jsxs: capture(runtime.jsxs) };
      }
      if (id === '@/features/ai-conversations') return compile(readFileSync(`${__dirname}/../../features/ai-conversations.ts`, 'utf8'));
      if (id === '@/components/AppShell') return ({ children }) => children;
      if (id.startsWith('@/components/')) return () => null;
      if (id === '@/lib/api') return { api() { requests++; }, streamApi() { requests++; } };
      return require(id);
    }, output, output.exports, document);
    return output.exports;
  }
  const Page = compile(source, { getElementById: (id) => ({ focus() { focused = id; } }) }).default;
  function render() { index = 0; nodes.length = 0; return renderToStaticMarkup(React.createElement(Page)); }
  function draft() { return nodes.find(({ tag }) => tag === 'textarea').props; }
  render();
  for (const title of ['梳理项目亮点', '复盘面试表现', '制定训练计划']) {
    nodes.find(({ tag, props }) => tag === 'button' && props.children?.[1]?.props?.children?.[0] === title).props.onClick();
    render();
    assert.ok(draft().value.length > 10);
    assert.equal(focused, 'chat-draft');
    assert.equal(requests, 0);
  }
  draft().onChange({ target: { value: '我想先练习项目介绍' } });
  render();
  assert.equal(draft().value, '我想先练习项目介绍');
  for (const loadingIndex of [17, 11]) {
    state[loadingIndex] = true;
    state[1] = undefined;
    const html = render();
    for (const className of ['chat-sidebar-panel', 'chat-heading', 'chat-composer']) assert.match(html, new RegExp(className));
    assert.match(html, /home-robot-loop\.gif/);
    assert.equal(draft().disabled, true);
    assert.match(html, /正在加载对话/);
    assert.equal(requests, 0);
    state[loadingIndex] = false;
  }
});
