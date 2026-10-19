const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const ts = require('typescript');

function load(file, requireModule = require) {
  const output = { exports: {} };
  const compiled = ts.transpileModule(readFileSync(`${__dirname}/${file}.ts`, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  new Function('require', 'module', 'exports', compiled)(requireModule, output, output.exports);
  return output.exports;
}

test('the complete practice stays local, validates prerequisites, and resets without changing real API behavior', async () => {
  const events = [];
  global.window = { location: { pathname: '/guide' }, dispatchEvent: event => events.push(event.detail) };
  let realCalls = 0, authCalls = 0;
  global.fetch = async () => { realCalls++; return { ok: true, status: 200, json: async () => ({ real: true }) }; };
  const demo = load('guide-demo');
  const { api, apiBlob, streamApi } = load('api', id => id === './guide-demo' ? demo : {
    getSession: () => { authCalls++; return { accessToken: 'test' }; }, signOut() {},
  });
  const send = (path, body, method = 'POST') => api(`/api/v1/${path}`, { method, body: JSON.stringify(body) });
  const read = path => api(`/api/v1/${path}`);
  try {
    demo.resetGuideDemo();
    await assert.rejects(send('mock-interviews', { totalQuestions: 1 }), /面试包/);
    await assert.rejects(send('interviews', {}), /面试包/);
    await assert.rejects(send('interviews/guide-real/questions', {}), /先创建/);
    await assert.rejects(send('job-descriptions', { company: ' ', role: '前端', content: 'React' }), /完成/);
    const jd = await send('job-descriptions', { company: demo.guideExample.company, role: demo.guideExample.role, content: demo.guideExample.jd });
    const resumes = await read('resume-files');
    const pack = await send('interview-packages', { interviewRound: '技术一面', resumeFileId: resumes[0].id, jobDescriptionId: jd.id });
    assert.equal(pack.role, jd.role);
    const recordDraft = { company: jd.company, role: jd.role, interviewRound: pack.interviewRound, interviewPackageId: pack.id,
      interviewTime: new Date(demo.guideExample.interviewTime).toISOString(), status: 'PENDING_REVIEW', result: 'UNKNOWN', notes: demo.guideExample.notes };
    await assert.rejects(send('interviews', { ...recordDraft, interviewTime: 'invalid' }), /有效的面试时间/);
    assert.deepEqual(await read('interviews'), []);
    const record = await send('interviews', recordDraft);
    assert.equal(record.interview.simulationType, 'REAL');
    assert.equal(record.notes, demo.guideExample.notes);
    assert.deepEqual(record.questions, []);
    assert.equal(await read('interview-imports/pending?interviewId=guide-real'), undefined);
    const questionDraft = { questionText: demo.guideExample.question, answerText: demo.guideExample.answer, selfAssessment: 'UNCERTAIN' };
    await assert.rejects(send('interviews/guide-real/questions', { ...questionDraft, questionText: ' ' }), /完成/);
    await assert.rejects(send('interviews/guide-real/questions', { ...questionDraft, answerText: '' }), /完成/);
    await assert.rejects(send('interviews/guide-real/questions', { ...questionDraft, selfAssessment: 'INVALID' }), /有效自评/);
    await send('interviews/guide-real/questions', questionDraft);
    const segments = await send('interviews/guide-real/segment-transcript', { transcript: demo.guideExample.transcript.replace(/\n/g, '\r\n') });
    assert.equal(segments.length, 2);
    assert.equal(segments[0].questionText, '为什么选择列表虚拟化？');
    assert.equal(segments[0].answerText, '长列表只渲染可视区域，减少 DOM 数量。');
    const detail = await read('interviews/guide-real');
    assert.equal(detail.questions.length, 3);
    assert.equal(detail.questions[0].answerText, questionDraft.answerText);
    assert.deepEqual(detail.questions.map(q => q.sortOrder), [0, 1, 2]);
    await assert.rejects(send('interviews/guide-real/segment-transcript', { transcript: '问：有效问题\n答：有效回答\n\n问：' }), /完成/);
    assert.equal((await read('interviews/guide-real')).questions.length, 3);
    const unanswered = await send('interviews/guide-real/questions', { questionText: '没答上的题目', answerText: '', selfAssessment: 'UNANSWERED' });
    assert.equal(unanswered.answerText, '');
    assert.equal(unanswered.selfAssessment, 'UNANSWERED');
    await assert.rejects(send('mock-interviews', { interviewPackageId: pack.id, totalQuestions: 4 }), /数量设为 1/);
    const session = await send('mock-interviews', { interviewPackageId: pack.id, totalQuestions: 1 });
    const answer = await send(`mock-interviews/${session.id}/answer`, { questionId: session.currentQuestion.id, answerText: demo.guideExample.answer, selfAssessment: 'UNCERTAIN' });
    assert.equal(answer.completedQuestions, 1);
    assert.equal(answer.questions[0].answerText, demo.guideExample.answer);
    assert.match(answer.questions[0].aiFeedback, /教学示例/);
    await send(`mock-interviews/${session.id}/finish`, {});
    const analysis = await send('weaknesses/analysis', {});
    assert.equal(analysis.items[0].tag, '成果量化');
    const draft = { title: '补充验证方法', weaknessTag: '成果量化', action: analysis.items[0].action, status: 'NOT_STARTED' };
    const task = await send('training-tasks', draft);
    assert.equal(task.source.questionId, session.currentQuestion.id);
    const completed = await send(`training-tasks/${task.id}`, { ...draft, status: 'COMPLETED' }, 'PUT');
    assert.ok(completed.completedAt);
    const saved = await read('training-tasks'); saved[0].title = 'must not mutate the store';
    assert.equal((await read('training-tasks'))[0].title, draft.title);
    assert.deepEqual(events, ['jd', 'package', 'real-interview', 'question', 'segment', 'question', 'start', 'answer', 'finish', 'analysis', 'task', 'completed']);
    await assert.rejects(send('interviews/guide-real', {}, 'DELETE'), /不会修改正式数据/);
    await assert.rejects(read('dashboard'), /指引/);
    await assert.rejects(apiBlob('/file'), /不提供真实文件/);
    await assert.rejects(streamApi('/stream', {}, () => {}), /示例回复/);
    assert.equal(realCalls, 0);
    assert.equal(authCalls, 0);
    demo.resetGuideDemo();
    assert.deepEqual(await read('job-descriptions'), []);
    assert.deepEqual(await read('interview-packages'), []);
    assert.deepEqual(await read('training-tasks'), []);
    assert.deepEqual(await read('interviews'), []);
    await assert.rejects(read('interviews/guide-real'), /先创建/);
    assert.equal((await read('weaknesses/analysis')).analyzedAt, null);
    window.location.pathname = '/library';
    assert.deepEqual(await read('job-descriptions'), { real: true });
    assert.equal(realCalls, 1);
    assert.equal(authCalls, 1);
  } finally { delete global.window; delete global.fetch; }
});
