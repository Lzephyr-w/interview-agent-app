const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');

function renderQuestions({ method = 'audio', task, questions = [], type = 'REAL', busy = '', buttons, inputs, dialogs, onApi } = {}) {
  const detail = { interview: { id: 'test', simulationType: type, company: '测试', role: '前端', interviewTime: '2026-10-01T00:00:00Z' }, questions: [], reviews: [] };
  const source = readFileSync(`${__dirname}/page.tsx`, 'utf8')
    .replace('useState<Detail>()', `useState(${JSON.stringify(detail)})`)
    .replace('useState<ImportTask>()', `useState(${JSON.stringify(task)})`)
    .replace(/useState<ImportedQuestion\[\]>\(\s*\[\],?\s*\)/, `useState(${JSON.stringify(questions)})`)
    .replace('useState("audio")', `useState(${JSON.stringify(method)})`)
    .replace(new RegExp(`(const \\[${busy},[^\\n]+useState\\()false`), '$1true');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2017, jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const output = { exports: {} };
  new Function('require', 'module', 'exports', compiled)((id) => {
    if (id === 'next/navigation') return { usePathname: () => '/interviews/test/questions', useRouter: () => ({}) };
    if (id === 'next/link') return ({ children, ...props }) => React.createElement('a', props, children);
    if (id === '@/components/AppShell') return ({ children }) => children;
    if (id === '@/components/ConfirmDialog') return (props) => { dialogs?.push(props); return null; };
    if (id === '@/lib/api') return { api: onApi };
    if (id === 'react/jsx-runtime' && (buttons || inputs)) {
      const runtime = require(id);
      const capture = (jsx) => (tag, props, key) => {
        if (tag === 'button') buttons?.push(props);
        if (tag === 'input') inputs?.push(props);
        return jsx(tag, props, key);
      };
      return { ...runtime, jsx: capture(runtime.jsx), jsxs: capture(runtime.jsxs) };
    }
    if (id.startsWith('@/components/')) return () => null;
    if (id.startsWith('@/lib/')) return {};
    return require(id);
  }, output, output.exports);
  return renderToStaticMarkup(React.createElement(output.exports.default));
}

test('question composer isolates methods and handles failed, empty and ready imports', () => {
  for (const method of ['audio', 'text', 'manual']) {
    const html = renderQuestions({ method });
    for (const panel of ['audio', 'text', 'manual']) {
      const tag = html.match(new RegExp(`<div id="qa-${panel}-panel"[^>]*>`))[0];
      assert.equal(tag.includes('hidden'), panel !== method);
    }
  }
  const failed = { status: 'ANALYSIS_FAILED', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '问：项目难点？', error: '模型 JSON 非法' };
  const empty = renderQuestions({ task: failed });
  assert.match(empty, /问答识别未完成，转写已保留/);
  assert.match(empty, /重新识别/);
  assert.doesNotMatch(empty, /确认加入 \d+ 条问答/);
  assert.equal((empty.match(/aria-current="step"/g) || []).length, 1);
  for (const status of ['TRANSCRIPTION_FAILED', 'ANALYSIS_FAILED', 'READY']) {
    const html = renderQuestions({ task: { ...failed, status } });
    assert.match(html, /重新导入/);
    assert.equal((html.match(/type="file"/g) || []).length, 1);
    assert.doesNotMatch(html.match(/<input type="file"[^>]*>/)[0], /disabled/);
    if (status === 'TRANSCRIPTION_FAILED') { assert.match(html, /录音转写未完成，请重新上传/); assert.doesNotMatch(html, /问答识别未完成，转写已保留/); }
  }
  const questions = [{ question: '项目难点？', answer: '', orderIndex: 1, speakerEvidence: '' }];
  const ready = renderQuestions({ task: { ...failed, status: 'READY', error: '' }, questions });
  assert.match(ready, />重新识别问答<\/button>/);
  assert.match(ready, /<button class="primary-button" type="button">确认加入 1 条问答/);
  for (const busy of ['importingAudio', 'analyzingImport', 'saving']) {
    const html = renderQuestions({ task: failed, questions, busy });
    assert.match(html.match(/<input type="file"[^>]*>/)[0], /disabled/);
    assert.match(html, /<fieldset class="import-questions" disabled=""/);
  }
  assert.match(renderQuestions({ task: failed, questions: [{ ...questions[0], question: '' }] }), /<button class="primary-button" type="button" disabled="">确认加入/);
  assert.doesNotMatch(renderQuestions({ type: 'AI_VOICE' }), /class="qa-composer"/);
});


