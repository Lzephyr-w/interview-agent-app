// ponytail: one disposable practice session; real pages keep their existing forms and validation.
type Job = { id: string; company: string; role: string; content: string };
type Package = { id: string; company: string; role: string; interviewRound: string; resumeFileId: string; jobDescriptionId: string; evidenceCardIds: string[] };
type Task = { id: string; title: string; weaknessTag: string; action: string; status: string; createdAt: string; completedAt: string | null; source: object };
type RecordedQuestion = { id: string; questionText: string; answerText: string; selfAssessment: string; sortOrder: number; aiFeedback: string };
type RealInterview = {
  interview: { id: string; company: string; role: string; interviewRound: string; interviewTime: string; status: string; result: string; interviewPackageId: string; interviewType: "REAL"; simulationType: "REAL" };
  notes: string; transcript: string; questions: RecordedQuestion[]; reviews: [];
};

export const GUIDE_EVENT = "operation-guide:action";
export type GuideAction = "jd" | "package" | "real-interview" | "question" | "segment" | "start" | "answer" | "finish" | "analysis" | "task" | "completed";
export const guideExample = {
  company: "星河科技",
  role: "前端开发工程师",
  jd: "负责招聘平台前端开发，熟悉 React、TypeScript，能够定位性能瓶颈并用数据说明优化效果。",
  round: "技术一面",
  interviewTime: new Date(Date.now() - new Date().getTimezoneOffset() * 60_000).toISOString().slice(0, 16),
  notes: "本次为操作指引中的示例真实面试。先按原意记录，没记清的内容稍后补充。",
  question: "请介绍一次你参与的性能优化，并说明如何验证效果？",
  transcript: "问：为什么选择列表虚拟化？\n答：长列表只渲染可视区域，减少 DOM 数量。\n\n问：如何评估这次优化？\n答：在相同设备和网络条件下，对比首屏耗时与长任务数量。",
  answer: "我负责招聘平台的列表性能优化。先用 Performance 面板定位重复渲染，再通过稳定组件属性和列表虚拟化减少渲染开销。上线前后对比首屏耗时与长任务数量，并保留回滚方案。",
};

const question = () => ({
  id: "guide-question", questionText: "请介绍一次你亲自参与的性能优化：如何定位问题、选择方案并验证效果？",
  answerText: "", aiFeedback: "", selfAssessment: "UNCERTAIN", questionKind: "MAIN", parentQuestionId: null,
  state: "OPEN", sortOrder: 1, sourceTitle: null, sourceLocation: null,
});
const interview = () => ({
  id: "guide-mock", company: "", role: "", interviewRound: "", status: "RUNNING", mode: "TEXT", sourceMode: "STANDARD",
  aiAvailable: true, aiMessage: "操作体验 · 本题与反馈为教学示例", totalQuestions: 1, completedQuestions: 0,
  currentQuestionIndex: 1, formalInterviewId: null as string | null, currentQuestion: question() as ReturnType<typeof question> | null,
  questions: [question()], task: null,
});
let job: Job | undefined;
let pack: Package | undefined;
let task: Task | undefined;
let mock: ReturnType<typeof interview> | undefined;
let analyzed = false;
let realInterview: RealInterview | undefined;

export function resetGuideDemo() {
  job = undefined; pack = undefined; task = undefined; mock = undefined; analyzed = false; realInterview = undefined;
}

function announce(action: GuideAction) {
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(GUIDE_EVENT, { detail: action }));
}

function required(body: Record<string, unknown>, key: string) {
  const value = body[key];
  if (typeof value !== "string" || !value.trim()) throw new Error("请先按指引完成当前内容。");
  return value.trim();
}

function recordedQuestion(body: Record<string, unknown>, sortOrder: number): RecordedQuestion {
  const selfAssessment = required(body, "selfAssessment");
  if (!["GOOD", "UNCERTAIN", "UNANSWERED"].includes(selfAssessment)) throw new Error("请选择有效自评。");
  return {
    id: `guide-real-question-${sortOrder + 1}`, questionText: required(body, "questionText"),
    answerText: selfAssessment === "UNANSWERED" ? String(body.answerText ?? "").trim() : required(body, "answerText"),
    selfAssessment, sortOrder, aiFeedback: "",
  };
}

