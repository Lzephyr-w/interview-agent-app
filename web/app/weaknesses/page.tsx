"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Source = {
  interviewId: string;
  reviewReportId: string;
  company: string;
  role: string;
  interviewRound: string;
  interviewType: "REAL" | "MOCK";
  reviewedAt: string;
};
type Weakness = {
  tag: string;
  count: number;
  suggestion: {
    title: string;
    action: string;
    reason: string;
    missingEvidence: string;
    recommendedStructure: string;
  };
  sources: Source[];
};
type TaskSource = {
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

const statusLabels = {
  NOT_STARTED: "待开始",
  IN_PROGRESS: "进行中",
  COMPLETED: "已完成",
};
const emptyDraft = {
  title: "",
  weaknessTag: "",
  action: "",
  status: "NOT_STARTED" as Task["status"],
  sourceInterviewId: "",
  sourceReviewReportId: "",
};

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export default function WeaknessesPage() {
  const [weaknesses, setWeaknesses] = useState<Weakness[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [draft, setDraft] = useState(emptyDraft);
  const [editing, setEditing] = useState<Task>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [deleting, setDeleting] = useState(false);
  const [dialog, setDialog] = useState<Task>();

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [nextWeaknesses, nextTasks] = await Promise.all([
        api<Weakness[]>("/api/v1/weaknesses"),
        api<Task[]>("/api/v1/training-tasks"),
      ]);
      setWeaknesses(nextWeaknesses);
      setTasks(nextTasks);
    } catch (cause) {
      setError(messageOf(cause, "薄弱点和训练任务加载失败。"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function beginCreate(item: Weakness, source?: Source) {
    setEditing(undefined);
    setMessage("");
    setDraft({
      title: `练习：${item.suggestion.title}`,
      weaknessTag: item.tag,
      action: [
        item.suggestion.action,
        `缺失证据：${item.suggestion.missingEvidence}`,
        `推荐结构：${item.suggestion.recommendedStructure}`,
      ].join("\n"),
      status: "NOT_STARTED",
      sourceInterviewId: source?.interviewId ?? "",
      sourceReviewReportId: source?.reviewReportId ?? "",
    });
    document
      .getElementById("training-task-editor")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function beginEdit(task: Task) {
    setEditing(task);
    setMessage("");
    setDraft({
      title: task.title,
      weaknessTag: task.weaknessTag,
      action: task.action,
      status: task.status,
      sourceInterviewId: task.source?.interviewId ?? "",
      sourceReviewReportId: task.source?.reviewReportId ?? "",
    });
    document
      .getElementById("training-task-editor")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function saveTask(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const body = JSON.stringify({
        ...draft,
        sourceInterviewId: draft.sourceInterviewId || null,
        sourceReviewReportId: draft.sourceReviewReportId || null,
      });
      const saved = await api<Task>(
        editing
          ? `/api/v1/training-tasks/${editing.id}`
          : "/api/v1/training-tasks",
        { method: editing ? "PUT" : "POST", body },
      );
      setTasks((current) =>
        editing
          ? current.map((item) => (item.id === saved.id ? saved : item))
          : [saved, ...current],
      );
      setEditing(undefined);
      setDraft(emptyDraft);
      setMessage(editing ? "训练任务已更新。" : "训练任务已创建。");
    } catch (cause) {
      setError(messageOf(cause, "训练任务保存失败。"));
    } finally {
      setSaving(false);
    }
  }

  function removeTask(task: Task) {
    setDialog(task);
  }
  async function confirmRemoveTask() {
    if (!dialog) return;
    setDeleting(true);
    setError("");
    try {
      await api<void>(`/api/v1/training-tasks/${dialog.id}`, {
        method: "DELETE",
      });
      setTasks((current) => current.filter((item) => item.id !== dialog.id));
      setDialog(undefined);
      setMessage("训练任务已删除。");
    } catch (cause) {
      setError(messageOf(cause, "训练任务删除失败。"));
    } finally {
      setDeleting(false);
    }
  }

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero weaknesses-hero">
          <p className="eyebrow">CLOSED LOOP 3</p>
          <h1>
            把复盘里的薄弱点，<em>变成下一步练习。</em>
          </h1>
          <p className="intro">
            这里按历史复盘中的固定标签统计出现次数。次数是复盘证据数量，不代表趋势或通过概率。
          </p>
        </section>
        <Toast
          error={error}
          notice={message}
          onDismissError={() => setError("")}
          onDismissNotice={() => setMessage("")}
        />
        {loading ? (
          <section className="library-section">
            <p className="muted">正在加载薄弱点和训练任务…</p>
          </section>
        ) : (
          <>
            <section className="library-section weakness-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">TOP 3</p>
                  <h2>当前薄弱点</h2>
                  <p className="muted">每个标签在同一份复盘中最多计一次。</p>
                </div>
              </div>
              {weaknesses.length === 0 ? (
                <p className="muted">
                  还没有可聚合的历史复盘。完成一次 AI
                  复盘后，薄弱点会出现在这里。
                </p>
              ) : (
                <div className="weakness-grid">
                  {weaknesses.map((item) => (
                    <article className="weakness-card" key={item.tag}>
                      <div className="weakness-card-heading">
                        <div>
                          <p className="profile-label">{item.tag}</p>
                          <h3>{item.suggestion.title}</h3>
                        </div>
                        <strong className="weakness-count">
                          {item.count}
                          <small>次</small>
                        </strong>
                      </div>
                      <p>{item.suggestion.action}</p>
                      <dl className="suggestion-list">
                        <div>
                          <dt>缺失证据</dt>
                          <dd>{item.suggestion.missingEvidence}</dd>
                        </div>
                        <div>
                          <dt>为什么建议</dt>
                          <dd>{item.suggestion.reason}</dd>
                        </div>
                        <div>
                          <dt>推荐回答结构</dt>
                          <dd>{item.suggestion.recommendedStructure}</dd>
                        </div>
                      </dl>
                      <div className="form-actions">
                        <Link
                          className="secondary-button"
                          href={`/weaknesses/${encodeURIComponent(item.tag)}`}
                        >
                          查看详情
                        </Link>
                        <button
                          className="primary-button"
                          type="button"
                          onClick={() => beginCreate(item, item.sources[0])}
                        >
                          创建训练任务
                        </button>
                      </div>
                      <details className="source-details">
                        <summary>查看 {item.sources.length} 个来源</summary>
                        <ul className="source-list">
                          {item.sources.map((source) => (
                            <li key={source.reviewReportId}>
                              <div>
                                <strong>
                                  {source.company} · {source.role}
                                </strong>
                                <span
                                  className={`interview-type-label ${source.interviewType.toLowerCase()}`}
                                >
                                  {source.interviewType === "MOCK"
                                    ? "AI 模拟"
                                    : "真实面试"}
                                </span>
                                <p>
                                  {source.interviewRound} ·{" "}
                                  {new Date(source.reviewedAt).toLocaleString()}
                                </p>
                              </div>
                              <div className="item-actions">
                                <Link
                                  className="text-link"
                                  href={`/interviews/${source.interviewId}`}
                                >
                                  面试
                                </Link>
                                <Link
                                  className="text-link"
                                  href={`/interviews/${source.interviewId}/review`}
                                >
                                  复盘
                                </Link>
                                <button
                                  className="secondary-button"
                                  type="button"
                                  onClick={() => beginCreate(item, source)}
                                >
                                  用此来源
                                </button>
                              </div>
                            </li>
                          ))}
                        </ul>
                      </details>
                    </article>
                  ))}
                </div>
              )}
            </section>
            <section
              className="library-section weakness-section"
              id="training-task-editor"
            >
              <div className="section-heading">
                <div>
                  <p className="profile-label">TRAINING TASKS</p>
                  <h2>{editing ? "编辑训练任务" : "创建训练任务"}</h2>
                  <p className="muted">
                    任务保存建议快照；来源删除后任务仍保留，但来源入口会失效。
                  </p>
                </div>
                {editing && (
                  <button
                    className="secondary-button"
                    type="button"
                    onClick={() => {
                      setEditing(undefined);
                      setDraft(emptyDraft);
                    }}
                  >
                    取消编辑
                  </button>
                )}
              </div>
              {(draft.weaknessTag || editing) && (
                <form className="library-form" onSubmit={saveTask}>
                  <label className="field">
                    标题
                    <input
                      required
                      value={draft.title}
                      onChange={(event) =>
                        setDraft({ ...draft, title: event.target.value })
                      }
                    />
                  </label>
                  <label className="field">
                    关联弱项标签
                    <input readOnly value={draft.weaknessTag} />
                  </label>
                  <label className="field">
                    建议动作 / 练习内容
                    <textarea
                      required
                      value={draft.action}
                      onChange={(event) =>
                        setDraft({ ...draft, action: event.target.value })
                      }
                    />
                  </label>
                  <label className="field">
                    状态
                    <select
                      value={draft.status}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          status: event.target.value as Task["status"],
                        })
                      }
                    >
                      <option value="NOT_STARTED">待开始</option>
                      <option value="IN_PROGRESS">进行中</option>
                      <option value="COMPLETED">已完成</option>
                    </select>
                  </label>
                  <p className="muted">
                    来源：
                    {draft.sourceReviewReportId
                      ? "已关联复盘"
                      : draft.sourceInterviewId
                        ? "已关联面试"
                        : "未关联（可选）"}
                  </p>
                  <div className="form-actions">
                    <button className="primary-button" disabled={saving}>
                      {saving ? "正在保存…" : editing ? "保存任务" : "创建任务"}
                    </button>
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() => {
                        setEditing(undefined);
                        setDraft(emptyDraft);
                      }}
                    >
                      清空
                    </button>
                  </div>
                </form>
              )}
              {!draft.weaknessTag && (
                <p className="muted">
                  从上方任一弱项建议点击“创建训练任务”，开始记录可追踪练习。
                </p>
              )}
            </section>
            <section className="library-section weakness-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">SAVED TASKS</p>
                  <h2>我的训练任务</h2>
                </div>
              </div>
              {tasks.length === 0 ? (
                <p className="muted">暂无训练任务。</p>
              ) : (
                <ul className="resource-list">
                  {tasks.map((task) => (
                    <li className="resource-item" key={task.id}>
                      <div>
                        <strong>{task.title}</strong>
                        <p>
                          <span className="task-tag">{task.weaknessTag}</span> ·
                          创建于 {new Date(task.createdAt).toLocaleString()}
                          {task.completedAt &&
                            ` · 完成于 ${new Date(task.completedAt).toLocaleString()}`}
                        </p>
                        <p className="task-action">{task.action}</p>
                        {task.source?.label && (
                          <p>
                            来源：{task.source.label}{" "}
                            {task.source.interviewType && (
                              <span
                                className={`interview-type-label ${task.source.interviewType.toLowerCase()}`}
                              >
                                {task.source.interviewType === "MOCK"
                                  ? "AI 模拟"
                                  : "真实面试"}
                              </span>
                            )}{" "}
                            {task.source.interviewId && (
                              <Link
                                className="text-link"
                                href={`/interviews/${task.source.interviewId}`}
                              >
                                查看面试
                              </Link>
                            )}{" "}
                            {task.source.reviewReportId &&
                              task.source.interviewId && (
                                <Link
                                  className="text-link"
                                  href={`/interviews/${task.source.interviewId}/review`}
                                >
                                  查看复盘
                                </Link>
                              )}
                          </p>
                        )}
                      </div>
                      <div className="item-actions">
                        <select
                          aria-label={`更新${task.title}状态`}
                          value={task.status}
                          onChange={(event) => {
                            const status = event.target.value as Task["status"];
                            void (async () => {
                              try {
                                const saved = await api<Task>(
                                  `/api/v1/training-tasks/${task.id}`,
                                  {
                                    method: "PUT",
                                    body: JSON.stringify({
                                      title: task.title,
                                      weaknessTag: task.weaknessTag,
                                      action: task.action,
                                      status,
                                      sourceInterviewId:
                                        task.source?.interviewId ?? null,
                                      sourceReviewReportId:
                                        task.source?.reviewReportId ?? null,
                                    }),
                                  },
                                );
                                setTasks((current) =>
                                  current.map((item) =>
                                    item.id === saved.id ? saved : item,
                                  ),
                                );
                                setMessage("训练状态已更新。");
                              } catch (cause) {
                                setError(
                                  messageOf(cause, "训练状态更新失败。"),
                                );
                              }
                            })();
                          }}
                        >
                          {Object.entries(statusLabels).map(
                            ([value, label]) => (
                              <option key={value} value={value}>
                                {label}
                              </option>
                            ),
                          )}
                        </select>
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => beginEdit(task)}
                        >
                          编辑
                        </button>
                        <button
                          className="danger-button"
                          type="button"
                          onClick={() => void removeTask(task)}
                        >
                          删除
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </main>
      <ConfirmDialog
        open={dialog !== undefined}
        title="删除这项训练任务？"
        description={dialog ? `“${dialog.title}”会被删除，操作无法撤销。` : ""}
        confirmLabel="确认删除"
        confirmTone="danger"
        busy={deleting}
        onConfirm={() => void confirmRemoveTask()}
        onCancel={() => setDialog(undefined)}
      />
    </AppShell>
  );
}
