"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Package = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
};
type Interview = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  interviewTime: string;
  status: string;
  result: string;
  interviewPackageId: string;
  interviewType: "REAL" | "MOCK";
  simulationType: "REAL" | "AI_TEXT" | "AI_VOICE";
};
type Question = {
  id: string;
  questionText: string;
  answerText: string;
  selfAssessment: string;
  sortOrder: number;
};
type QuestionReview = {
  questionId: string;
  evaluation: string;
  answerEvidence: string;
  missingEvidence: string;
  improvementAction: string;
  recommendedAnswerStructure: string;
  possibleFollowups: string[];
};
type Review = {
  id: string;
  readiness: string;
  summary: string;
  weaknessTags: string[];
  createdAt: string;
  questionReviews: QuestionReview[];
};
type Detail = {
  interview: Interview;
  notes: string;
  transcript: string;
  questions: Question[];
  reviews: Review[];
};
type ImportedQuestion = {
  question: string;
  answer: string;
  orderIndex: number;
  speakerEvidence: string;
};
type ImportTask = {
  id: string;
  status: string;
  originalFilename: string;
  sizeBytes: number;
  transcript: string;
  error: string;
  questions: ImportedQuestion[];
  finalInterviewId?: string;
};

const empty = {
  company: "",
  role: "",
  interviewRound: "",
  interviewTime: "",
  interviewPackageId: "",
  status: "PENDING_REVIEW",
  result: "UNKNOWN",
  notes: "",
};
const labels: Record<string, string> = {
  PENDING_REVIEW: "待复盘",
  REVIEWED: "已复盘",
  UNKNOWN: "未知",
  PASSED: "通过",
  REJECTED: "拒绝",
  PENDING: "待定",
  GOOD: "答得好",
  UNCERTAIN: "不确定",
  UNANSWERED: "没答上",
  REAL: "真实面试",
  AI_TEXT: "AI 文本模拟",
  AI_VOICE: "AI 语音模拟",
};

const interviewGroups: Array<{
  type: Interview["simulationType"];
  title: string;
  empty: string;
}> = [
  { type: "REAL", title: "真实面试记录", empty: "暂无真实面试记录。" },
  { type: "AI_TEXT", title: "AI 文本模拟记录", empty: "暂无 AI 文本模拟记录。" },
  { type: "AI_VOICE", title: "AI 语音模拟记录", empty: "暂无 AI 语音模拟记录。" },
];

function localTime(value: string) {
  return value ? new Date(value).toISOString().slice(0, 16) : "";
}
function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

type PageMode = "list" | "new" | "detail" | "edit" | "questions" | "review";