test('shows absolute time, local speakers, uncertain and corrected roles', () => {
  const task = { status: 'READY', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '原文', error: '', turns: [
    { id: 0, segmentIndex: 0, speakerId: 0, startMs: 1000, endMs: 5000, text: '技术问题？', role: 'INTERVIEWER', roleCorrected: false },
    { id: 1, segmentIndex: 1, speakerId: 0, startMs: 119000, endMs: 120000, text: '真实回答', role: 'CANDIDATE', roleCorrected: true },
  ] };
  const html = renderQuestions({ task });
  assert.match(html, /0:01 – 0:05<\/time><span>片段 1 \/ 说话人 0/);
  assert.match(html, /1:59 – 2:00<\/time><span>片段 2 \/ 说话人 0/);
  assert.match(html, /已修正角色/);
  assert.match(html, /value="CANDIDATE" selected/);
  assert.match(html, /待确认/);
  const source = readFileSync(`${__dirname}/page.tsx`, 'utf8');
  const correction = source.slice(source.indexOf('async function correctImportRole'), source.indexOf('function importTime'));
  assert.match(correction, /\/roles/); assert.match(correction, /\/analyze/); assert.doesNotMatch(correction, /\/audio/);
});

test('partial transcripts keep transcription active until it actually completes', () => {
  for (const status of ['TRANSCRIBING', 'TRANSCRIPTION_FAILED', 'ANALYZING', 'ANALYSIS_FAILED', 'READY', 'SAVED']) {
    const html = renderQuestions({ task: { status, originalFilename: '录音.wav', sizeBytes: 1024, transcript: '已完成的部分转写', error: '' } });
    const steps = html.match(/<ol class="import-steps"[^>]*>(.*?)<\/ol>/)[1].match(/<li\b[^>]*>.*?<\/li>/g);
    const transcriptionComplete = !['TRANSCRIBING', 'TRANSCRIPTION_FAILED'].includes(status);
    const analysisComplete = ['READY', 'SAVED'].includes(status);
    assert.equal(steps[1].includes('class="done"'), transcriptionComplete);
    assert.equal(steps[2].includes('class="done"'), analysisComplete);
    assert.equal(steps[2].includes('aria-current="step"'), transcriptionComplete && !analysisComplete);
    assert.equal(steps[3].includes('class="done"'), status === 'SAVED');
    assert.equal((html.match(/aria-current="step"/g) || []).length, status === 'SAVED' ? 0 : 1);
  }
});

test('ready reanalysis forces AI extraction and protects unsaved edits; other states have no fresh button', async () => {
  const questions = [{ question: '项目职责？', answer: '真实回答', orderIndex: 1, speakerEvidence: '来源' }];
  const task = { id: 'fresh', status: 'READY', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '原话', error: '', questions };
  const requests = [], buttons = [];
  const onApi = async (url, options) => { requests.push({ url, options }); return task; };
  renderQuestions({ task, questions, buttons, onApi });
  buttons.find((button) => button.children === '重新识别问答').onClick();
  await new Promise(setImmediate);
  assert.deepEqual(requests, [{ url: '/api/v1/interview-imports/fresh/analyze?force=true', options: { method: 'POST' } }]);

  requests.length = 0;
  const editedButtons = [], dialogs = [];
  renderQuestions({ task, questions: [{ ...questions[0], answer: '手工编辑\n\n保留段落' }], buttons: editedButtons, dialogs, onApi });
  editedButtons.find((button) => button.children === '重新识别问答').onClick();
  await new Promise(setImmediate);
  assert.equal(requests.length, 0);
  const confirmation = dialogs.find((dialog) => dialog.title === '重新识别问答？');
  assert.match(confirmation.description, /替换当前未保存的手工编辑/);
  confirmation.onConfirm();
  await new Promise(setImmediate);
  assert.equal(requests[0].url, '/api/v1/interview-imports/fresh/analyze?force=true');
  for (const status of ['ANALYSIS_FAILED', 'TRANSCRIPTION_FAILED', 'SAVED']) {
    assert.doesNotMatch(renderQuestions({ task: { ...task, status }, questions }), />重新识别问答<\/button>/);
  }
  for (const busy of ['importingAudio', 'analyzingImport', 'saving']) {
    const busyButtons = [];
    renderQuestions({ task, questions, busy, buttons: busyButtons });
    const button = busyButtons.find((item) => item.title?.includes('使用已保存的转写'));
    assert.equal(button.disabled, true);
    button.onClick(); // The handler also guards against a second request.
  }
});

