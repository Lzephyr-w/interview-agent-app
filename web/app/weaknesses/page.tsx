"use client";

import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { useWeaknessAnalysis, type WeaknessAnalysis, type WeaknessEvidence } from "@/components/WeaknessAnalysisProvider";
import { api } from "@/lib/api";

type Evidence = WeaknessEvidence;
type Weakness = WeaknessAnalysis["items"][number];
type Panel = "analysis" | "details" | "tasks";
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
const dimensionGroups = [
  { title: "技术能力", description: "从原理理解到工程落地", tags: ["技术基础", "算法与数据结构", "系统设计", "性能优化", "工程质量", "问题排查"] },
  { title: "项目与业务", description: "讲清选择、价值与实际贡献", tags: ["项目深挖", "业务理解", "技术权衡", "成果量化"] },
  { title: "表达与协作", description: "让回答清晰，让协作有结果", tags: ["沟通表达", "行为面", "协作推进", "英语表达"] },
  { title: "岗位与简历", description: "连接真实经历与岗位要求", tags: ["岗位匹配", "简历风险"] },
];
const weaknessTags = dimensionGroups.flatMap((group) => group.tags);
const panels: { id: Panel; label: string }[] = [
  { id: "analysis", label: "分析总览" }, { id: "details", label: "维度详情" }, { id: "tasks", label: "训练任务" },
];
const emptyDraft = { title: "", weaknessTag: "", action: "", status: "NOT_STARTED" as Task["status"], sourceQuestionId: "", sourceInterviewId: "", sourceReviewReportId: "" };

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export default function WeaknessesPage() {
  const { analysis, analyzing, loadAnalysis: refreshAnalysis, startAnalysis } = useWeaknessAnalysis();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [activePanel, setActivePanel] = useState<Panel>("analysis");
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
  const [selectedTag, setSelectedTag] = useState("");
  const initialLoadStarted = useRef(false);
  const focusDimension = useRef(false);

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
    void load();
  }, []);

  useEffect(() => {
    function syncLocation() {
      const panel = new URLSearchParams(window.location.search).get("tab");
      let tag = "";
      try { tag = decodeURIComponent(window.location.hash.slice(1)); } catch { /* Ignore malformed hashes. */ }
      const validTag = weaknessTags.includes(tag);
      focusDimension.current = validTag && panel !== "tasks";
      setSelectedTag(validTag ? tag : "");
      setActivePanel(panel === "tasks" ? "tasks" : panel === "details" || validTag ? "details" : "analysis");
    }
    syncLocation();
    window.addEventListener("popstate", syncLocation);
    window.addEventListener("hashchange", syncLocation);
    return () => {
      window.removeEventListener("popstate", syncLocation);
      window.removeEventListener("hashchange", syncLocation);
    };
  }, []);

  useEffect(() => {
    if (activePanel === "details" && !analysisLoading && focusDimension.current) {
      document.getElementById("weakness-dimension-detail")?.focus({ preventScroll: true });
      focusDimension.current = false;
    }
  }, [activePanel, selectedTag, analysisLoading]);

  function selectPanel(panel: Panel, tag?: string, focusDetail = true) {
    const dimension = tag || selectedTag || analysis?.items[0]?.tag || weaknessTags[0];
    focusDimension.current = panel === "details" && Boolean(tag) && focusDetail;
    setActivePanel(panel);
    if (panel === "details") setSelectedTag(dimension);
    const params = new URLSearchParams(window.location.search);
    params.set("tab", panel);
    window.history.replaceState(null, "", `${window.location.pathname}?${params}${panel === "details" ? `#${encodeURIComponent(dimension)}` : ""}`);
  }

  function moveTab(event: KeyboardEvent<HTMLDivElement>) {
    const index = panels.findIndex((panel) => panel.id === activePanel);
    const next = event.key === "ArrowRight" ? (index + 1) % panels.length : event.key === "ArrowLeft" ? (index + panels.length - 1) % panels.length : event.key === "Home" ? 0 : event.key === "End" ? panels.length - 1 : -1;
    if (next < 0) return;
    event.preventDefault();
    selectPanel(panels[next].id);
    document.getElementById(`weakness-${panels[next].id}-tab`)?.focus();
  }

  async function analyze() {
    setError("");
    try {
      await startAnalysis();
    } catch (cause) {
      setError(messageOf(cause, "AI 分析失败，请稍后重试。"));
    }
  }

  function beginCreate(item: Weakness, evidence: Evidence) {
    selectPanel("tasks");
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
    selectPanel("tasks");
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

  const hasAnalysis = Boolean(analysis?.analyzedAt && !analysis.stale);
  const items = hasAnalysis ? analysis!.items : [];
  const itemsByTag = new Map(items.map((item) => [item.tag, item]));
  const currentTag = selectedTag || items[0]?.tag || weaknessTags[0];
  const currentGroup = dimensionGroups.find((group) => group.tags.includes(currentTag)) || dimensionGroups[0];
  const selectedItem = itemsByTag.get(currentTag);
  const evidenceCount = items.reduce((count, item) => count + item.evidence.length, 0);
  const visibleTasks = tasks.filter((task) => statusFilter === "ALL" || task.status === statusFilter);

  return (
    <AppShell>
      <main className="app-page weaknesses-page">
        <section className="hero-card page-hero weaknesses-hero">
          <p className="eyebrow">AI ANALYSIS</p>
          <h1>把具体回答里的问题，<em>变成下一步练习。</em></h1>
          <p className="intro">从技术、项目、表达和岗位四个方向梳理回答，先看总览，再逐项诊断，安排有针对性的练习。</p>
        </section>
        <Toast error={error} notice={message} onDismissError={() => setError("")} onDismissNotice={() => setMessage("")} />
        <section className="library-section weakness-workspace">
            <div className="interview-tabs" id="weakness-panel-tabs" role="tablist" aria-label="薄弱点页面内容" onKeyDown={moveTab}>
              {panels.map((panel) => (
                <button key={panel.id} id={`weakness-${panel.id}-tab`} className={`interview-tab${activePanel === panel.id ? " active" : ""}`} type="button" role="tab" aria-selected={activePanel === panel.id} aria-controls={`weakness-${panel.id}-panel`} tabIndex={activePanel === panel.id ? 0 : -1} onClick={() => selectPanel(panel.id)}>
                  <strong>{panel.label}</strong>
                  <small>{panel.id === "details" ? `${weaknessTags.length} 个维度` : panel.id === "tasks" ? tasksLoading ? "加载中" : `${tasks.length} 条` : analysisLoading ? "加载中" : `${items.length} 项待改进`}</small>
                </button>
              ))}
            </div>
            {activePanel !== "tasks" ? <div className="weakness-report" id={`weakness-${activePanel}-panel`} role="tabpanel" aria-labelledby={`weakness-${activePanel}-tab`}>
              <div className="section-heading">
                <div>
                  <p className="profile-label">{activePanel === "analysis" ? "ANALYSIS OVERVIEW" : "DIMENSION DETAIL"}</p>
                  <h2>{activePanel === "analysis" ? "薄弱点分析总览" : "逐项看清，逐项改善"}</h2>
                  <p className="muted">{analysis?.analyzedAt ? `最后分析：${new Date(analysis.analyzedAt).toLocaleString()}` : "尚未进行 AI 分析"}</p>
                </div>
                <button className="primary-button" data-guide="analyze" type="button" onClick={() => void analyze()} disabled={analyzing}>
                  {analyzing ? "AI 分析处理中…" : analysis?.analyzedAt ? "重新分析" : "开始 AI 分析"}
                </button>
              </div>
              {analyzing && <p className="weakness-analysis-progress" role="status">正在梳理回答与复盘证据，完成后将更新分析。你可以先查看已有结果或安排训练。</p>}
              {analysisLoading ? (
                <p className="analysis-empty" role="status">正在加载 AI 分析…</p>
              ) : activePanel === "analysis" ? (
                <div className="weakness-overview">
                  {hasAnalysis ? <div className="weakness-summary-card">
                    <div><span className="weakness-summary-marker">本次分析</span><p className="analysis-summary">{analysis!.summary}</p></div>
                    <dl className="weakness-metrics">
                      <div><dt>待改进维度</dt><dd>{items.length}<small>项</small></dd></div>
                      <div><dt>关联问题</dt><dd>{evidenceCount}<small>道</small></dd></div>
                      <div><dt>待完成训练</dt><dd>{tasksLoading ? "—" : tasks.filter((task) => task.status !== "COMPLETED").length}<small>项</small></dd></div>
                    </dl>
                  </div> : <p className="analysis-empty" role="status">{analysis?.stale ? "面试回答、逐题复盘或关联简历已变化，请重新分析以查看最新诊断。" : "完成面试记录后，点击“开始 AI 分析”，从 16 个维度发现值得练习的方向。"}</p>}
                  <div className="weakness-dimension-heading"><h3>四个方向，找到练习重点</h3><p className="muted">点击类目查看诊断与题目证据；暂无分析项的维度仍可浏览。</p></div>
                  <div className="weakness-group-grid">
                    {dimensionGroups.map((group, index) => {
                      const groupItems = items.filter((item) => group.tags.includes(item.tag));
                      return <section className="weakness-group-card" key={group.title}>
                        <header><span className="weakness-group-number">0{index + 1}</span><div><h4>{group.title}</h4><p>{group.description}</p></div><span className="weakness-group-count">{groupItems.length} 项</span></header>
                        <div className="weakness-dimension-chips">{group.tags.map((tag) => <button key={tag} type="button" data-identified={itemsByTag.has(tag)} onClick={() => selectPanel("details", tag)} aria-label={`查看${tag}详情`}>{itemsByTag.has(tag) && <span aria-hidden="true">● </span>}{tag}</button>)}</div>
                        {groupItems.length ? <ul className="weakness-focus-list">{groupItems.map((item) => <li key={item.tag}><button type="button" onClick={() => selectPanel("details", item.tag)}><span>{item.title}</span><small>{item.evidence.length} 道问题 <span aria-hidden="true">↗</span></small></button></li>)}</ul> : <p className="weakness-group-empty">{hasAnalysis ? "本次暂无有证据支持的分析项" : "分析后在这里查看练习重点"}</p>}
                      </section>;
                    })}
                  </div>
                </div>
              ) : (
                <div className="weakness-detail-layout">
                  <nav className="weakness-group-nav" aria-label="薄弱点分组">
                    <div className="weakness-nav-heading"><h3>能力分组</h3><p>4 个方向 · 16 个维度</p></div>
                    <div className="weakness-group-links">
                      {dimensionGroups.map((group, index) => <button key={group.title} type="button" aria-label={`查看${group.title}分组`} aria-pressed={currentGroup.title === group.title} aria-controls="weakness-dimension-categories" onClick={() => selectPanel("details", currentGroup.title === group.title ? currentTag : group.tags.find((tag) => itemsByTag.has(tag)) || group.tags[0], false)}>
                        <span className="weakness-nav-number" aria-hidden="true">0{index + 1}</span>
                        <span><strong>{group.title}</strong><small>{group.tags.filter((tag) => itemsByTag.has(tag)).length} 项待改进</small></span>
                        <span className="weakness-nav-arrow" aria-hidden="true">›</span>
                      </button>)}
                    </div>
                  </nav>
                  <div className="weakness-detail-main">
                    <section className="weakness-category-panel" id="weakness-dimension-categories" aria-labelledby="weakness-category-title">
                      <header><div><p className="profile-label">当前分组</p><h3 id="weakness-category-title">{currentGroup.title}</h3><p className="muted">{currentGroup.description}，选择下方类目查看详细诊断。</p></div><span className="weakness-category-count">{currentGroup.tags.length} 个细分维度</span></header>
                      <nav className="weakness-category-list" data-count={currentGroup.tags.length} aria-label={`${currentGroup.title}细分维度`}>
                        {currentGroup.tags.map((tag) => <button key={tag} type="button" aria-label={`查看${tag}详情`} aria-pressed={currentTag === tag} aria-controls="weakness-dimension-detail" data-identified={itemsByTag.has(tag)} onClick={() => selectPanel("details", tag, false)}>
                          <span>{tag}</span><small>{itemsByTag.has(tag) ? `${itemsByTag.get(tag)!.evidence.length} 道问题证据` : "暂无分析项"}</small>
                        </button>)}
                      </nav>
                    </section>
                    <div id="weakness-dimension-detail" tabIndex={-1} aria-label={`${currentTag}分析详情`}>
                      {selectedItem ? <article className="weakness-report-item" key={currentTag}>
                        <header className="weakness-report-item-heading">
                          <div>
                            <p className="profile-label">{selectedItem.tag} · {selectedItem.evidence.length} 道问题证据</p>
                            <h3>{selectedItem.title}</h3>
                          </div>
                          {selectedItem.evidence[0] && <button className="primary-button" type="button" onClick={() => beginCreate(selectedItem, selectedItem.evidence[0])}>创建关联训练</button>}
                        </header>
                        <dl className="analysis-details">
                          <div><dt>为什么需要改进</dt><dd>{selectedItem.diagnosis}</dd></div>
                          <div className="weakness-next-action"><dt>下一步怎么练</dt><dd>{selectedItem.action}</dd></div>
                        </dl>
                        <div className="evidence-list">
                          <h4>回答证据 <span>展开问题，回到具体回答</span></h4>
                          {selectedItem.evidence.map((evidence, index) => (
                            <details className="question-evidence" key={evidence.questionId} open={index === 0}>
                              <summary><span className="weakness-evidence-number">{String(index + 1).padStart(2, "0")}</span><span className="question-evidence-text">{evidence.questionText}</span></summary>
                              <div className="weakness-evidence-body">
                                <p className="muted">{evidence.company} · {evidence.role} · {evidence.interviewRound} · {evidence.interviewType === "MOCK" ? "AI 模拟" : "真实面试"}</p>
                                <p><strong>关联理由：</strong>{evidence.reason}</p>
                                <div className="item-actions">
                                  <Link className="text-link" href={`/interviews/${evidence.interviewId}`}>查看原面试 ↗</Link>
                                  {evidence.reviewReportId && <Link className="text-link" href={`/interviews/${evidence.interviewId}/review`}>查看复盘 ↗</Link>}
                                  <button className="text-button" type="button" onClick={() => beginCreate(selectedItem, evidence)}>以此问题创建训练</button>
                                </div>
                              </div>
                            </details>
                          ))}
                        </div>
                      </article> : <div className="weakness-dimension-empty"><span className="weakness-summary-marker">{currentTag}</span><h3>{analysis?.stale ? "分析需要更新" : hasAnalysis ? "该维度暂无分析项" : "等待第一次分析"}</h3><p>{analysis?.stale ? "回答与复盘资料已变化，重新分析后再查看具体诊断。" : hasAnalysis ? "当前回答证据未形成该维度的具体薄弱点。这不代表已经掌握，可以继续积累相关面试回答。" : "记录面试回答并开始 AI 分析后，在这里查看诊断、练习建议和具体问题。"}</p><button className="secondary-button" type="button" onClick={() => selectPanel("analysis")}>返回分析总览</button></div>}
                    </div>
                  </div>
                </div>
              )}
            </div> : <div className="training-task-panels" id="weakness-tasks-panel" role="tabpanel" aria-labelledby="weakness-tasks-tab">
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
                  <label className="field">关联弱项标签{draft.sourceQuestionId ? <input readOnly value={draft.weaknessTag} /> : <select required value={draft.weaknessTag} onChange={(event) => setDraft({ ...draft, weaknessTag: event.target.value })}><option value="" hidden>请选择弱项标签</option>{dimensionGroups.map((group) => <optgroup key={group.title} label={group.title}>{group.tags.map((tag) => <option key={tag} value={tag}>{tag}</option>)}</optgroup>)}</select>}</label>
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
            </div>}
        </section>
      </main>
      <ConfirmDialog open={Boolean(dialog)} title="删除训练任务" description={dialog ? `确定删除“${dialog.title}”吗？` : ""} confirmLabel="删除" confirmTone="danger" busy={deleting} onCancel={() => setDialog(undefined)} onConfirm={() => void removeTask()} />
    </AppShell>
  );
}
