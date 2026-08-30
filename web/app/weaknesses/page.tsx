"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Evidence = {
  questionId: string;
  reviewReportId: string | null;
  interviewId: string;
  questionText: string;
  company: string;
  role: string;
  interviewRound: string;
  interviewType: "REAL" | "MOCK";
  reason: string;
};
type Weakness = {
  tag: string;
  title: string;
  diagnosis: string;
  action: string;
  evidence: Evidence[];
};
type Analysis = {
  summary: string | null;
  analyzedAt: string | null;
  stale: boolean;
  items: Weakness[];
};
type TaskSource = {
  questionId: string | null;
  questionText: string | null;
  interviewId: string | null;
  reviewReportId: string | null;
  label: string | null;
  interviewType: "REAL" | "MOCK" | null;
};
type Task = {
  id: string;
  title: string;
  weaknessTag: string;
  action: string;
  status: "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED";
  createdAt: string;
  completedAt: string | null;
  source: TaskSource | null;
};

const statusLabels = { NOT_STARTED: "待开始", IN_PROGRESS: "进行中", COMPLETED: "已完成" };
const emptyDraft = { title: "", weaknessTag: "", action: "", status: "NOT_STARTED" as Task["status"], sourceQuestionId: "", sourceInterviewId: "", sourceReviewReportId: "" };

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export default function WeaknessesPage() {
  const [analysis, setAnalysis] = useState<Analysis>();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [activePanel, setActivePanel] = useState<"analysis" | "tasks">("analysis");
  const [draft, setDraft] = useState(emptyDraft);
  const [editing, setEditing] = useState<Task>();
  const [loading, setLoading] = useState(true);
  const [analyzing, setAnalyzing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dialog, setDialog] = useState<Task>();
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const initialLoadStarted = useRef(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [nextAnalysis, nextTasks] = await Promise.all([
        api<Analysis>("/api/v1/weaknesses/analysis"),
        api<Task[]>("/api/v1/training-tasks"),
      ]);
      setAnalysis(nextAnalysis);
      setTasks(nextTasks);
    } catch (cause) {
      setError(messageOf(cause, "薄弱点分析和训练任务加载失败。"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (initialLoadStarted.current) return;
    initialLoadStarted.current = true;
    void load();
  }, []);

  async function analyze() {
    setAnalyzing(true);
    setError("");
    try {
      const next = await api<Analysis>("/api/v1/weaknesses/analysis", { method: "POST" });
      setAnalysis(next);
      setMessage("AI 弱项分析已更新。");
    } catch (cause) {
      setError(messageOf(cause, "AI 分析失败，请稍后重试。"));
    } finally {
      setAnalyzing(false);
    }
  }

  function beginCreate(item: Weakness, evidence: Evidence) {
    setActivePanel("tasks");
    setEditing(undefined);
    setMessage("");
    setDraft({
      title: `练习：${item.title}`,
      weaknessTag: item.tag,
      action: item.action,
      status: "NOT_STARTED",
      sourceQuestionId: evidence.questionId,
      sourceInterviewId: evidence.interviewId,
      sourceReviewReportId: evidence.reviewReportId ?? "",
    });
    document.getElementById("weakness-panel-tabs")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function beginEdit(task: Task) {
    setActivePanel("tasks");
    setEditing(task);
    setMessage("");
    setDraft({
      title: task.title,
      weaknessTag: task.weaknessTag,
      action: task.action,
      status: task.status,
      sourceQuestionId: task.source?.questionId ?? "",
      sourceInterviewId: task.source?.interviewId ?? "",
      sourceReviewReportId: task.source?.reviewReportId ?? "",
    });
    document.getElementById("weakness-panel-tabs")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function saveTask(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const saved = await api<Task>(editing ? `/api/v1/training-tasks/${editing.id}` : "/api/v1/training-tasks", {
        method: editing ? "PUT" : "POST",
        body: JSON.stringify({
          ...draft,
          sourceQuestionId: draft.sourceQuestionId || null,
          sourceInterviewId: draft.sourceInterviewId || null,
          sourceReviewReportId: draft.sourceReviewReportId || null,
        }),
      });
      setTasks((current) => (editing ? current.map((item) => (item.id === saved.id ? saved : item)) : [saved, ...current]));
      setEditing(undefined);
      setDraft(emptyDraft);
      setMessage(editing ? "训练任务已更新。" : "训练任务已创建。");
    } catch (cause) {
      setError(messageOf(cause, "训练任务保存失败。"));
    } finally {
      setSaving(false);
    }
  }

  async function updateStatus(task: Task, status: Task["status"]) {
    try {
      const saved = await api<Task>(`/api/v1/training-tasks/${task.id}`, {
        method: "PUT",
        body: JSON.stringify({
          title: task.title,
          weaknessTag: task.weaknessTag,
          action: task.action,
          status,
          sourceQuestionId: task.source?.questionId ?? null,
          sourceInterviewId: task.source?.interviewId ?? null,
          sourceReviewReportId: task.source?.reviewReportId ?? null,
        }),
      });
      setTasks((current) => current.map((item) => (item.id === saved.id ? saved : item)));
      setMessage("训练状态已更新。");
    } catch (cause) {
      setError(messageOf(cause, "训练状态更新失败。"));
    }
  }

  async function removeTask() {
    if (!dialog) return;
    setDeleting(true);
    try {
      await api<void>(`/api/v1/training-tasks/${dialog.id}`, { method: "DELETE" });
      setTasks((current) => current.filter((item) => item.id !== dialog.id));
      setDialog(undefined);
      setMessage("训练任务已删除。");
    } catch (cause) {
      setError(messageOf(cause, "训练任务删除失败。"));
    } finally {
      setDeleting(false);
    }
  }

  const needsAnalysis = !analysis || analysis.stale || analysis.items.length === 0;

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero weaknesses-hero">
          <p className="eyebrow">AI ANALYSIS</p>
          <h1>把具体回答里的问题，<em>变成下一步练习。</em></h1>
          <p className="intro">基于当前面试记录、逐题复盘和关联简历的 AI 分析；不代表通过概率或招聘结论。</p>
        </section>
        <Toast error={error} notice={message} onDismissError={() => setError("")} onDismissNotice={() => setMessage("")} />
        {loading ? (
          <section className="library-section"><p className="muted">正在加载分析和训练任务…</p></section>
        ) : (
          <>
            <section className="library-section weakness-workspace">
            <div className="interview-tabs" id="weakness-panel-tabs" role="tablist" aria-label="薄弱点页面内容">
              <button className={`interview-tab${activePanel === "analysis" ? " active" : ""}`} type="button" role="tab" aria-selected={activePanel === "analysis"} onClick={() => setActivePanel("analysis")}>
                <strong>AI 分析</strong>
                <small>{analysis?.items.length ?? 0} 项</small>
              </button>
              <button className={`interview-tab${activePanel === "tasks" ? " active" : ""}`} type="button" role="tab" aria-selected={activePanel === "tasks"} onClick={() => setActivePanel("tasks")}>
                <strong>训练任务</strong>
                <small>{tasks.length} 条</small>
              </button>
            </div>
            {activePanel === "analysis" ? <div className="weakness-report">
              <div className="section-heading">
                <div>
                  <p className="profile-label">CURRENT SNAPSHOT</p>
                  <h2>薄弱点分析</h2>
                  <p className="muted">{analysis?.analyzedAt ? `最后分析：${new Date(analysis.analyzedAt).toLocaleString()}` : "尚未进行 AI 分析"}</p>
                </div>
                <button className="primary-button" type="button" onClick={() => void analyze()} disabled={analyzing}>
                  {analyzing ? "AI 分析处理中…" : analysis?.analyzedAt ? "重新分析" : "开始 AI 分析"}
                </button>
              </div>
              {needsAnalysis ? (
                <p className="analysis-empty" role="status">
                  {analysis?.stale ? "当前面试、逐题复盘或关联简历已变化。旧分析已隐藏，请重新分析。" : "暂无可用分析。完成面试记录和逐题复盘后，点击“开始 AI 分析”。"}
                </p>
              ) : (
                <div className="analysis-content">
                  <p className="analysis-summary">{analysis.summary}</p>
                  {analysis.items.map((item) => (
                    <article className="weakness-report-item" id={item.tag} key={item.tag}>
                      <header className="weakness-report-item-heading">
                        <div>
                          <p className="profile-label">{item.tag}</p>
                          <h3>{item.title}</h3>
                        </div>
                        {item.evidence[0] && <button className="secondary-button" type="button" onClick={() => beginCreate(item, item.evidence[0])}>根据该薄弱点创建训练任务</button>}
                      </header>
                      <dl className="analysis-details">
                        <div><dt>诊断</dt><dd>{item.diagnosis}</dd></div>
                        <div><dt>下一步行动</dt><dd>{item.action}</dd></div>
                      </dl>
                      <div className="evidence-list">
                        <h4>具体问题证据</h4>
                        {item.evidence.map((evidence) => (
                          <section className="question-evidence" key={evidence.questionId}>
                            <p className="question-evidence-text">{evidence.questionText}</p>
                            <p className="muted">{evidence.company} · {evidence.role} · {evidence.interviewRound} · {evidence.interviewType === "MOCK" ? "AI 模拟" : "真实面试"}</p>
                            <p><strong>关联理由：</strong>{evidence.reason}</p>
                            <div className="item-actions">
                              <Link className="text-link" href={`/interviews/${evidence.interviewId}`}>查看原面试</Link>
                              {evidence.reviewReportId && <Link className="text-link" href={`/interviews/${evidence.interviewId}/review`}>查看复盘</Link>}
                            </div>
                          </section>
                        ))}
                      </div>
                    </article>
                  ))}
                </div>
              )}
            </div> : <>
            <div className="training-task-panels">
            <section className="library-section weakness-section" id="training-task-editor">
              <div className="section-heading">
                <div>
                  <p className="profile-label">TRAINING TASKS</p>
                  <h2>{editing ? "编辑训练任务" : "创建训练任务"}</h2>
                  <p className="muted">任务保存练习内容快照；来源删除后，任务仍会保留。</p>
                </div>
                {editing && <button className="secondary-button" type="button" onClick={() => { setEditing(undefined); setDraft(emptyDraft); }}>取消编辑</button>}
              </div>
              {draft.weaknessTag ? (
                <form className="library-form" onSubmit={saveTask}>
                  <label className="field">标题<input required value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} /></label>
                  <label className="field">关联弱项标签<input readOnly value={draft.weaknessTag} /></label>
                  <label className="field">建议动作 / 练习内容<textarea required value={draft.action} onChange={(event) => setDraft({ ...draft, action: event.target.value })} /></label>
                  <label className="field">状态<select value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value as Task["status"] })}>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
                  <p className="muted">来源：{draft.sourceQuestionId ? "已精确关联问题" : "未关联（可选）"}</p>
                  <div className="form-actions"><button className="primary-button" disabled={saving}>{saving ? "正在保存…" : editing ? "保存任务" : "创建任务"}</button><button className="secondary-button" type="button" onClick={() => { setEditing(undefined); setDraft(emptyDraft); }}>清空</button></div>
                </form>
              ) : <p className="muted">从上方的薄弱点创建训练任务，保留首条具体问题作为精确来源。</p>}
            </section>
            <section className="library-section weakness-section">
              <div className="section-heading"><div><p className="profile-label">SAVED TASKS</p><h2>我的训练任务</h2></div></div>
              {tasks.length === 0 ? <p className="muted">暂无训练任务。</p> : (
                <ul className="resource-list">
                  {tasks.map((task) => (
                    <li className="resource-item" key={task.id}>
                      <div>
                        <strong>{task.title}</strong>
                        <p><span className="task-tag">{task.weaknessTag}</span> · 创建于 {new Date(task.createdAt).toLocaleString()}</p>
                        <p className="task-action">{task.action}</p>
                        {task.source?.questionText && <p>具体问题：{task.source.questionText}</p>}
                        {task.source?.label && <p className="task-source">来源：{task.source.label} {task.source.interviewId && <Link className="text-link" href={`/interviews/${task.source.interviewId}`}>查看面试</Link>} {task.source.reviewReportId && task.source.interviewId && <Link className="text-link" href={`/interviews/${task.source.interviewId}/review`}>查看复盘</Link>}</p>}
                      </div>
                      <div className="item-actions">
                        <select aria-label={`更新${task.title}状态`} value={task.status} onChange={(event) => void updateStatus(task, event.target.value as Task["status"])}>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
                        <button className="secondary-button" type="button" onClick={() => beginEdit(task)}>编辑</button>
                        <button className="danger-button" type="button" onClick={() => setDialog(task)}>删除</button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            </div>
            </>}
            </section>
          </>
        )}
      </main>
      <ConfirmDialog open={Boolean(dialog)} title="删除训练任务" description={dialog ? `确定删除“${dialog.title}”吗？` : ""} confirmLabel="删除" confirmTone="danger" busy={deleting} onCancel={() => setDialog(undefined)} onConfirm={() => void removeTask()} />
    </AppShell>
  );
}
