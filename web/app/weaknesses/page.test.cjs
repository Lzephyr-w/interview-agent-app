const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

function taskPage(tasks = [], { analysis, panel = 'tasks', route = '/weaknesses' } = {}) {
  const state = [tasks, panel, false, 'ALL', undefined, undefined, false, false];
  let index = 0, nodes = [], effects = [], failSave = false;
  const requests = [];
  const location = new URL(route, 'http://localhost');
  const listeners = new Map();
  const browser = {
    location,
    history: { replaceState(_state, _title, path) { const next = new URL(path, location); location.href = next.href; } },
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name) { listeners.delete(name); },
  };
  const output = { exports: {} };
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/page.tsx`, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  new Function('require', 'module', 'exports', 'document', 'window', compiled)((id) => {
    if (id === 'react') return { ...React, useEffect(callback) { effects.push(callback); }, useRef: () => ({ current: false }), useState(initial) {
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
    if (id === '@/components/WeaknessAnalysisProvider') return { useWeaknessAnalysis: () => ({ analysis, loadAnalysis: async () => {} }) };
    if (id === '@/lib/api') return { api: async (url, options = {}) => {
      if (!options.method) return tasks;
      const body = JSON.parse(options.body);
      requests.push({ url, method: options.method, body });
      if (failSave) throw new Error('保存失败');
      return { ...body, id: url.split('/')[4] || 'created', createdAt: '2026-10-02T00:00:00Z', source: null };
    } };
    if (id.startsWith('@/components/')) return () => null;
    return require(id);
  }, output, output.exports, { getElementById: () => null }, browser);
  return {
    requests,
    location,
    render() { index = 0; nodes = []; effects = []; return renderToStaticMarkup(React.createElement(output.exports.default)); },
    async mount() { this.render(); for (const callback of effects) callback(); await new Promise(setImmediate); return this.render(); },
    navigate(path) { location.href = new URL(path, location).href; listeners.get('popstate')?.(); },
    button(name, index = 0) { return nodes.filter(({ tag, props }) => tag === 'button' && props.children === name)[index].props; },
    dimension(name) { return nodes.find(({ tag, props }) => tag === 'button' && props['aria-label'] === `查看${name}详情`).props; },
    group(name) { return nodes.find(({ tag, props }) => tag === 'button' && props['aria-label'] === `查看${name}分组`).props; },
    tab(name) { return nodes.find(({ props }) => props.role === 'tab' && props.children[0].props.children === name).props; },
    tabs() { return nodes.find(({ props }) => props.role === 'tablist').props; },
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

function sampleAnalysis() {
  return {
    analyzedAt: '2026-10-11T00:00:00Z', stale: false, summary: '建议先练习瓶颈定位，再梳理项目成果。',
    items: ['性能优化', '工程质量', '技术权衡', '成果量化', '协作推进'].map((tag, index) => ({
      tag, title: `${tag}需要具体例子`, diagnosis: `${tag}的诊断内容`, action: `${tag}的练习动作`,
      evidence: [{ questionId: `question-${index}`, interviewId: `interview-${index}`, reviewReportId: `review-${index}`, questionText: `${tag}相关的问题？`, company: '示例公司', role: '前端', interviewRound: '技术面', interviewType: 'REAL', reason: '回答没有说明验证过程' }],
    })),
  };
}

test('overview groups all 16 dimensions and only opens the selected diagnosis', () => {
  const analysis = sampleAnalysis();
  const page = taskPage([], { analysis, panel: 'analysis' });
  let html = page.render();
  assert.equal((html.match(/class="weakness-group-card"/g) || []).length, 4);
  assert.equal((html.match(/class="weakness-report-item"/g) || []).length, 0);
  assert.match(html, /5 项待改进/);
  assert.doesNotMatch(html, /的诊断内容|的练习动作|相关的问题？/);
  page.dimension('性能优化').onClick();
  html = page.render();
  assert.equal((html.match(/class="weakness-report-item"/g) || []).length, 1);
  assert.match(html, /性能优化的诊断内容/);
  assert.doesNotMatch(html, /工程质量的诊断内容/);
  assert.match(html, /<details class="question-evidence" open="">/);
  assert.equal(page.location.search, '?tab=details');
  assert.equal(decodeURIComponent(page.location.hash.slice(1)), '性能优化');
  page.tab('分析总览').onClick(); page.render();
  page.dimension('英语表达').onClick();
  assert.match(page.render(), /该维度暂无分析项/);
  assert.match(page.render(), /这不代表已经掌握/);
});

test('dimension details create training from the chosen evidence with all source IDs', async () => {
  const analysis = sampleAnalysis();
  analysis.items[0].evidence.push({ ...analysis.items[0].evidence[0], questionId: 'another-question', interviewId: 'another-interview', reviewReportId: null });
  const page = taskPage([], { analysis, panel: 'analysis' });
  page.render(); page.dimension('性能优化').onClick(); page.render();
  page.button('以此问题创建训练', 1).onClick(); page.render();
  assert.equal(page.field('关联弱项标签').value, '性能优化');
  assert.equal(page.field('关联弱项标签').readOnly, true);
  await page.form().onSubmit({ preventDefault() {} });
  assert.equal(page.requests[0].body.sourceQuestionId, 'another-question');
  assert.equal(page.requests[0].body.sourceInterviewId, 'another-interview');
  assert.equal(page.requests[0].body.sourceReviewReportId, null);
  assert.equal(page.location.search, '?tab=tasks');
});

test('detail navigation exposes four groups and puts every subcategory above the selected diagnosis', () => {
  const page = taskPage([], { analysis: sampleAnalysis(), panel: 'details' });
  let html = page.render();
  assert.equal((html.match(/aria-label="查看[^"]+分组"/g) || []).length, 4);
  assert.equal(page.group('技术能力')['aria-pressed'], true);
  assert.equal(page.dimension('性能优化')['aria-pressed'], true);
  const tags = [];
  for (const [group, count] of [['技术能力', 6], ['项目与业务', 4], ['表达与协作', 4], ['岗位与简历', 2]]) {
    page.group(group).onClick();
    html = page.render();
    assert.equal(page.group(group)['aria-pressed'], true);
    const categories = html.match(/<nav class="weakness-category-list"[^>]*>([\s\S]*?)<\/nav>/)[1];
    const labels = [...categories.matchAll(/aria-label="查看([^"]+)详情"/g)].map((match) => match[1]);
    assert.equal(labels.length, count);
    tags.push(...labels);
    assert.ok(html.indexOf('id="weakness-dimension-categories"') < html.indexOf('id="weakness-dimension-detail"'));
  }
  assert.equal(new Set(tags).size, 16);
  assert.match(html, /该维度暂无分析项/);
  page.group('项目与业务').onClick();
  html = page.render();
  assert.match(html, /技术权衡的诊断内容/);
  page.dimension('成果量化').onClick(); page.render();
  page.group('项目与业务').onClick();
  assert.match(page.render(), /成果量化的诊断内容/);
  assert.equal(decodeURIComponent(page.location.hash.slice(1)), '成果量化');
});

test('direct dimension links, browser navigation and tab keys keep the selected panel in sync', async () => {
  const page = taskPage([], { analysis: sampleAnalysis(), route: `/weaknesses#${encodeURIComponent('成果量化')}` });
  assert.match(await page.mount(), /成果量化的诊断内容/);
  assert.equal(page.group('项目与业务')['aria-pressed'], true);
  assert.equal(page.dimension('成果量化')['aria-pressed'], true);
  page.navigate('/weaknesses?tab=tasks');
  assert.match(page.render(), /我的训练任务/);
  page.navigate('/weaknesses?tab=details#%E0%A4%A');
  assert.match(page.render(), /性能优化的诊断内容/);
  page.navigate('/weaknesses?tab=analysis'); page.render();
  page.tabs().onKeyDown({ key: 'ArrowRight', preventDefault() {} });
  page.render();
  assert.equal(page.tab('维度详情')['aria-selected'], true);
  page.tabs().onKeyDown({ key: 'End', preventDefault() {} }); page.render();
  assert.equal(page.tab('训练任务')['aria-selected'], true);
});