export default function InterviewsPage() {
  const pathname = usePathname();
  const router = useRouter();
  const [, , pathSegment, action] = pathname.split("/");
  const mode: PageMode =
    pathSegment === "new"
      ? "new"
      : action === "edit"
        ? "edit"
        : action === "questions"
          ? "questions"
          : action === "review"
            ? "review"
            : pathSegment
              ? "detail"
              : "list";
  const interviewId =
    pathSegment && pathSegment !== "new" ? pathSegment : undefined;
  const [interviews, setInterviews] = useState<Interview[]>([]);
  const [activeInterviewType, setActiveInterviewType] =
    useState<Interview["simulationType"]>("REAL");
  const [packages, setPackages] = useState<Package[]>([]);
  const [detail, setDetail] = useState<Detail>();
  const [form, setForm] = useState(empty);
  const [editing, setEditing] = useState<string>();
  const [question, setQuestion] = useState({
    questionText: "",
    answerText: "",
    selfAssessment: "UNCERTAIN",
  });
  const [transcript, setTranscript] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [dialog, setDialog] = useState<
    { kind: "interview" | "question" | "review"; id: string } | undefined
  >();
  const [importTask, setImportTask] = useState<ImportTask>();
  const [importQuestions, setImportQuestions] = useState<ImportedQuestion[]>(
    [],
  );
  const [importingAudio, setImportingAudio] = useState(false);
  const [analyzingImport, setAnalyzingImport] = useState(false);
  const [draggingAudio, setDraggingAudio] = useState(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [nextInterviews, nextPackages] = await Promise.all([
        api<Interview[]>("/api/v1/interviews"),
        api<Package[]>("/api/v1/interview-packages"),
      ]);
      setInterviews(nextInterviews);
      setPackages(nextPackages);
    } catch (cause) {
      setError(errorMessage(cause, "面试记录加载失败。"));
    } finally {
      setLoading(false);
    }
  }
  async function loadDetail(id: string) {
    try {
      const nextDetail = await api<Detail>(`/api/v1/interviews/${id}`);
      setDetail(nextDetail);
      if (mode === "edit") {
        setEditing(nextDetail.interview.id);
        setTranscript(nextDetail.transcript);
        setForm({
          ...nextDetail.interview,
          interviewTime: localTime(nextDetail.interview.interviewTime),
          notes: nextDetail.notes,
        });
      }
    } catch (cause) {
      setError(errorMessage(cause, "面试详情加载失败。"));
    }
  }
  useEffect(() => {
    void load();
    if (interviewId) void loadDetail(interviewId);
  }, [interviewId, mode]);
  useEffect(() => {
    if (mode !== "questions" || !detail) return;
    const id = window.sessionStorage.getItem(
      `interview-audio-import-id-${detail.interview.id}`,
    );
    if (!id) return;
    void api<ImportTask>(`/api/v1/interview-imports/${id}`)
      .then((task) => {
        setImportTask(task);
        setImportQuestions(task.questions);
      })
      .catch(() => window.sessionStorage.removeItem("interview-audio-import-id"));
  }, [detail?.interview.id, mode]);

  async function saveInterview(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const body = {
        ...form,
        interviewTime: new Date(form.interviewTime).toISOString(),
      };
      const saved = await api<Detail>(
        `/api/v1/interviews${editing ? `/${editing}` : ""}`,
        { method: editing ? "PUT" : "POST", body: JSON.stringify(body) },
      );
      setDetail(saved);
      await load();
      router.replace(`/interviews/${saved.interview.id}`);
      setMessage("面试记录已保存。");
    } catch (cause) {
      setError(errorMessage(cause, "保存失败。"));
    } finally {
      setSaving(false);
    }
  }
  async function uploadAudio(file?: File) {
    if (!file || importingAudio) return;
    if (file.size > 25 * 1024 * 1024) {
      setError("录音超过 25 MiB，请缩短或压缩后重试。");
      return;
    }
    setImportingAudio(true);
    setError("");
    setMessage("正在上传录音并等待转写…");
    try {
      const data = new FormData();
      data.append("file", file);
      if (!detail) return;
      data.append("interviewId", detail.interview.id);
      const task = await api<ImportTask>("/api/v1/interview-imports/audio", {
        method: "POST",
        body: data,
      });
      setImportTask(task);
      setImportQuestions(task.questions);
      window.sessionStorage.setItem(
        `interview-audio-import-id-${detail.interview.id}`,
        task.id,
      );
      setMessage(
        task.status === "READY"
          ? "转写与问答识别完成，请检查后保存。"
          : "已保留转写结果，可重试分析或手工整理。",
      );
    } catch (cause) {
      setError(errorMessage(cause, "录音上传或转写失败。"));
    } finally {
      setImportingAudio(false);
    }
  }
  async function analyzeImported() {
    if (!importTask || analyzingImport) return;
    setAnalyzingImport(true);
    setError("");
    try {
      const task = await api<ImportTask>(
        `/api/v1/interview-imports/${importTask.id}/analyze`,
        { method: "POST" },
      );
      setImportTask(task);
      setImportQuestions(task.questions);
      setMessage(
        task.status === "READY"
          ? "问答识别完成，请检查后保存。"
          : "识别未完成，原始转写已保留，可继续手工整理。",
      );
    } catch (cause) {
      setError(errorMessage(cause, "问答识别失败。"));
    } finally {
      setAnalyzingImport(false);
    }
  }
  function updateImported(index: number, patch: Partial<ImportedQuestion>) {
    setImportQuestions((items) =>
      items.map((item, current) =>
        current === index ? { ...item, ...patch } : item,
      ),
    );
  }
  function moveImported(index: number, direction: -1 | 1) {
    setImportQuestions((items) => {
      const target = index + direction;
      if (target < 0 || target >= items.length) return items;
      const next = [...items];
      [next[index], next[target]] = [next[target], next[index]];
      return next.map((item, orderIndex) => ({ ...item, orderIndex: orderIndex + 1 }));
    });
  }
  async function saveImported() {
    if (!importTask || saving) return;
    setSaving(true);
    setError("");
    try {
      const saved = await api<Detail>(
        `/api/v1/interview-imports/${importTask.id}/confirm`,
        {
          method: "POST",
          body: JSON.stringify({
            questions: importQuestions.map((item, orderIndex) => ({
              ...item,
              orderIndex: orderIndex + 1,
            })),
          }),
        },
      );
      window.sessionStorage.removeItem(
        `interview-audio-import-id-${saved.interview.id}`,
      );
      setImportTask(undefined);
      setImportQuestions([]);
      await loadDetail(saved.interview.id);
      setMessage("识别问答已加入本场面试，请继续检查或发起复盘。");
    } catch (cause) {
      setError(errorMessage(cause, "正式保存失败，未创建面试记录。"));
    } finally {
      setSaving(false);
    }
  }
  function removeInterview(id: string) {
    setDialog({ kind: "interview", id });
  }
  async function confirmRemove() {
    if (!dialog) return;
    const current = dialog;
    setDeleting(true);
    setError("");
    try {
      if (current.kind === "interview") {
        await api<void>(`/api/v1/interviews/${current.id}`, {
          method: "DELETE",
        });
        await load();
        setMessage("面试、问答与复盘已删除。");
      } else if (current.kind === "question") {
        if (!detail) return;
        await api<void>(
          `/api/v1/interviews/${detail.interview.id}/questions/${current.id}`,
          { method: "DELETE" },
        );
        await loadDetail(detail.interview.id);
        setMessage("问答已删除。");
      } else {
        if (!detail) return;
        await api<void>(
          `/api/v1/interviews/${detail.interview.id}/reviews/${current.id}`,
          { method: "DELETE" },
        );
        await loadDetail(detail.interview.id);
        await load();
        setMessage("复盘已删除，薄弱点会按剩余历史重新计算。");
      }
      setDialog(undefined);
    } catch (cause) {
      setError(errorMessage(cause, "删除失败。"));
    } finally {
      setDeleting(false);
    }
  }
  async function addQuestion(event: FormEvent) {
    event.preventDefault();
    if (!detail) return;
    setError("");
    try {
      await api<Question>(
        `/api/v1/interviews/${detail.interview.id}/questions`,
        { method: "POST", body: JSON.stringify(question) },
      );
      setQuestion({
        questionText: "",
        answerText: "",
        selfAssessment: "UNCERTAIN",
      });
      await loadDetail(detail.interview.id);
      setMessage("问答已添加。");
    } catch (cause) {
      setError(errorMessage(cause, "添加问答失败。"));
    }
  }
  async function saveQuestion(item: Question) {
    if (!detail) return;
    setError("");
    try {
      await api<Question>(
        `/api/v1/interviews/${detail.interview.id}/questions/${item.id}`,
        { method: "PUT", body: JSON.stringify(item) },
      );
      await loadDetail(detail.interview.id);
      setMessage("问答已更新。");
    } catch (cause) {
      setError(errorMessage(cause, "更新问答失败。"));
    }
  }
  function removeQuestion(id: string) {
    if (!detail) return;
    setDialog({ kind: "question", id });
  }
  async function segment() {
    if (!detail) return;
    setError("");
    try {
      await api<Question[]>(
        `/api/v1/interviews/${detail.interview.id}/segment-transcript`,
        { method: "POST", body: JSON.stringify({ transcript }) },
      );
      await loadDetail(detail.interview.id);
      setMessage("已按空行分段并加入问答，请逐题确认或编辑。");
    } catch (cause) {
      setError(errorMessage(cause, "分段失败。"));
    }
  }
  async function review() {
    if (!detail) return;
    setReviewing(true);
    setError("");
    setMessage("");
    try {
      await api<Review>(`/api/v1/interviews/${detail.interview.id}/review`, {
        method: "POST",
      });
      await loadDetail(detail.interview.id);
      await load();
      setMessage("复盘已生成并保存。");
    } catch (cause) {
      setError(errorMessage(cause, "复盘失败，可检查问答后重新发起。"));
    } finally {
      setReviewing(false);
    }
  }
  function removeReview(reviewId: string) {
    if (!detail) return;
    setDialog({ kind: "review", id: reviewId });
  }

  const activeGroup =
    interviewGroups.find((group) => group.type === activeInterviewType) ??
    interviewGroups[0];
  const visibleInterviews = interviews.filter(
    (item) => item.simulationType === activeGroup.type,
  );
  const questionReadOnly = detail?.interview.simulationType !== "REAL";
  const audioImportPanel =
    mode === "questions" && detail && !questionReadOnly ? (
      <details className="fold-card audio-import" open={Boolean(importTask)}>
        <summary>从录音导入问答</summary>
        <p className="muted">上传录音 → 语音转写 → 识别问答 → 检查并加入本场问答。支持 WebM、Ogg、MP3、MP4/M4A、WAV，最大 25 MiB；服务端会校验文件内容。</p>
        {!importTask && (
          <label className={`audio-dropzone${draggingAudio ? " dragging" : ""}`} onDragOver={(event) => { event.preventDefault(); setDraggingAudio(true); }} onDragLeave={() => setDraggingAudio(false)} onDrop={(event) => { event.preventDefault(); setDraggingAudio(false); void uploadAudio(event.dataTransfer.files[0]); }}>
            <span>{importingAudio ? "正在上传、转写与识别…" : "选择或拖放面试录音"}</span>
            <input type="file" accept="audio/webm,audio/ogg,audio/mpeg,audio/mp4,audio/wav,.webm,.ogg,.mp3,.mp4,.m4a,.wav" disabled={importingAudio} onChange={(event) => void uploadAudio(event.target.files?.[0])} />
          </label>
        )}
        {importTask && (
          <div className="audio-import-result">
            <p><strong>{importTask.originalFilename}</strong> · {(importTask.sizeBytes / 1024 / 1024).toFixed(1)} MiB</p>
            <ol className="import-steps" aria-label="录音导入进度"><li className="done">上传录音</li><li className={importTask.transcript ? "done" : "current"}>语音转写</li><li className={importTask.status === "READY" ? "done" : "current"}>识别问答</li><li>检查并加入</li></ol>
            {importTask.error && <p role="alert" className="form-error">{importTask.error}</p>}
            {importTask.transcript && <details className="fold-card"><summary>查看原始转写文本</summary><pre className="transcript-preview">{importTask.transcript}</pre></details>}
            {importTask.transcript && importTask.status !== "READY" && <button className="secondary-button" type="button" disabled={analyzingImport} onClick={() => void analyzeImported()}>{analyzingImport ? "正在重新识别…" : "重试问答识别"}</button>}
            {importTask.transcript && (
              <div className="import-questions">
                <div className="section-heading"><h3>检查识别结果</h3><button className="secondary-button" type="button" onClick={() => setImportQuestions((items) => [...items, { question: "", answer: "", orderIndex: items.length + 1, speakerEvidence: "待确认" }])}>添加问答</button></div>
                {importQuestions.map((item, index) => (
                  <article className="question-card" key={`${item.orderIndex}-${index}`}>
                    <div className="question-card-head"><strong>第 {index + 1} 题</strong><div className="item-actions"><button className="icon-button" type="button" aria-label="上移问题" disabled={index === 0} onClick={() => moveImported(index, -1)}>↑</button><button className="icon-button" type="button" aria-label="下移问题" disabled={index === importQuestions.length - 1} onClick={() => moveImported(index, 1)}>↓</button><button className="danger-button" type="button" onClick={() => setImportQuestions((items) => items.filter((_, current) => current !== index).map((value, orderIndex) => ({ ...value, orderIndex: orderIndex + 1 })))}>删除</button></div></div>
                    <label className="field">问题<textarea value={item.question} onChange={(event) => updateImported(index, { question: event.target.value })} /></label>
                    <label className="field">回答<textarea value={item.answer} onChange={(event) => updateImported(index, { answer: event.target.value })} placeholder="空回答会加入为“没答上”" /></label>
                    {(item.answer.trim() === "" || item.speakerEvidence.includes("待确认")) && <p className="muted">待确认：{item.speakerEvidence || "未识别出明确回答"}</p>}
                  </article>
                ))}
                <div className="form-actions"><button className="primary-button" type="button" disabled={saving || importQuestions.length === 0} onClick={() => void saveImported()}>{saving ? "正在加入…" : "确认并加入本场问答"}</button></div>
              </div>
            )}
          </div>
        )}
      </details>
    ) : null;

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero interviews-hero">
          <p className="eyebrow">CLOSED LOOP 2</p>
          <h1>
            把面试现场，<em>变成下一次准备。</em>
          </h1>
          <p className="intro">
            请确认你有权记录本次面试内容。记录仅用于当前账户的复盘，不提供真实面试实时辅助。
          </p>
          <div className="hero-actions">
            <Link className="text-link" href="/library">
              管理面试包
            </Link>
            <Link className="secondary-button" href="/interviews/new">
              创建面试
            </Link>
          </div>
        </section>
        <Toast
          error={error}
          notice={message}
          onDismissError={() => setError("")}
          onDismissNotice={() => setMessage("")}
        />
        {mode === "list" && (
          <section className="library-section interview-list">
            {loading ? (
              <p className="muted">正在加载…</p>
            ) : (
              <>
                <div
                  className="interview-tabs"
                  role="tablist"
                  aria-label="面试记录类型"
                >
                  {interviewGroups.map((group) => {
                    const count = interviews.filter(
                      (item) => item.simulationType === group.type,
                    ).length;
                    return (
                      <button
                        className={`interview-tab${activeGroup.type === group.type ? " active" : ""}`}
                        type="button"
                        role="tab"
                        aria-selected={activeGroup.type === group.type}
                        onClick={() => setActiveInterviewType(group.type)}
                        key={group.type}
                      >
                        <strong>{labels[group.type]}</strong>
                        <small>{count} 条</small>
                      </button>
                    );
                  })}
                </div>
                <div className="section-heading interview-tab-heading">
                  <div>
                    <p className="profile-label">{labels[activeGroup.type]}</p>
                    <h2>{activeGroup.title}</h2>
                  </div>
                  {activeGroup.type !== "REAL" && (
                    <Link
                      className="secondary-button"
                      href={
                        activeGroup.type === "AI_TEXT"
                          ? "/mock-interviews"
                          : "/ai-mock-interviews"
                      }
                    >
                      {activeGroup.type === "AI_TEXT" ? "开始 AI 文本模拟" : "开始 AI 语音模拟"}
                    </Link>
                  )}
                </div>
                <ul className="resource-list">
                  {visibleInterviews.map((item) => (
                    <li key={item.id} className="resource-item">
                      <div>
                        <strong>
                          {item.company} · {item.role}
                        </strong>
                        <p>
                          {item.interviewRound} ·{" "}
                          {new Date(item.interviewTime).toLocaleString()} ·{" "}
                          {labels[item.status]} · {labels[item.result]}
                        </p>
                      </div>
                      <div className="item-actions">
                        <Link
                          className="secondary-button"
                          href={`/interviews/${item.id}`}
                        >
                          查看详情
                        </Link>
                        <button
                          className="danger-button"
                          type="button"
                          onClick={() => void removeInterview(item.id)}
                        >
                          删除
                        </button>
                      </div>
                    </li>
                  ))}
                  {visibleInterviews.length === 0 && (
                    <li className="muted">{activeGroup.empty}</li>
                  )}
                </ul>
              </>
            )}
          </section>
        )}
        {(mode === "new" || mode === "edit") && (
          <section className="library-section interview-form">
            <div className="section-heading">
              <h2>{mode === "edit" ? "编辑面试记录" : "新建面试记录"}</h2>
              <Link className="secondary-button" href="/interviews">
                返回历史
              </Link>
            </div>
            <form className="library-form" onSubmit={saveInterview}>
              <label className="field">
                关联面试包
                <select
                  required
                  value={form.interviewPackageId}
                  onChange={(event) => {
                    const selected = packages.find(
                      (item) => item.id === event.target.value,
                    );
                    setForm({
                      ...form,
                      interviewPackageId: event.target.value,
                      company: selected?.company ?? form.company,
                      role: selected?.role ?? form.role,
                      interviewRound:
                        selected?.interviewRound ?? form.interviewRound,
                    });
                  }}
                >
                  <option value="">请选择面试包</option>
                  {packages.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.company} · {item.role} · {item.interviewRound}
                    </option>
                  ))}
                </select>
                <small>
                  选择后自动带入公司、岗位和轮次；可按实际面试修改。
                </small>
              </label>
              <div className="form-row">
                <Field
                  label="公司"
                  value={form.company}
                  onChange={(company) => setForm({ ...form, company })}
                />
                <Field
                  label="岗位"
                  value={form.role}
                  onChange={(role) => setForm({ ...form, role })}
                />
              </div>
              <div className="form-row">
                <Field
                  label="面试轮次"
                  value={form.interviewRound}
                  onChange={(interviewRound) =>
                    setForm({ ...form, interviewRound })
                  }
                />
                <label className="field">
                  面试时间
                  <input
                    required
                    type="datetime-local"
                    value={form.interviewTime}
                    onChange={(event) =>
                      setForm({ ...form, interviewTime: event.target.value })
                    }
                  />
                </label>
              </div>
              <div className="form-row">
                <label className="field">
                  状态
                  <select
                    value={form.status}
                    onChange={(event) =>
                      setForm({ ...form, status: event.target.value })
                    }
                  >
                    <option value="PENDING_REVIEW">待复盘</option>
                    <option value="REVIEWED">已复盘</option>
                  </select>
                </label>
                <label className="field">
                  结果
                  <select
                    value={form.result}
                    onChange={(event) =>
                      setForm({ ...form, result: event.target.value })
                    }
                  >
                    <option value="UNKNOWN">未知</option>
                    <option value="PASSED">通过</option>
                    <option value="REJECTED">拒绝</option>
                    <option value="PENDING">待定</option>
                  </select>
                </label>
              </div>
              <Field
                label="备注"
                multiline
                value={form.notes}
                onChange={(notes) => setForm({ ...form, notes })}
                required={false}
              />
              <div className="form-actions">
                <button className="primary-button" disabled={saving}>
                  {saving
                    ? "正在保存…"
                    : mode === "edit"
                      ? "更新面试"
                      : "创建面试"}
                </button>
                <Link
                  className="secondary-button"
                  href={
                    mode === "edit" && editing
                      ? `/interviews/${editing}`
                      : "/interviews"
                  }
                >
                  取消
                </Link>
              </div>
            </form>
            {packages.length === 0 && (
              <p className="muted">请先在资料库创建面试包。</p>
            )}
          </section>
        )}
        {mode === "detail" && detail && (
          <section className="library-section interview-detail">
            <div className="section-heading">
              <div>
                <p className="profile-label">面试记录</p>
                <p className="profile-label">
                  {labels[detail.interview.simulationType]}
                </p>
                <h2>
                  {detail.interview.company} · {detail.interview.role}
                </h2>
                <p className="muted">
                  {detail.interview.interviewRound} ·{" "}
                  {labels[detail.interview.status]} ·{" "}
                  {labels[detail.interview.result]}
                </p>
              </div>
              <Link className="secondary-button" href="/interviews">
                返回历史
              </Link>
            </div>
            <div className="interview-overview">
              <div>
                <span className="profile-label">面试时间</span>
                <strong>
                  {new Date(detail.interview.interviewTime).toLocaleString()}
                </strong>
              </div>
              <div>
                <span className="profile-label">问答数量</span>
                <strong>{detail.questions.length} 道</strong>
              </div>
              <div>
                <span className="profile-label">复盘次数</span>
                <strong>{detail.reviews.length} 次</strong>
              </div>
            </div>
            <div className="interview-actions">
              <article className="navigation-card">
                <div>
                  <p className="profile-label">问答记录</p>
                  <h3>整理现场回答</h3>
                  <p className="muted">
                    添加、编辑或删除问题，也可以粘贴转写后自动分段。
                  </p>
                </div>
                <Link
                  className="primary-button"
                  href={`/interviews/${detail.interview.id}/questions`}
                >
                  进入问答记录
                </Link>
              </article>
              <article className="navigation-card">
                <div>
                  <p className="profile-label">AI 复盘</p>
                  <h3>
                    {detail.reviews.length ? "查看历史复盘" : "生成第一份复盘"}
                  </h3>
                  <p className="muted">
                    在独立页面查看准备度、薄弱项和逐题改进建议。
                  </p>
                </div>
                <Link
                  className="primary-button"
                  href={`/interviews/${detail.interview.id}/review`}
                >
                  {detail.reviews.length ? "查看复盘报告" : "进入复盘"}
                </Link>
              </article>
            </div>
            <div className="form-actions">
              <Link
                className="secondary-button"
                href={`/interviews/${detail.interview.id}/edit`}
              >
                编辑基本信息
              </Link>
              {detail.notes && (
                <details className="fold-card">
                  <summary>查看现场备注</summary>
                  <p>{detail.notes}</p>
                </details>
              )}
            </div>
          </section>
        )}
        {(mode === "questions" || mode === "review") && detail && (
          <section className="library-section interview-detail">
            <div className="section-heading">
              <div>
                <p className="profile-label">
                  {mode === "questions" ? "问答记录" : "AI 复盘"}
                </p>
                <h2>
                  {detail.interview.company} · {detail.interview.role}
                </h2>
                <p className="muted">
                  {detail.interview.interviewRound} ·{" "}
                  {new Date(detail.interview.interviewTime).toLocaleString()}
                </p>
              </div>
              <Link
                className="secondary-button"
                href={`/interviews/${detail.interview.id}`}
              >
                返回面试概览
              </Link>
            </div>
            {mode === "questions" && (
              <>
                <div className="subpage-intro">
                  <div>
                    <h3>{questionReadOnly ? "查看本场问答" : "确认这场面试的问答"}</h3>
                    <p className="muted">
                      {questionReadOnly
                        ? "AI 模拟的原始问答仅供查看，不能修改、添加或删除。"
                        : "先整理事实，再生成复盘。问题默认折叠，点击后编辑。"}
                    </p>
                  </div>
                  <Link
                    className="secondary-button"
                    href={`/interviews/${detail.interview.id}/review`}
                  >
                    去看复盘
                  </Link>
                </div>
                {audioImportPanel}
                {!questionReadOnly && (
                  <details className="fold-card" open>
                    <summary>粘贴转写并批量分段</summary>
                    <p className="muted">
                      每个“问题 +
                      回答”用一个空行分隔；系统会加入可编辑问答，不覆盖已有记录。
                    </p>
                    <textarea
                      value={transcript}
                      onChange={(event) => setTranscript(event.target.value)}
                      placeholder={
                        "问：项目最大的难点是什么？\n答：…\n\n问：为什么这样设计？\n答：…"
                      }
                    />
                    <div className="form-actions">
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={!transcript.trim()}
                        onClick={() => void segment()}
                      >
                        按空行分段并确认
                      </button>
                    </div>
                  </details>
                )}
                {!questionReadOnly && (
                  <details className="fold-card">
                    <summary>手动添加一道问答</summary>
                    <form className="library-form" onSubmit={addQuestion}>
                    <Field
                      label="问题"
                      value={question.questionText}
                      onChange={(questionText) =>
                        setQuestion({ ...question, questionText })
                      }
                    />
                    <Field
                      label="回答"
                      multiline
                      value={question.answerText}
                      onChange={(answerText) =>
                        setQuestion({ ...question, answerText })
                      }
                      required={question.selfAssessment !== "UNANSWERED"}
                    />
                    <label className="field">
                      自评
                      <select
                        value={question.selfAssessment}
                        onChange={(event) =>
                          setQuestion({
                            ...question,
                            selfAssessment: event.target.value,
                          })
                        }
                      >
                        <option value="GOOD">答得好</option>
                        <option value="UNCERTAIN">不确定</option>
                        <option value="UNANSWERED">没答上</option>
                      </select>
                    </label>
                    <div className="form-actions">
                      <button className="primary-button">添加问答</button>
                    </div>
                    </form>
                  </details>
                )}
                <div className="question-list">
                  {detail.questions.map((item) => (
                    <details className="question-card" key={item.id}>
                      <summary>
                        <span className="question-index">
                          问题 {item.sortOrder + 1}
                        </span>
                        <strong>{item.questionText}</strong>
                        <span className="question-status">
                          {labels[item.selfAssessment]}
                        </span>
                      </summary>
                      <div className="question-editor">
                        {questionReadOnly ? (
                          <div className="question-readonly">
                            <p>
                              <b>回答</b>
                              {item.answerText || "未作答"}
                            </p>
                            <p>
                              <b>自评</b>
                              {labels[item.selfAssessment]}
                            </p>
                          </div>
                        ) : (
                          <QuestionEditor
                            question={item}
                            onSave={saveQuestion}
                            onDelete={removeQuestion}
                          />
                        )}
                      </div>
                    </details>
                  ))}
                  {detail.questions.length === 0 && (
                    <p className="muted">
                      {questionReadOnly ? "暂无本场问答。" : "暂无问答；添加后才能发起复盘。"}
                    </p>
                  )}
                </div>
              </>
            )}
            {mode === "review" && (
              <>
                <div className="review-toolbar">
                  <div>
                    <h3>生成并查看复盘报告</h3>
                    <p className="muted">
                      报告只依据当前已确认的问答、简历、JD 和证据卡生成。
                    </p>
                  </div>
                  <div className="form-actions">
                    <Link
                      className="secondary-button"
                      href={`/interviews/${detail.interview.id}/questions`}
                    >
                      {questionReadOnly ? "查看问答" : "编辑问答"}
                    </Link>
                    <button
                      className="primary-button"
                      type="button"
                      disabled={reviewing || detail.questions.length === 0}
                      onClick={() => void review()}
                    >
                      {reviewing
                        ? "正在生成复盘…"
                        : detail.reviews.length
                          ? "重新生成复盘"
                          : "发起 AI 复盘"}
                    </button>
                  </div>
                </div>
                {detail.questions.length === 0 && (
                  <p className="muted">暂无问答，请先整理问答后再发起复盘。</p>
                )}
                <div className="review-list">
                  {detail.reviews.map((report) => (
                    <ReviewCard
                      key={report.id}
                      report={report}
                      questions={detail.questions}
                      onDelete={removeReview}
                    />
                  ))}
                  {detail.reviews.length === 0 &&
                    detail.questions.length > 0 && (
                      <p className="muted">尚未生成复盘，点击上方按钮开始。</p>
                    )}
                </div>
              </>
            )}
          </section>
        )}
        {(mode === "detail" || mode === "questions" || mode === "review") &&
          !detail &&
          !error && (
            <section className="library-section interview-detail">
              <p className="muted">正在加载面试详情…</p>
            </section>
          )}
      </main>
      <ConfirmDialog
        open={dialog !== undefined}
        title={
          dialog?.kind === "interview"
            ? "删除这场面试？"
            : dialog?.kind === "question"
              ? "删除这条问答？"
              : "删除这份复盘？"
        }
        description={
          dialog?.kind === "interview"
            ? "面试、全部问答和复盘都会被删除，操作无法撤销。"
            : dialog?.kind === "question"
              ? "问题、回答和自评都会被删除，操作无法撤销。"
              : "关联训练任务会保留，但来源复盘会清空，薄弱点会按剩余历史重新计算。"
        }
        confirmLabel="确认删除"
        cancelLabel="取消"
        confirmTone="danger"
        busy={deleting}
        onConfirm={() => void confirmRemove()}
        onCancel={() => setDialog(undefined)}
      />
    </AppShell>
  );
}

