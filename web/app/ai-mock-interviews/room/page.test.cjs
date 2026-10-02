const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

function renderRoom(currentQuestion, taskType, status = 'PROCESSING', { entered = true, starting = false } = {}) {
  const session = { status: 'RUNNING', totalQuestions: 10, currentQuestion, task: taskType ? { taskType, status } : null };
  const source = readFileSync(`${__dirname}/page.tsx`, 'utf8')
    .replace('useState<Package>()', 'useState({ company: "测试公司", role: "开发工程师", interviewRound: "1" })')
    .replace('useState<Session>()', `useState(${JSON.stringify(session)})`)
    .replace('const [entered, setEntered] = useState(false)', `const [entered, setEntered] = useState(${entered})`)
    .replace('const [starting, setStarting] = useState(false)', `const [starting, setStarting] = useState(${starting})`);
  const compiled = ts.transpileModule(source, {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const output = { exports: {} };
  new Function('require', 'module', 'exports', compiled)((id) => {
    if (id === 'next/link') return ({ children, ...props }) => React.createElement('a', props, children);
    if (id.startsWith('@/components/')) return () => null;
    if (id === '@/lib/api') return {};
    if (id === '@/lib/audio') return {};
    return require(id);
  }, output, output.exports);
  return renderToStaticMarkup(React.createElement(output.exports.default));
}

test('final answer processing says the interview is ending; intermediate questions still load', () => {
  for (const task of ['AI_AUDIO', 'AI_FINALIZE_AUDIO']) {
    for (const question of [null, { sortOrder: 9, state: 'TRANSCRIBING' }]) {
      const html = renderRoom(question, task);
      assert.match(html, /面试结束中…/);
      assert.doesNotMatch(html, /题目加载中/);
    }
    assert.match(renderRoom({ sortOrder: 8, state: 'TRANSCRIBING' }, task), /题目加载中…/);
  }
  assert.match(renderRoom(null, 'AI_NEXT'), /题目加载中…/);
  assert.match(renderRoom(null, 'AI_AUDIO', 'FAILED'), /AI 处理失败/);
  const processing = renderRoom(null, 'AI_AUDIO');
  assert.doesNotMatch(processing, /结束或取消面试/);
  assert.match(processing, /aria-label="退出模拟"/);
  assert.doesNotMatch(processing, /<a[^>]*class="ai-room-brand"/);
});

test('a prepared question is displayed only after startup completes', () => {
  const question = { id: 'first', sortOrder: 0, state: 'OPEN', questionType: 'FUNDAMENTAL', questionText: '首题内容' };
  for (const entered of [false, true]) {
    const html = renderRoom(question, null, undefined, { entered, starting: true });
    assert.match(html, /正在启动面试…/);
    assert.doesNotMatch(html, /首题内容|问题 1\/10|开始回答|重听问题/);
  }
  const welcome = renderRoom(question, null, undefined, { entered: false });
  assert.match(welcome, /准备好开始了吗/);
  assert.doesNotMatch(welcome, /首题内容/);
  const begun = renderRoom(question, null);
  assert.match(begun, /首题内容/);
  assert.match(begun, /开始回答/);
});