function analysis() {
  return {
    summary: analyzed ? "示例分析：回答包含定位与方案，但缺少具体的前后对比数据，可以优先练习成果量化。" : null,
    analyzedAt: analyzed ? new Date().toISOString() : null, stale: false,
    items: analyzed ? [{
      tag: "成果量化", title: "让优化效果有数据支撑",
      diagnosis: "提到了对比首屏耗时与长任务数量，但没有说明基线、优化后的数值及测量条件。",
      action: "用 2 分钟重新讲述这次优化，补充测量条件、优化前后数据和个人贡献；没有实际数据时说明验证方法，不编造数字。",
      evidence: [{ questionId: "guide-question", interviewId: "guide-record", reviewReportId: null,
        questionText: question().questionText, company: job?.company ?? "", role: job?.role ?? "", interviewRound: pack?.interviewRound ?? "",
        interviewType: "MOCK", reason: "本次示例回答描述了优化过程，尚未给出明确的量化结果。" }],
    }] : [],
  };
}

export async function guideDemoApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? "GET").toUpperCase();
  const body = typeof options.body === "string" ? JSON.parse(options.body) as Record<string, unknown> : {};
  let result: unknown;
  let action: GuideAction | undefined;
  if (method === "GET") {
    switch (path) {
      case "/api/v1/resume-files": result = [{ id: "guide-resume", originalFilename: "示例简历-前端开发.pdf", contentType: "application/pdf", sizeBytes: 24576, parseStatus: "READY", parsedTruncated: false, createdAt: new Date().toISOString() }]; break;
      case "/api/v1/job-descriptions": result = job ? [job] : []; break;
      case "/api/v1/interview-packages": result = pack ? [pack] : []; break;
      case "/api/v1/evidence-cards":
      case "/api/v1/knowledge/categories":
      case "/api/v1/knowledge/documents": result = []; break;
      case "/api/v1/mock-interviews": result = { active: null }; break;
      case "/api/v1/interviews": result = realInterview ? [realInterview.interview] : []; break;
      case "/api/v1/interviews/guide-real":
        if (!realInterview) throw new Error("请先创建示例真实面试。");
        result = realInterview; break;
      case "/api/v1/interview-imports/pending?interviewId=guide-real": result = undefined; break;
      case "/api/v1/weaknesses/analysis": result = analysis(); break;
      case "/api/v1/training-tasks": result = task ? [task] : []; break;
      default: throw new Error("请按指引体验当前步骤。");
    }
  } else if (method === "POST" && path === "/api/v1/job-descriptions") {
    job = { id: "guide-jd", company: required(body, "company"), role: required(body, "role"), content: required(body, "content") };
    result = job; action = "jd";
  } else if (method === "POST" && path === "/api/v1/interview-packages") {
    if (!job || body.resumeFileId !== "guide-resume" || body.jobDescriptionId !== job.id) throw new Error("请选择示例简历和刚保存的 JD。");
    pack = { id: "guide-package", company: job.company, role: job.role, interviewRound: required(body, "interviewRound"), resumeFileId: "guide-resume", jobDescriptionId: job.id, evidenceCardIds: [] };
    result = pack; action = "package";
  } else if (method === "POST" && path === "/api/v1/interviews") {
    if (!pack || body.interviewPackageId !== pack.id) throw new Error("请选择刚创建的面试包。");
    const interviewTime = required(body, "interviewTime");
    if (!Number.isFinite(Date.parse(interviewTime))) throw new Error("请填写有效的面试时间。");
    const status = required(body, "status"), outcome = required(body, "result");
    if (!["PENDING_REVIEW", "REVIEWED"].includes(status) || !["UNKNOWN", "PASSED", "REJECTED", "PENDING"].includes(outcome)) throw new Error("请选择有效的面试状态和结果。");
    realInterview = { interview: { id: "guide-real", company: required(body, "company"), role: required(body, "role"), interviewRound: required(body, "interviewRound"),
      interviewTime, status, result: outcome, interviewPackageId: pack.id, interviewType: "REAL", simulationType: "REAL" },
      notes: typeof body.notes === "string" ? body.notes : "", transcript: "", questions: [], reviews: [],
    };
    result = realInterview; action = "real-interview";
  } else if (method === "POST" && path === "/api/v1/interviews/guide-real/questions") {
    if (!realInterview) throw new Error("请先创建示例真实面试。");
    const created = recordedQuestion(body, realInterview.questions.length);
    realInterview.questions.push(created);
    result = created; action = "question";
  } else if (method === "POST" && path === "/api/v1/interviews/guide-real/segment-transcript") {
    if (!realInterview) throw new Error("请先创建示例真实面试。");
    const transcript = required(body, "transcript");
    const strip = (value: string) => value.replace(/^(问题|问|Q|回答|答|A)[：: ]*/, "").trim();
    // Match the real endpoint: blank lines separate questions; the first line is the question.
    const created = transcript.split(/(?:\r?\n){2,}/).filter((section) => section.trim()).map((section, index) => {
      const [questionText, ...answerLines] = section.trim().split(/\r?\n/);
      return recordedQuestion({ questionText: strip(questionText), answerText: strip(answerLines.join("\n")), selfAssessment: answerLines.length ? "UNCERTAIN" : "UNANSWERED" }, realInterview!.questions.length + index);
    });
    if (!created.length) throw new Error("请用空行分隔每道题及其回答。");
    realInterview.questions.push(...created); realInterview.transcript = transcript;
    result = created; action = "segment";
  } else if (method === "POST" && path === "/api/v1/mock-interviews") {
    if (!pack || body.interviewPackageId !== pack.id || body.totalQuestions !== 1) throw new Error("请选择刚创建的面试包，并将主问题数量设为 1。");
    mock = { ...interview(), company: pack.company, role: pack.role, interviewRound: pack.interviewRound };
    result = mock; action = "start";
  } else if (method === "POST" && path === "/api/v1/mock-interviews/guide-mock/answer") {
    if (!mock || !mock.currentQuestion || body.questionId !== "guide-question") throw new Error("请先开始本次体验。" );
    mock = { ...mock, currentQuestion: null, completedQuestions: 1, questions: [{ ...mock.questions[0],
      answerText: required(body, "answerText"), selfAssessment: required(body, "selfAssessment"), state: "ANSWERED",
      aiFeedback: "【教学示例】回答覆盖了问题定位、方案选择与验证方法。下一步请补充测量条件和优化前后的实际数据，让个人贡献与效果更清楚。",
    }] };
    result = mock; action = "answer";
  } else if (method === "POST" && path === "/api/v1/mock-interviews/guide-mock/finish") {
    if (!mock?.completedQuestions) throw new Error("请先提交一次回答。");
    mock = { ...mock, status: "FINISHED", formalInterviewId: "guide-record" };
    result = mock; action = "finish";
  } else if (method === "POST" && path === "/api/v1/weaknesses/analysis") {
    if (mock?.status !== "FINISHED") throw new Error("请先完成模拟并保存。");
    analyzed = true; result = analysis(); action = "analysis";
  } else if ((method === "POST" && path === "/api/v1/training-tasks") || (method === "PUT" && path === "/api/v1/training-tasks/guide-task")) {
    if (!analyzed) throw new Error("请先完成示例分析。");
    const status = required(body, "status");
    if (!["NOT_STARTED", "IN_PROGRESS", "COMPLETED"].includes(status)) throw new Error("请选择有效状态。");
    task = { id: "guide-task", title: required(body, "title"), weaknessTag: required(body, "weaknessTag"), action: required(body, "action"), status,
      createdAt: task?.createdAt ?? new Date().toISOString(), completedAt: status === "COMPLETED" ? new Date().toISOString() : null,
      source: { questionId: "guide-question", interviewId: "guide-record", reviewReportId: null, questionText: question().questionText, label: "操作指引 · 示例文本模拟", interviewType: "MOCK" },
    };
    result = task; action = method === "PUT" && status === "COMPLETED" ? "completed" : "task";
  } else {
    // Fail closed: an unsupported demo action must never reach the real API.
    throw new Error("体验区仅开放指引中的操作，不会修改正式数据。");
  }
  if (action) announce(action);
  return structuredClone(result) as T;
}