test('long dialogue stays in a keyboard-accessible scroll region with collapsed long turns', () => {
  const turns = Array.from({ length: 80 }, (_, id) => ({ id, segmentIndex: Math.floor(id / 10), speakerId: id % 2, startMs: id * 1000, endMs: (id + 1) * 1000, text: id === 0 ? '长回答。'.repeat(60) : `发言 ${id}`, role: 'UNKNOWN', roleCorrected: false }));
  const task = { status: 'READY', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '原文', error: '', turns };
  const html = renderQuestions({ task });
  assert.match(html, /80 条发言/);
  assert.match(html, /class="import-dialogue-list" role="region" aria-label="面试发言列表" tabindex="0"/);
  assert.equal((html.match(/class="import-turn"/g) || []).length, 80);
  assert.match(html, /<details class="import-turn-text"><summary>/);
  assert.match(html, /展开全文/);
  assert.match(html, /收起全文/);
  assert.match(html, new RegExp(turns[0].text));
});

test('pending source warnings block saving until explicit review and preserve source IDs in the draft', async () => {
  const questions = [{ question: '问题？', answer: '原话。', orderIndex: 1, speakerEvidence: '来源', questionTurnIds: [0], answerTurnIds: [1], sourceId: '0', reviewConfirmed: false, warnings: [{ code: 'ANSWER_ROLE_UNCERTAIN', message: '回答角色未定', turnIds: [1] }] }];
  const task = { id: 'pending', status: 'READY', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '原文', error: '', questions, turns: [{ id: 0, text: '问题？', role: 'INTERVIEWER' }, { id: 1, text: '原话。', role: 'UNKNOWN' }] };
  const buttons = [], inputs = [], requests = [];
  const html = renderQuestions({ task, questions, buttons, inputs, onApi: async (url, options) => { requests.push({ url, options }); return task; } });
  assert.match(html, /先逐条核对或排除待确认项/);
  assert.equal(buttons.find((b) => String(b.children).startsWith('确认加入')).disabled, true);
  assert.match(html, /\[1\] 原话。/);
  await inputs.find((p) => p.type === 'checkbox').onChange({ target: { checked: true } });
  await new Promise(setImmediate);
  assert.equal(requests[0].url, '/api/v1/interview-imports/pending/draft');
  const body = JSON.parse(requests[0].options.body);
  assert.equal(body.questions[0].reviewConfirmed, true);
  assert.deepEqual(body.questions[0].answerTurnIds, [1]);
  const approved = [], reviewed = [{ ...questions[0], reviewConfirmed: true }];
  renderQuestions({ task, questions: reviewed, buttons: approved });
  assert.equal(approved.find((b) => String(b.children).startsWith('确认加入')).disabled, false);
});

test('text preview shows resume fallback and unaccepted corrections; acceptance and exclusion are explicit', async () => {
  const questions = [{ question: '工具？', answer: '靠带。', orderIndex: 1, speakerEvidence: '来源', sourceId: '0', questionTurnIds: [0], answerTurnIds: [1] }];
  const task = { id: 'text', source: 'TEXT', status: 'READY', originalFilename: '粘贴转写.txt', sizeBytes: 80, transcript: '工具？靠带。', error: '', questions, resume: { filename: 'r.pdf', status: 'PENDING', truncated: false }, corrections: [{ id: 'fix', turnId: 1, original: '靠带', replacement: 'Codex', evidence: 'Codex', evidenceSource: 'RESUME', reason: '术语核对', error: '', accepted: false }] };
  const buttons = [], requests = [];
  const html = renderQuestions({ method: 'text', task, questions, buttons, onApi: async (url, options) => { requests.push({ url, options }); return task; } });
  assert.match(html, /粘贴文本 · AI 预览/);
  assert.match(html, /本场简历尚未解析完成/);
  assert.match(html, /靠带 → Codex/);
  assert.match(html, /分段并加入问答/);
  assert.doesNotMatch(html, /type="file"|录音导入进度/);
  buttons.find((p) => p.children === '核对后采纳').onClick();
  await new Promise(setImmediate);
  assert.deepEqual(JSON.parse(requests[0].options.body).acceptedCorrectionIds, ['fix']);
  buttons.find((p) => p.children === '排除这条').onClick();
  await new Promise(setImmediate);
  assert.deepEqual(JSON.parse(requests[1].options.body).excludedQuestionIds, ['0']);
  assert.deepEqual(JSON.parse(requests[1].options.body).questions, []);
  const invalid = renderQuestions({ method: 'text', task: { ...task, corrections: [{ ...task.corrections[0], error: '证据不符' }] }, questions });
  assert.match(invalid, /无法采纳：证据不符/);
  assert.match(invalid, /disabled="">核对后采纳/);
});

