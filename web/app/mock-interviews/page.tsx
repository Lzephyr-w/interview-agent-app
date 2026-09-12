"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
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

type Question = {
  id: string;
  questionText: string;
  answerText: string;
  aiFeedback: string;
  selfAssessment: string;
  questionKind: "MAIN" | "FOLLOW_UP";
  parentQuestionId: string | null;
  state: "PENDING" | "OPEN" | "ANSWERED" | "SKIPPED";
  sortOrder: number;
};

type MockInterview = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  status: "RUNNING" | "FINISHED";
  mode: string;
  aiAvailable: boolean;
  aiMessage: string;
  totalQuestions: number;
  completedQuestions: number;
  currentQuestionIndex: number;
  formalInterviewId: string | null;
  currentQuestion: Question | null;
  questions: Question[];
  task: { id: string; taskType: string; status: "PENDING" | "PROCESSING" | "FAILED"; error: string } | null;
};

const emptyForm = { packageId: "", company: "", role: "", round: "" };

function message(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

function mainQuestionNumber(question: Question, questions: Question[]) {
  const mainId = question.questionKind === "FOLLOW_UP" ? question.parentQuestionId : question.id;
  const index = questions.filter((item) => item.questionKind === "MAIN").findIndex((item) => item.id === mainId);
  return index >= 0 ? index + 1 : 1;
}

export default function MockInterviewsPage() {
  const [packages, setPackages] = useState<Package[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [session, setSession] = useState<MockInterview>();
  const [pendingSession, setPendingSession] = useState<MockInterview>();
  const [answer, setAnswer] = useState("");
  const [answerError, setAnswerError] = useState("");
  const [assessment, setAssessment] = useState("UNCERTAIN");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<"finish" | "abandon" | "resume" | null>(null);
  const firstQuestionAnnounced = useRef(false);

  useEffect(() => {
    async function bootstrap() {
      setLoading(true);
      try {
        const loaded = await api<Package[]>("/api/v1/interview-packages");
        setPackages(loaded);
      } catch (cause) {
        setError(message(cause, "面试包加载失败，请稍后重试。"));
      } finally {
        setLoading(false);
      }
    }
    void bootstrap();
  }, []);

  useEffect(() => {
    if (!session?.task || session.task.status === "FAILED") return;
    const timer = window.setInterval(() => {
      void api<MockInterview>(`/api/v1/mock-interviews/${session.id}`).then(setSession).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [session?.id, session?.task?.id, session?.task?.status]);

  useEffect(() => {
    void api<MockInterview>("/api/v1/mock-interviews")
      .then((active) => {
        setPendingSession(active);
        setDialog("resume");
      })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    setAnswer(session?.currentQuestion?.answerText ?? "");
    setAnswerError("");
    setAssessment(session?.currentQuestion?.selfAssessment ?? "UNCERTAIN");
  }, [session?.currentQuestion?.id]);

  useEffect(() => {
    if (session?.currentQuestionIndex === 1 && session.currentQuestion && !firstQuestionAnnounced.current) {
      firstQuestionAnnounced.current = true;
      setNotice("AI 已生成第一题。");
    }
  }, [session?.currentQuestion, session?.currentQuestionIndex]);

  function selectPackage(packageId: string) {
    const selected = packages.find((item) => item.id === packageId);
    setForm({
      packageId,
      company: selected?.company ?? "",
      role: selected?.role ?? "",
      round: selected?.interviewRound ?? "",
    });
  }

  async function start(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    firstQuestionAnnounced.current = false;
    try {
      const created = await api<MockInterview>("/api/v1/mock-interviews", {
        method: "POST",
        body: JSON.stringify({
          interviewPackageId: form.packageId,
          company: form.company,
          role: form.role,
          interviewRound: form.round,
        }),
      });
      setSession(created);
    } catch (cause) {
      setError(message(cause, "模拟创建失败，请稍后重试。"));
    } finally {
      setSaving(false);
    }
  }

  async function submitAnswer(event: FormEvent) {
    event.preventDefault();
    if (!session?.currentQuestion) return;
    if (!answer.trim()) {
      setAnswerError("请填写回答；若不作答请点击“跳过”。");
      document.getElementById("mock-answer")?.focus();
      return;
    }
    setAnswerError("");
    setSaving(true);
    setError("");
    try {
      const updated = await api<MockInterview>(
        `/api/v1/mock-interviews/${session.id}/answer`,
        {
          method: "POST",
          body: JSON.stringify({
            questionId: session.currentQuestion.id,
            answerText: answer,
            selfAssessment: assessment,
          }),
        },
      );
      setSession(updated);
      setNotice("回答已保存，进入下一题。");
    } catch (cause) {
      setError(message(cause, "回答提交失败，当前内容已保留，可重试。"));
    } finally {
      setSaving(false);
    }
  }

  async function skip() {
    if (!session?.currentQuestion) return;
    setSaving(true);
    setError("");
    try {
      const updated = await api<MockInterview>(
        `/api/v1/mock-interviews/${session.id}/skip`,
        {
          method: "POST",
          body: JSON.stringify({ questionId: session.currentQuestion.id }),
        },
      );
      setSession(updated);
      setNotice("已跳过当前问题，不会生成虚假回答。");
    } catch (cause) {
      setError(message(cause, "跳过失败，请重试。"));
    } finally {
      setSaving(false);
    }
  }

  async function finishConfirmed() {
    if (!session) return;
    setSaving(true);
    setError("");
    try {
      const finished = await api<MockInterview>(
        `/api/v1/mock-interviews/${session.id}/finish`,
        { method: "POST" },
      );
      setSession(finished);
      setNotice("文本模拟已完成，已保存本场逐题复盘。");
    } catch (cause) {
      setError(message(cause, "保存失败，已提交内容仍保留，可重试。"));
    } finally {
      setSaving(false);
    }
  }

  async function abandonConfirmed(target = session) {
    if (!target) return;
    setSaving(true);
    try {
      await api<void>(`/api/v1/mock-interviews/${target.id}`, {
        method: "DELETE",
      });
      setSession(undefined);
      setPendingSession(undefined);
      setDialog(null);
      setNotice("未完成模拟已放弃。");
    } catch (cause) {
      setError(message(cause, "放弃失败，请稍后重试。"));
    } finally {
      setSaving(false);
    }
  }

  async function retryTask() {
    if (!session?.task) return;
    setSaving(true);
    try {
      await api(`/api/v1/ai-mock-tasks/${session.task.id}/retry`, { method: "POST" });
      setSession(await api<MockInterview>(`/api/v1/mock-interviews/${session.id}`));
    } catch (cause) {
      setError(message(cause, "重试失败，请稍后重试。"));
    } finally {
      setSaving(false);
    }
  }

  function finish() {
    if (session) setDialog("finish");
  }

  function abandon() {
    if (session) setDialog("abandon");
  }

  const history = session?.questions.filter((item) =>
    ["ANSWERED", "SKIPPED"].includes(item.state),
  );
  const lastSubmitted = history?.[history.length - 1];
  const pendingFollowUp = Boolean(
    session?.task &&
      !session.currentQuestion &&
      lastSubmitted?.questionKind === "MAIN" &&
      lastSubmitted.answerText.trim(),
  );
  const currentQuestionIsFollowUp = Boolean(
    pendingFollowUp || session?.currentQuestion?.questionKind === "FOLLOW_UP",
  );
  const displayQuestionIndex = pendingFollowUp
    ? session?.completedQuestions
    : session?.currentQuestionIndex;
  const blocksQuestion = Boolean(
    session?.task &&
      (session.task.taskType !== "MOCK_FEEDBACK" || !session.currentQuestion),
  );
  const feedbackTask = session?.task?.taskType === "MOCK_FEEDBACK" ? session.task : null;

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero text-mock-hero">
          <p className="eyebrow">CLOSED LOOP 4</p>
          <h1>
            用一轮回答，<em>练到经得起追问。</em>
          </h1>
          <p className="intro">
            AI 文本模拟只用于训练，不会包装成真实面试结果。结束后会保存为已有面试记录，可继续进入复盘。
          </p>
        </section>
        <Toast
          error={error}
          notice={notice}
          onDismissError={() => setError("")}
          onDismissNotice={() => setNotice("")}
        />
        {loading ? (
          <section className="library-section mock-section">
            <p className="muted">正在加载面试包…</p>
          </section>
        ) : session ? (
          <section className="library-section mock-section">
            <div className="section-heading">
              <div>
                <p className="profile-label">
                  {session.company} · {session.role}
                </p>
                <h2>{session.interviewRound} 文本模拟</h2>
              </div>
              {session.status === "RUNNING" && (
                <button
                  className="secondary-button"
                  type="button"
                onClick={() => void abandon()}
                disabled={saving}
              >
                  取消不保存
                </button>
              )}
            </div>
            {session.status === "RUNNING" ? (
              <>
                <div className="mock-status" role="status">
                  <strong>AI 文字面试</strong>
                  <span>{session.aiMessage}</span>
                </div>
                <div
                  className="mock-progress"
                  aria-label={`当前第 ${displayQuestionIndex} / ${session.totalQuestions} 题${currentQuestionIsFollowUp ? "，追问" : ""}`}
                >
                  <strong>
                    第 {displayQuestionIndex} / {session.totalQuestions} 题
                    {currentQuestionIsFollowUp && <small className="mock-progress-followup">追问</small>}
                  </strong>
                  <span>
                    已完成 {session.completedQuestions} 道主问题；追问不计入 4 道主问题上限
                  </span>
                </div>
                {blocksQuestion && session.task?.status === "FAILED" ? (
                  <div className="mock-complete">
                    <h3>{session.task.taskType === "MOCK_FEEDBACK" ? "反馈生成失败" : "AI 处理失败"}</h3>
                    <p className="error">{session.task.error || "后台处理失败，请重试。"}</p>
                    <button className="primary-button" type="button" onClick={() => void retryTask()} disabled={saving}>重试</button>
                    <button className="secondary-button" type="button" onClick={finish} disabled={saving}>结束并保存已答内容</button>
                  </div>
                ) : blocksQuestion && session.task ? (
                  <div className="mock-complete" role="status">
                    <h3>{session.task.taskType === "MOCK_FEEDBACK" ? "正在生成反馈…" : "正在准备下一题…"}</h3>
                    <p className="muted">AI 正在处理，已保存回答不会丢失。</p>
                  </div>
                ) : session.currentQuestion ? (
                  <>
                    {feedbackTask && (
                      <div className="mock-status" role="status">
                        {feedbackTask.status === "FAILED" ? (
                          <>
                            <strong>上一题反馈生成失败</strong>
                            <span className="error">{feedbackTask.error || "后台处理失败，请重试。"}</span>
                            <button className="secondary-button" type="button" onClick={() => void retryTask()} disabled={saving}>重试反馈</button>
                          </>
                        ) : (
                          <span>上一题反馈生成中，不影响继续答题。</span>
                        )}
                      </div>
                    )}
                    <form className="mock-question" onSubmit={submitAnswer}>
                    <p className="profile-label">
                      {session.currentQuestion.questionKind === "FOLLOW_UP"
                        ? "有限追问"
                        : "主问题"}
                    </p>
                    <h3>{session.currentQuestion.questionText}</h3>
                    <label className="field" htmlFor="mock-answer">
                      我的回答
                      <textarea
                        id="mock-answer"
                        autoFocus
                        value={answer}
                        aria-invalid={Boolean(answerError)}
                        aria-describedby={
                          answerError ? "mock-answer-error" : undefined
                        }
                        onChange={(event) => {
                          setAnswer(event.target.value);
                          if (answerError) setAnswerError("");
                        }}
                        placeholder="只写你确定的经历、事实和指标；没有的信息请写“待补充”。"
                      />
                      {answerError && (
                        <p
                          id="mock-answer-error"
                          className="error"
                          role="alert"
                        >
                          {answerError}
                        </p>
                      )}
                    </label>
                    <div className="form-row">
                      <label className="field">
                        自评
                        <select
                          value={assessment}
                          onChange={(event) =>
                            setAssessment(event.target.value)
                          }
                        >
                          <option value="GOOD">答得好</option>
                          <option value="UNCERTAIN">不确定</option>
                          <option value="UNANSWERED">没答上</option>
                        </select>
                      </label>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button" disabled={saving}>
                        {saving ? "正在保存…" : "提交回答"}
                      </button>
                      <button
                        className="secondary-button"
                        type="button"
                        onClick={() => void skip()}
                        disabled={saving}
                      >
                        跳过
                      </button>
                      <button
                        className="secondary-button"
                        type="button"
                        onClick={() => void finish()}
                        disabled={saving}
                      >
                        结束并保存
                      </button>
                    </div>
                    </form>
                  </>
                ) : (
                  <div className="mock-complete">
                    <h3>题目已完成</h3>
                    <p className="muted">可以结束并保存为正式面试记录。</p>
                    <button
                      className="primary-button"
                      type="button"
                      onClick={() => void finish()}
                      disabled={saving}
                    >
                      结束并保存
                    </button>
                  </div>
                )}
                {history && history.length > 0 && (
                  <div className="mock-history">
                    <h3>已提交内容（点击题目查看详情）</h3>
                    <ol>
                      {history.map((item) => (
                        <li className="mock-history-item" key={item.id}>
                          <details>
                            <summary>
                              <span className="mock-history-index">
                                {mainQuestionNumber(item, session.questions)}
                              </span>
                              <strong>{item.questionText}</strong>
                              <small>
                                {item.questionKind === "FOLLOW_UP" ? "追问 · " : ""}
                                {item.state === "SKIPPED" ? "已跳过" : "已回答"}
                              </small>
                            </summary>
                            <div className="mock-history-detail">
                              <p>
                                <b>我的回答</b>
                                {item.state === "SKIPPED" ? "已跳过" : item.answerText}
                              </p>
                              {item.aiFeedback && (
                                <p>
                                  <b>AI 反馈</b>
                                  {item.aiFeedback}
                                </p>
                              )}
                            </div>
                          </details>
                        </li>
                      ))}
                    </ol>
                  </div>
                )}
              </>
            ) : (
              <div className="mock-complete">
                <div className="section-heading">
                  <div>
                    <h3>AI 文本模拟已保存</h3>
                    <p className="muted">
                      以下是本场全部题目、回答与逐题 AI 反馈；正式记录结果仍默认为未知。
                    </p>
                  </div>
                  <div className="form-actions">
                    {session.formalInterviewId && (
                      <>
                        <Link
                          className="primary-button"
                          href={`/interviews/${session.formalInterviewId}`}
                        >
                          查看面试记录
                        </Link>
                        <Link
                          className="secondary-button"
                          href={`/interviews/${session.formalInterviewId}/review`}
                        >
                          进入复盘
                        </Link>
                      </>
                    )}
                    <Link className="secondary-button" href="/mock-interviews">
                      再来一轮
                    </Link>
                  </div>
                </div>
                <div className="mock-history">
                  <h3>本场复盘</h3>
                  <ol>
                    {session.questions.map((item) => (
                      <li key={item.id}>
                        <strong>{item.questionText}</strong>
                        <span>{item.state === "SKIPPED" ? "已跳过" : item.answerText}</span>
                        <em>AI 反馈：{item.aiFeedback || "待补充"}</em>
                      </li>
                    ))}
                  </ol>
                </div>
              </div>
            )}
          </section>
        ) : (
          <section className="library-section mock-section mock-config">
            <div className="section-heading">
              <div>
                <p className="profile-label">开始训练</p>
                <h2>选择面试包</h2>
              </div>
              <Link className="secondary-button" href="/library">
                管理资料
              </Link>
            </div>
            {packages.length === 0 ? (
              <p className="muted">
                暂无可用面试包，请先在资料库创建并关联简历、JD。
              </p>
            ) : (
              <form className="library-form" onSubmit={start}>
                <label className="field">
                  面试包
                  <select
                    required
                    value={form.packageId}
                    onChange={(event) => selectPackage(event.target.value)}
                  >
                    <option value="">请选择面试包</option>
                    {packages.map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.company} · {item.role} · {item.interviewRound}
                      </option>
                    ))}
                  </select>
                </label>
                <div className="form-row">
                  <label className="field">
                    公司
                    <input
                      required
                      value={form.company}
                      onChange={(event) =>
                        setForm({ ...form, company: event.target.value })
                      }
                    />
                  </label>
                  <label className="field">
                    岗位
                    <input
                      required
                      value={form.role}
                      onChange={(event) =>
                        setForm({ ...form, role: event.target.value })
                      }
                    />
                  </label>
                </div>
                <label className="field">
                  面试轮次
                  <input
                    required
                    value={form.round}
                    onChange={(event) =>
                      setForm({ ...form, round: event.target.value })
                    }
                  />
                </label>
                <p className="muted">
                  每次开始都会创建一场新的文字模拟。AI 将结合已解析简历、JD 和证据卡出题；缺失资料会正常降级为待补充。
                </p>
                <button className="primary-button" disabled={saving}>
                  {saving ? "正在创建…" : "开始 AI 文本模拟"}
                </button>
              </form>
            )}
          </section>
        )}
      </main>
      <ConfirmDialog
        open={dialog !== null}
        title={
          dialog === "finish"
            ? "是否保存此面试？"
            : dialog === "resume"
              ? "检测到未完成的文本模拟"
              : "取消这场 AI 文本模拟？"
        }
        description={
          dialog === "finish"
            ? "结束后会保存为正式面试记录，已提交的问题和回答会保留，结果默认为未知。"
            : dialog === "resume"
              ? "是否继续上一次未完成的面试？"
              : "已提交内容不会保存为正式面试记录，当前文字模拟会话将被放弃。"
        }
        confirmLabel={
          dialog === "finish"
              ? "保存为面试记录"
              : dialog === "resume"
                ? "继续面试"
                : "取消不保存"
        }
        cancelLabel={dialog === "resume" ? "取消" : "再想想"}
        confirmTone={dialog === "abandon" ? "danger" : "primary"}
        busy={saving}
        onConfirm={() => {
          if (dialog === "finish") {
            setDialog(null);
            void finishConfirmed();
          } else if (dialog === "resume") {
            setSession(pendingSession);
            setPendingSession(undefined);
            setDialog(null);
          } else {
            void abandonConfirmed();
          }
        }}
        onCancel={() => {
          if (dialog === "resume") {
            void abandonConfirmed(pendingSession);
            return;
          }
          setDialog(null);
        }}
      />
    </AppShell>
  );
}
