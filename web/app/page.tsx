"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Me = { id: string; username: string; displayName: string | null };
type Dashboard = {
  overview: {
    interviewPackageCount: number;
    resumeFileCount: number;
    pendingReviewCount: number;
    pendingTrainingTaskCount: number;
  };
  recentActivities: Activity[];
  weaknesses: Weakness[];
  sprintItems: SprintItem[];
};
type Activity = {
  type: string;
  title: string;
  detail: string;
  targetPath: string;
  occurredAt: string;
};
type Weakness = { tag: string; count: number; targetPath: string };
type SprintItem = {
  id: string;
  kind: string;
  title: string;
  description: string;
  source: string;
  targetPath: string;
  priority: number;
  status: "TODO" | "DONE";
  editable: boolean;
  updatedAt: string | null;
};
type SprintDraft = {
  title: string;
  description: string;
  targetPath: string;
  priority: number;
  status: SprintItem["status"];
};

const blankDraft: SprintDraft = {
  title: "",
  description: "",
  targetPath: "",
  priority: 50,
  status: "TODO",
};

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

function dateText(value: string) {
  return new Date(value).toLocaleString();
}

export default function HomePage() {
  const router = useRouter();
  const [me, setMe] = useState<Me>();
  const [dashboard, setDashboard] = useState<Dashboard>();
  const [draft, setDraft] = useState<SprintDraft>(blankDraft);
  const [editing, setEditing] = useState<SprintItem>();
  const [deleting, setDeleting] = useState<SprintItem>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [currentMe, currentDashboard] = await Promise.all([
        api<Me>("/api/v1/me"),
        api<Dashboard>("/api/v1/dashboard"),
      ]);
      setMe(currentMe);
      setDashboard(currentDashboard);
    } catch (cause) {
      setError(messageOf(cause, "首页加载失败。"));
      if (cause instanceof Error && cause.message.includes("登录")) {
        router.replace("/login");
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function updateItem(saved: SprintItem) {
    setDashboard((current) =>
      current
        ? {
            ...current,
            sprintItems: current.sprintItems.some(
              (item) => item.id === saved.id,
            )
              ? current.sprintItems.map((item) =>
                  item.id === saved.id ? saved : item,
                )
              : [saved, ...current.sprintItems],
          }
        : current,
    );
  }

  async function saveItem(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    try {
      const saved = await api<SprintItem>(
        editing
          ? `/api/v1/sprint-checklist-items/${editing.id}`
          : "/api/v1/sprint-checklist-items",
        { method: editing ? "PUT" : "POST", body: JSON.stringify(draft) },
      );
      updateItem(saved);
      setEditing(undefined);
      setDraft(blankDraft);
      setNotice(editing ? "冲刺项已更新。" : "冲刺项已加入清单。");
    } catch (cause) {
      setError(messageOf(cause, "冲刺项保存失败。"));
    } finally {
      setSaving(false);
    }
  }

  async function toggleItem(item: SprintItem) {
    try {
      const saved = await api<SprintItem>(
        `/api/v1/sprint-checklist-items/${item.id}`,
        {
          method: "PUT",
          body: JSON.stringify({
            title: item.title,
            description: item.description,
            targetPath: item.targetPath,
            priority: item.priority,
            status: item.status === "TODO" ? "DONE" : "TODO",
          }),
        },
      );
      updateItem(saved);
      setNotice(
        saved.status === "DONE" ? "冲刺项已完成。" : "冲刺项已恢复待办。",
      );
    } catch (cause) {
      setError(messageOf(cause, "冲刺项状态更新失败。"));
    }
  }

  async function deleteItem() {
    if (!deleting) return;
    setSaving(true);
    try {
      await api<void>(`/api/v1/sprint-checklist-items/${deleting.id}`, {
        method: "DELETE",
      });
      setDashboard((current) =>
        current
          ? {
              ...current,
              sprintItems: current.sprintItems.filter(
                (item) => item.id !== deleting.id,
              ),
            }
          : current,
      );
      setDeleting(undefined);
      setNotice("冲刺项已删除。");
    } catch (cause) {
      setError(messageOf(cause, "冲刺项删除失败。"));
    } finally {
      setSaving(false);
    }
  }

  const overview = dashboard?.overview ?? {
    interviewPackageCount: 0,
    resumeFileCount: 0,
    pendingReviewCount: 0,
    pendingTrainingTaskCount: 0,
  };
  return (
    <AppShell>
      <main className="app-page dashboard-page">
        <Toast
          error={error}
          notice={notice}
          onDismissError={() => setError("")}
          onDismissNotice={() => setNotice("")}
        />
        {loading || !dashboard ? (
          <section className="library-section">
            <p className="muted">正在整理当前准备进度…</p>
          </section>
        ) : (
          <>
            <section className="hero-card page-hero dashboard-hero">
              <p className="eyebrow">PREPARATION DESK</p>
              <h1>
                {me?.displayName ?? me?.username ?? "你好"}，
                <em>从下一步开始。</em>
              </h1>
              <p className="intro">
                所有数字和行动都来自你当前账户的真实资料、记录与复盘。
              </p>
              <div className="hero-actions">
                <Link className="text-link" href="/library">
                  创建面试包
                </Link>
                <Link className="text-link" href="/interviews/new">
                  录入面试记录
                </Link>
                <Link className="text-link" href="/mock-interviews">
                  开始 AI 文本模拟
                </Link>
              </div>
            </section>

            <section className="dashboard-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">OVERVIEW</p>
                  <h2>准备概览</h2>
                </div>
              </div>
              <div className="dashboard-stats">
                {[
                  ["面试包", overview.interviewPackageCount, "/library"],
                  ["简历文件", overview.resumeFileCount, "/library"],
                  [
                    "待复盘真实面试",
                    overview.pendingReviewCount,
                    "/interviews",
                  ],
                  [
                    "待完成训练任务",
                    overview.pendingTrainingTaskCount,
                    "/weaknesses",
                  ],
                ].map(([label, value, href]) => (
                  <Link
                    className="dashboard-stat"
                    href={href as string}
                    key={label as string}
                  >
                    <span>{label}</span>
                    <strong>{value}</strong>
                  </Link>
                ))}
              </div>
            </section>

            <div className="dashboard-columns">
              <section className="library-section dashboard-section">
                <div className="section-heading">
                  <div>
                    <p className="profile-label">RECENT</p>
                    <h2>近期动态</h2>
                  </div>
                </div>
                {dashboard.recentActivities.length === 0 ? (
                  <p className="muted">
                    还没有面试或复盘记录。先录入一次真实面试，或开始 AI 文本模拟。
                  </p>
                ) : (
                  <ul className="dashboard-list">
                    {dashboard.recentActivities.map((item) => (
                      <li key={`${item.type}-${item.targetPath}`}>
                        <div>
                          <strong>{item.title}</strong>
                          <p>
                            {item.detail} · {dateText(item.occurredAt)}
                          </p>
                        </div>
                        <Link className="text-link" href={item.targetPath}>
                          查看
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section className="library-section dashboard-section">
                <div className="section-heading">
                  <div>
                    <p className="profile-label">FOCUS</p>
                    <h2>薄弱点聚焦</h2>
                  </div>
                  <Link className="secondary-button" href="/weaknesses">
                    全部薄弱点
                  </Link>
                </div>
                {dashboard.weaknesses.length === 0 ? (
                  <p className="muted">
                    完成一次 AI 复盘后，这里会显示当前高频薄弱点。
                  </p>
                ) : (
                  <ul className="dashboard-list focus-list">
                    {dashboard.weaknesses.map((item) => (
                      <li key={item.tag}>
                        <div>
                          <strong>{item.tag}</strong>
                          <p>在历史复盘中出现 {item.count} 次</p>
                        </div>
                        <Link className="text-link" href={item.targetPath}>
                          查看建议
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>

            <section className="library-section dashboard-section sprint-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">SPRINT CHECKLIST</p>
                  <h2>冲刺清单</h2>
                  <p className="muted">
                    自动建议来自已有事实；手动项只管理自己的完成状态，不会改写来源记录。
                  </p>
                </div>
              </div>
              {dashboard.sprintItems.length === 0 ? (
                <p className="muted">
                  暂无待办。上传简历、创建面试包、录入记录或开始 AI 文本模拟后，这里会出现下一步。
                </p>
              ) : (
                <ul className="sprint-list">
                  {dashboard.sprintItems.map((item) => (
                    <li
                      className={
                        item.status === "DONE"
                          ? "sprint-item done"
                          : "sprint-item"
                      }
                      key={item.id}
                    >
                      {item.editable ? (
                        <input
                          aria-label={`切换“${item.title}”完成状态`}
                          checked={item.status === "DONE"}
                          onChange={() => void toggleItem(item)}
                          type="checkbox"
                        />
                      ) : (
                        <span className="sprint-origin" aria-hidden="true" />
                      )}
                      <div>
                        <p className="profile-label">{item.source}</p>
                        <strong>{item.title}</strong>
                        {item.description && <p>{item.description}</p>}
                      </div>
                      <div className="item-actions">
                        {item.targetPath && (
                          <Link className="text-link" href={item.targetPath}>
                            开始
                          </Link>
                        )}
                        {item.editable && (
                          <button
                            className="secondary-button"
                            type="button"
                            onClick={() => {
                              setEditing(item);
                              setDraft({
                                title: item.title,
                                description: item.description,
                                targetPath: item.targetPath,
                                priority: item.priority,
                                status: item.status,
                              });
                            }}
                          >
                            编辑
                          </button>
                        )}
                        {item.editable && (
                          <button
                            className="danger-button"
                            type="button"
                            onClick={() => setDeleting(item)}
                          >
                            删除
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              <form className="library-form sprint-form" onSubmit={saveItem}>
                <div className="section-heading">
                  <h3>{editing ? "编辑手动冲刺项" : "添加手动冲刺项"}</h3>
                  {editing && (
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() => {
                        setEditing(undefined);
                        setDraft(blankDraft);
                      }}
                    >
                      取消
                    </button>
                  )}
                </div>
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
                  说明（可选）
                  <textarea
                    value={draft.description}
                    onChange={(event) =>
                      setDraft({ ...draft, description: event.target.value })
                    }
                  />
                </label>
                <div className="form-row">
                  <label className="field">
                    跳转位置（可选）
                    <select
                      value={draft.targetPath}
                      onChange={(event) =>
                        setDraft({ ...draft, targetPath: event.target.value })
                      }
                    >
                      <option value="">不跳转</option>
                      <option value="/library">资料库</option>
                      <option value="/interviews/new">新建面试记录</option>
                      <option value="/interviews">面试记录</option>
                      <option value="/mock-interviews">AI 文本模拟</option>
                      <option value="/weaknesses">薄弱点</option>
                      <option value="/ai-conversations">AI 对话</option>
                    </select>
                  </label>
                  <label className="field">
                    优先级（0–100）
                    <input
                      min="0"
                      max="100"
                      type="number"
                      value={draft.priority}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          priority: Number(event.target.value),
                        })
                      }
                    />
                  </label>
                </div>
                <div className="form-actions">
                  <button className="primary-button" disabled={saving}>
                    {saving ? "正在保存…" : editing ? "保存冲刺项" : "加入清单"}
                  </button>
                </div>
              </form>
            </section>
          </>
        )}
      </main>
      <ConfirmDialog
        open={deleting !== undefined}
        title="删除这项冲刺项？"
        description={
          deleting ? `“${deleting.title}”会被删除，操作无法撤销。` : ""
        }
        confirmLabel="确认删除"
        confirmTone="danger"
        busy={saving}
        onConfirm={() => void deleteItem()}
        onCancel={() => setDeleting(undefined)}
      />
    </AppShell>
  );
}