function Field({
  label,
  value,
  onChange,
  multiline = false,
  required = true,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  required?: boolean;
}) {
  return (
    <label className="field">
      {label}
      {multiline ? (
        <textarea
          required={required}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <input
          required={required}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </label>
  );
}

function QuestionEditor({
  question,
  onSave,
  onDelete,
}: {
  question: Question;
  onSave: (question: Question) => void;
  onDelete: (id: string) => void;
}) {
  const [draft, setDraft] = useState(question);
  useEffect(() => setDraft(question), [question]);
  return (
    <div>
      <p className="profile-label">问题 {question.sortOrder + 1}</p>
      <Field
        label="问题"
        value={draft.questionText}
        onChange={(questionText) => setDraft({ ...draft, questionText })}
      />
      <Field
        label="回答"
        multiline
        value={draft.answerText}
        onChange={(answerText) => setDraft({ ...draft, answerText })}
        required={draft.selfAssessment !== "UNANSWERED"}
      />
      <label className="field">
        自评
        <select
          value={draft.selfAssessment}
          onChange={(event) =>
            setDraft({ ...draft, selfAssessment: event.target.value })
          }
        >
          <option value="GOOD">答得好</option>
          <option value="UNCERTAIN">不确定</option>
          <option value="UNANSWERED">没答上</option>
        </select>
      </label>
      <div className="form-actions">
        <button
          className="secondary-button"
          type="button"
          onClick={() => onSave(draft)}
        >
          保存问答
        </button>
        <button
          className="danger-button"
          type="button"
          onClick={() => onDelete(question.id)}
        >
          删除
        </button>
      </div>
    </div>
  );
}

function ReviewCard({
  report,
  questions,
  onDelete,
}: {
  report: Review;
  questions: Question[];
  onDelete: (id: string) => void;
}) {
  const questionById = new Map(
    questions.map((item) => [item.id, item.questionText]),
  );
  return (
    <article className="review-card">
      <div className="section-heading">
        <div>
          <p className="profile-label">
            {new Date(report.createdAt).toLocaleString()}
          </p>
          <h3>准备度：{report.readiness}</h3>
        </div>
        <button
          className="danger-button"
          type="button"
          onClick={() => onDelete(report.id)}
        >
          删除复盘
        </button>
      </div>
      <p>{report.summary}</p>
      <p className="tag-list">
        {report.weaknessTags.map((tag) => (
          <span key={tag}>{tag}</span>
        ))}
      </p>
      <div className="review-question-list">
        {report.questionReviews.map((item) => (
          <details className="review-question" key={item.questionId}>
            <summary>
              <strong>
                {questionById.get(item.questionId) ?? "已删除的问题"}
              </strong>
              <span>查看建议</span>
            </summary>
            <div>
              <p>
                <b>评价：</b>
                {item.evaluation}
              </p>
              <p>
                <b>回答依据：</b>
                {item.answerEvidence}
              </p>
              <p>
                <b>缺失证据：</b>
                {item.missingEvidence}
              </p>
              <p>
                <b>改进动作：</b>
                {item.improvementAction}
              </p>
              <p>
                <b>推荐回答结构：</b>
                {item.recommendedAnswerStructure}
              </p>
              <p>
                <b>可能追问：</b>
                {item.possibleFollowups.join("；") || "待补充"}
              </p>
            </div>
          </details>
        ))}
      </div>
    </article>
  );
}
