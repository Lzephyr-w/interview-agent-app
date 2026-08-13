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
