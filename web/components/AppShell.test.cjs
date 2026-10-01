const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

test('logout requires confirmation; cancellation keeps the session and page', () => {
  let open = false, logoutButton, dialog;
  const calls = [];
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/AppShell.tsx`, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const output = { exports: {} };
  new Function('require', 'module', 'exports', compiled)((id) => {
    if (id === 'react') return { ...React, useState: () => [open, (value) => { open = value; }] };
    if (id === 'next/navigation') return {
      usePathname: () => '/',
      useRouter: () => ({ replace: (path) => calls.push(path), refresh: () => calls.push('refresh') }),
    };
    if (id === 'next/link') return ({ children, ...props }) => React.createElement('a', props, children);
    if (id === '@/components/ConfirmDialog') return (props) => { dialog = props; return null; };
    if (id.startsWith('@/components/')) return () => null;
    if (id === '@/lib/auth') return { signOut: () => { calls.push('signOut'); return Promise.resolve(); } };
    if (id === 'react/jsx-runtime') {
      const runtime = require(id);
      const capture = (jsx) => (tag, props, key) => {
        if (tag === 'button' && props.children === '退出登录') logoutButton = props;
        return jsx(tag, props, key);
      };
      return { ...runtime, jsx: capture(runtime.jsx), jsxs: capture(runtime.jsxs) };
    }
    return require(id);
  }, output, output.exports);
  const render = () => renderToStaticMarkup(React.createElement(output.exports.default, null, '页面内容'));

  render();
  assert.equal(dialog.open, false);
  logoutButton.onClick();
  render();
  assert.equal(dialog.open, true);
  assert.equal(dialog.confirmLabel, '确认退出');
  assert.deepEqual(calls, []);
  dialog.onCancel();
  render();
  assert.equal(dialog.open, false);
  assert.deepEqual(calls, []);

  logoutButton.onClick();
  render();
  dialog.onConfirm();
  render();
  assert.equal(dialog.open, false);
  assert.deepEqual(calls, ['signOut', '/login', 'refresh']);
});