test('editing sources resets review and preserves manual text in the request', async () => {
  const questions = [{ question: '手工问题', answer: '手工回答\n\n段落', orderIndex: 1, speakerEvidence: '来源', sourceId: '0', questionTurnIds: [0], answerTurnIds: [1], reviewConfirmed: true }];
  const task = { id: 'sources', status: 'READY', originalFilename: '录音.wav', sizeBytes: 1024, transcript: '原文', error: '', questions };
  const inputs = [], requests = [];
  renderQuestions({ task, questions, inputs, onApi: async (url, options) => { requests.push({ url, options }); return task; } });
  inputs.find((p) => p.defaultValue === '1').onBlur({ target: { value: '1,2' } });
  await new Promise(setImmediate);
  const question = JSON.parse(requests[0].options.body).questions[0];
  assert.equal(question.reviewConfirmed, false);
  assert.deepEqual(question.answerTurnIds, [1, 2]);
  assert.equal(question.answer, '手工回答\n\n段落');
});

test('topic drafts show corrected prose, linked evidence and raw sources including introductions', async () => {
  const questions = [
    { kind: 'INTRODUCTION', question: '自我介绍', answer: '负责请求封装项目。', orderIndex: 1, speakerEvidence: '', sourceId: 'INTRODUCTION:0', questionTurnIds: [], answerTurnIds: [0] },
    { kind: 'QA', question: '请求封装与登录状态？', answer: '创建Axios实例。\n\n登录过期后清除localStorage。', orderIndex: 2, speakerEvidence: '', sourceId: 'QA:1', questionTurnIds: [1], answerTurnIds: [2], notes: ['问题按上下文整理'], edits: [{ original: 'Excel', replacement: 'Axios', evidence: 'Axios封装', evidenceSource: 'EVIDENCE_CARD', reason: '技术栈核对', uncertain: false }] },
  ];
  const task = { id: 'organized', source: 'TEXT', status: 'READY', organization: 'topic-editor-v2', originalFilename: '转写.txt', sizeBytes: 100, transcript: '原文', error: '', evidenceCards: ['请求封装项目'], questions, turns: [
    { id: 0, segmentIndex: -1, text: '嗯我负责请求封装项目。', role: 'CANDIDATE' }, { id: 1, segmentIndex: -1, text: '怎么封装？', role: 'INTERVIEWER' }, { id: 2, segmentIndex: -1, text: '创建Excel实例。', role: 'CANDIDATE' },
  ] };
  const buttons = [], requests = [];
  const html = renderQuestions({ method: 'text', task, questions, buttons, onApi: async (url, options) => { requests.push({ url, options }); return task; } });
  assert.match(html, /话题整理稿/); assert.match(html, /个话题 · 自我介绍/);
  assert.match(html, /\[0\] 嗯我负责请求封装项目。/); assert.match(html, /\[2\] 创建Excel实例。/);
  assert.match(html, /关联证据卡：请求封装项目/); assert.match(html, /Excel → Axios/); assert.match(html, /问题按上下文整理/);
  assert.match(html, /rows="6"/); assert.match(html, /将本话题恢复为原文摘录/);
  buttons.find((p) => p.children === '重新识别问答').onClick();
  await new Promise(setImmediate);
  assert.equal(requests[0].url, '/api/v1/interview-imports/organized/analyze?force=true'); // Readable generated prose is not an unsaved manual edit.
});