test('stale snapshots hide diagnoses and an empty completed analysis still shows its summary', () => {
  const stale = taskPage([], { analysis: { ...sampleAnalysis(), stale: true }, panel: 'analysis' });
  assert.match(stale.render(), /请重新分析/);
  assert.doesNotMatch(stale.render(), /需要具体例子/);
  stale.dimension('性能优化').onClick();
  assert.match(stale.render(), /分析需要更新/);
  assert.doesNotMatch(stale.render(), /性能优化的诊断内容/);
  const empty = taskPage([], { analysis: { ...sampleAnalysis(), summary: '当前证据不足以形成明确薄弱点。', items: [] }, panel: 'analysis' });
  assert.match(empty.render(), /当前证据不足以形成明确薄弱点/);
  assert.doesNotMatch(empty.render(), /等待第一次分析/);
});

test('training categories match the shared backend taxonomy', () => {
  const declaration = readFileSync(`${__dirname}/../../../server/src/main/java/com/interviewagent/weakness/WeaknessApi.java`, 'utf8').match(/WEAKNESS_TAGS = List\.of\(([\s\S]*?)\);/)[1];
  const serverTags = [...declaration.matchAll(/"([^"]+)"/g)].map((match) => match[1]);
  const page = taskPage();
  page.render(); page.button('＋ 创建训练任务').onClick();
  const html = page.render();
  const options = [...html.matchAll(/<option value="([^"]+)">/g)].map((match) => match[1]);
  assert.equal(serverTags.length, 16);
  assert.deepEqual(options.filter((value) => serverTags.includes(value)), serverTags);
  assert.equal((html.match(/<optgroup/g) || []).length, 4);
});
