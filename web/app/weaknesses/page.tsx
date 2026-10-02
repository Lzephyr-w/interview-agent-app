"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { useWeaknessAnalysis, type WeaknessAnalysis, type WeaknessEvidence } from "@/components/WeaknessAnalysisProvider";
import { api } from "@/lib/api";

type Evidence = WeaknessEvidence;
type Weakness = {
  tag: string;
  title: string;
  diagnosis: string;
  action: string;
  evidence: Evidence[];
};
type Analysis = WeaknessAnalysis & { items: Weakness[] };
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
const weaknessTags = ["技术基础", "算法与数据结构", "系统设计", "项目深挖", "业务理解", "行为面", "沟通表达", "岗位匹配", "简历风险", "英语表达"];
const emptyDraft = { title: "", weaknessTag: "", action: "", status: "NOT_STARTED" as Task["status"], sourceQuestionId: "", sourceInterviewId: "", sourceReviewReportId: "" };

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export default function WeaknessesPage() {
  const { analysis: sharedAnalysis, analyzing, loadAnalysis: refreshAnalysis, startAnalysis } = useWeaknessAnalysis();
  const analysis = sharedAnalysis as Analysis | undefined;
  const [tasks, setTasks] = useState<Task[]>([]);
  const [activePanel, setActivePanel] = useState<"analysis" | "tasks">("analysis");
  const [composerOpen, setComposerOpen] = useState(false);
  const [statusFilter, setStatusFilter] = useState<Task["status"] | "ALL">("ALL");
  const [draft, setDraft] = useState(emptyDraft);
  const [editing, setEditing] = useState<Task>();
  const [analysisLoading, setAnalysisLoading] = useState(true);
  const [tasksLoading, setTasksLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dialog, setDialog] = useState<Task>();
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const initialLoadStarted = useRef(false);

  async function load() {
    setError("");
    void loadAnalysis();
    void loadTasks();
  }

  async function loadAnalysis() {
    try {
      await refreshAnalysis();
    } catch (cause) {
      setError(messageOf(cause, "薄弱点分析加载失败。"));
    } finally {
      setAnalysisLoading(false);
    }
  }

  async function loadTasks() {
    try {
      setTasks(await api<Task[]>("/api/v1/training-tasks"));
    } catch (cause) {
      setError(messageOf(cause, "训练任务加载失败。"));
    } finally {
      setTasksLoading(false);
    }
  }

  useEffect(() => {
    if (initialLoadStarted.current) return;
    initialLoadStarted.current = true;
    if (new URLSearchParams(window.location.search).get("tab") === "tasks") setActivePanel("tasks");
    void load();
  }, []);

  useEffect(() => {
    if (analysisLoading || !window.location.hash) return;
    let tag = window.location.hash.slice(1);
    try { tag = decodeURIComponent(tag); } catch { return; }
    if (!analysis?.items.some((item) => item.tag === tag)) return;
    document.getElementById(tag)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [analysis, analysisLoading]);

  async function analyze() {
    setError("");
    try {
      await startAnalysis();
    } catch (cause) {
      setError(messageOf(cause, "AI 分析失败，请稍后重试。"));
    }
  }

  function beginCreate(item: Weakness, evidence: Evidence) {
    setActivePanel("tasks");
    setComposerOpen(true);
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
    setComposerOpen(true);
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
      setTasks((current) => (editing ? current.map((item) => (item.id === saved.id ? saved : item)) : current.some((item) => item.id === saved.id) ? current : [saved, ...current]));
      setEditing(undefined);
      setDraft(emptyDraft);
      setComposerOpen(false);
      setStatusFilter("ALL");
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
  const visibleTasks = tasks.filter((task) => statusFilter === "ALL" || task.status === statusFilter);

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero weaknesses-hero">
          <p className="eyebrow">AI ANALYSIS</p>
          <h1>把具体回答里的问题，<em>变成下一步练习。</em></h1>
          <p className="intro">基于当前面试记录、逐题复盘和关联简历的 AI 分析；不代表通过概率或招聘结论。</p>
        </section>
        <Toast error={error} notice={message} onDismissError={() => setError("")} onDismissNotice={() => setMessage("")} />
        <>
            <section className="library-section weakness-workspace">
            <div className="interview-tabs" id="weakness-panel-tabs" role="tablist" aria-label="薄弱点页面内容">
              <button className={`interview-tab${activePanel === "analysis" ? " active" : ""}`} type="button" role="tab" aria-selected={activePanel === "analysis"} onClick={() => { setActivePanel("analysis"); window.history.replaceState(null, "", "/weaknesses?tab=analysis"); }}>
                <strong>AI 分析</strong>
                <small>{analysisLoading ? "加载中" : `${analysis?.items.length ?? 0} 项`}</small>
              </button>
              <button className={`interview-tab${activePanel === "tasks" ? " active" : ""}`} type="button" role="tab" aria-selected={activePanel === "tasks"} onClick={() => { setActivePanel("tasks"); window.history.replaceState(null, "", "/weaknesses?tab=tasks"); }}>
                <strong>训练任务</strong>
                <small>{tasksLoading ? "加载中" : `${tasks.length} 条`}</small>
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
              {analysisLoading ? (
                <p className="analysis-empty" role="status">正在加载 AI 分析…</p>
              ) : needsAnalysis ? (
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
                {composerOpen ? <button className="secondary-button" type="button" disabled={saving} onClick={() => { setComposerOpen(false); setEditing(undefined); setDraft(emptyDraft); }}>取消</button> : <button className="primary-button training-create-button" type="button" onClick={() => { setEditing(undefined); setDraft(emptyDraft); setComposerOpen(true); }}>＋ 创建训练任务</button>}
              </div>
              {composerOpen ? (
                <form className="library-form" onSubmit={saveTask}>
                  <label className="field">标题<input required value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} /></label>
                  <label className="field">关联弱项标签{draft.sourceQuestionId ? <input readOnly value={draft.weaknessTag} /> : <select required value={draft.weaknessTag} onChange={(event) => setDraft({ ...draft, weaknessTag: event.target.value })}><option value="" hidden>请选择弱项标签</option>{weaknessTags.map((tag) => <option key={tag} value={tag}>{tag}</option>)}</select>}</label>
                  <label className="field">建议动作 / 练习内容<textarea required value={draft.action} onChange={(event) => setDraft({ ...draft, action: event.target.value })} /></label>
                  <label className="field">状态<select value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value as Task["status"] })}>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
                  <p className="muted">来源：{draft.sourceQuestionId ? "已精确关联问题" : "未关联（可选）"}</p>
                  <div className="form-actions"><button className="primary-button" disabled={saving}>{saving ? "正在保存…" : editing ? "保存任务" : "创建任务"}</button><button className="secondary-button" type="button" onClick={() => { setEditing(undefined); setDraft(emptyDraft); }}>清空</button></div>
                </form>
              ) : <p className="muted">直接添加练习计划，也可以从 AI 分析结果创建关联任务。</p>}
            </section>
            <section className="library-section weakness-section saved-training-tasks">
              <div className="section-heading"><div><p className="profile-label">SAVED TASKS</p><h2>我的训练任务</h2></div><div className="training-task-toolbar">{!tasksLoading && <span className="training-task-count">{statusFilter === "ALL" ? `${tasks.length} 项任务` : `${visibleTasks.length} / ${tasks.length} 项任务`}</span>}<select aria-label="筛选任务状态" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as Task["status"] | "ALL")}><option value="ALL">全部状态</option>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div></div>
              {tasksLoading ? <p className="muted">正在加载训练任务…</p> : visibleTasks.length === 0 ? <p className="muted">{tasks.length === 0 ? "暂无训练任务，点击上方按钮创建第一项任务。" : "当前状态下暂无任务，可以切换其他状态查看。"}</p> : (
                <ul className="training-task-list">
                  {visibleTasks.map((task) => (
                    <li className="training-task-card" key={task.id}>
                      <header className="training-task-header">
                        <div>
                          <h3>{task.title}</h3>
                          <div className="training-task-meta"><span className="task-tag">{task.weaknessTag}</span><time dateTime={task.createdAt}>创建于 {new Date(task.createdAt).toLocaleDateString()}</time></div>
                        </div>
                        <select className="training-task-status" data-status={task.status} aria-label={`更新${task.title}状态`} value={task.status} onChange={(event) => void updateStatus(task, event.target.value as Task["status"])}>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
                      </header>
                      <div className="training-task-content"><span>练习内容</span><p>{task.action}</p></div>
                      {task.source?.questionText && <div className="training-task-question"><span>关联问题</span><p>{task.source.questionText}</p></div>}
                      <footer className="training-task-footer">
                        <div className="training-task-source">
                          {task.source?.label && <span>来源：{task.source.label}</span>}
                          {task.source?.interviewId && <Link href={`/interviews/${task.source.interviewId}`}>查看面试 ↗</Link>}
                          {task.source?.reviewReportId && task.source.interviewId && <Link href={`/interviews/${task.source.interviewId}/review`}>查看复盘 ↗</Link>}
                        </div>
                        <div className="training-task-actions">
                          <button type="button" onClick={() => beginEdit(task)}>编辑任务</button>
                          <button className="training-task-delete" type="button" onClick={() => setDialog(task)}>删除</button>
                        </div>
                      </footer>
                    </li>
                  ))}
                </ul>
              )}
            </section>
            </div>
            </>}
            </section>
        </>
      </main>
      <ConfirmDialog open={Boolean(dialog)} title="删除训练任务" description={dialog ? `确定删除“${dialog.title}”吗？` : ""} confirmLabel="删除" confirmTone="danger" busy={deleting} onCancel={() => setDialog(undefined)} onConfirm={() => void removeTask()} />
    </AppShell>
  );
}
