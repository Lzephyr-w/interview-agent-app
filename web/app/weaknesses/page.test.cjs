const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

function taskPage(tasks = []) {
  const state = [tasks, 'tasks', false, 'ALL', undefined, undefined, false, false];
  let index = 0, nodes = [], failSave = false;
  const requests = [];
  const output = { exports: {} };
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/page.tsx`, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  new Function('require', 'module', 'exports', 'document', compiled)((id) => {
    if (id === 'react') return { ...React, useEffect() {}, useRef: () => ({ current: false }), useState(initial) {
      const key = index++;
      if (!(key in state) || (key === 4 && state[key] === undefined)) state[key] = initial;
      return [state[key], (value) => { state[key] = typeof value === 'function' ? value(state[key]) : value; }];
    } };
    if (id === 'react/jsx-runtime') {
      const runtime = require(id);
      const capture = (jsx) => (tag, props, key) => { nodes.push({ tag, props }); return jsx(tag, props, key); };
      return { ...runtime, jsx: capture(runtime.jsx), jsxs: capture(runtime.jsxs) };
    }
    if (id === 'next/link') return ({ children, ...props }) => React.createElement('a', props, children);
    if (id === '@/components/AppShell') return ({ children }) => children;
    if (id === '@/components/WeaknessAnalysisProvider') return { useWeaknessAnalysis: () => ({}) };
    if (id === '@/lib/api') return { api: async (url, options) => {
      const body = JSON.parse(options.body);
      requests.push({ url, method: options.method, body });
      if (failSave) throw new Error('保存失败');
      return { ...body, id: url.split('/')[4] || 'created', createdAt: '2026-10-02T00:00:00Z', source: null };
    } };
    if (id.startsWith('@/components/')) return () => null;
    return require(id);
  }, output, output.exports, { getElementById: () => null });
  return {
    requests,
    render() { index = 0; nodes = []; return renderToStaticMarkup(React.createElement(output.exports.default)); },
    button(name) { return nodes.find(({ tag, props }) => tag === 'button' && props.children === name).props; },
    field(name) { return nodes.find(({ tag, props }) => tag === 'label' && props.children[0] === name).props.children[1].props; },
    select(name) { return nodes.find(({ tag, props }) => tag === 'select' && props['aria-label'] === name).props; },
    form() { return nodes.find(({ tag }) => tag === 'form').props; },
    fail(value) { failSave = value; },
  };
}

test('manual task creation opens a blank form, preserves failed drafts and saves without a source', async () => {
  const page = taskPage();
  assert.match(page.render(), /＋ 创建训练任务/);
  page.button('＋ 创建训练任务').onClick();
  assert.match(page.render(), /请选择弱项标签/);
  for (const [name, value] of [['标题', '练习闭包'], ['关联弱项标签', '技术基础'], ['建议动作 / 练习内容', '手写闭包示例']]) {
    page.field(name).onChange({ target: { value } });
    page.render();
  }
  page.fail(true);
  await page.form().onSubmit({ preventDefault() {} });
  assert.match(page.render(), /value="练习闭包"/);
  page.fail(false);
  await page.form().onSubmit({ preventDefault() {} });
  const html = page.render();
  assert.match(html, /<h3>练习闭包<\/h3>/);
  assert.doesNotMatch(html, /<form/);
  assert.deepEqual(page.requests.at(-1), {
    url: '/api/v1/training-tasks', method: 'POST', body: {
      title: '练习闭包', weaknessTag: '技术基础', action: '手写闭包示例', status: 'NOT_STARTED',
      sourceQuestionId: null, sourceInterviewId: null, sourceReviewReportId: null,
    },
  });
  page.button('＋ 创建训练任务').onClick(); page.render();
  page.button('取消').onClick();
  assert.doesNotMatch(page.render(), /<form/);
});

test('status filters show matching tasks and update when a task changes status', async () => {
  const tasks = ['NOT_STARTED', 'IN_PROGRESS', 'COMPLETED'].map((status, i) => ({
    id: String(i), title: `任务${i}`, weaknessTag: '技术基础', action: '练习内容', status, createdAt: '2026-10-02T00:00:00Z', source: null,
  }));
  const page = taskPage(tasks);
  assert.equal((page.render().match(/class="training-task-card"/g) || []).length, 3);
  for (const task of tasks) {
    page.select('筛选任务状态').onChange({ target: { value: task.status } });
    const html = page.render();
    assert.equal((html.match(/class="training-task-card"/g) || []).length, 1);
    assert.match(html, new RegExp(`<h3>${task.title}</h3>`));
  }
  await page.select('更新任务2状态').onChange({ target: { value: 'NOT_STARTED' } });
  await new Promise(setImmediate);
  assert.match(page.render(), /当前状态下暂无任务/);
  page.select('筛选任务状态').onChange({ target: { value: 'ALL' } });
  assert.equal((page.render().match(/class="training-task-card"/g) || []).length, 3);
});

test('editing a linked task keeps its question and review source', async () => {
  const page = taskPage([{
    id: 'linked', title: '练习原型链', weaknessTag: '技术基础', action: '解释继承机制', status: 'NOT_STARTED', createdAt: '2026-10-02T00:00:00Z',
    source: { questionId: 'question', interviewId: 'interview', reviewReportId: 'review', questionText: '如何实现继承？', label: '技术面' },
  }]);
  page.render(); page.button('编辑任务').onClick(); page.render();
  assert.equal(page.field('关联弱项标签').readOnly, true);
  await page.form().onSubmit({ preventDefault() {} });
  assert.equal(page.requests[0].method, 'PUT');
  assert.equal(page.requests[0].body.sourceQuestionId, 'question');
  assert.equal(page.requests[0].body.sourceInterviewId, 'interview');
  assert.equal(page.requests[0].body.sourceReviewReportId, 'review');
});
