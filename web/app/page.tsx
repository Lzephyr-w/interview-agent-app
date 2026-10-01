"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import WavingInterviewer from "@/components/WavingInterviewer";
import { api } from "@/lib/api";

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
type DashboardFocus = Pick<Dashboard, "weaknesses" | "sprintItems">;
type DashboardDetails = Pick<Dashboard, "recentActivities" | "sprintItems">;
type Activity = {
  id: string;
  type: string;
  title: string;
  detail: string;
  targetPath: string;
  occurredAt: string;
};
type Weakness = { tag: string; title: string; targetPath: string };
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
  const [dashboard, setDashboard] = useState<Dashboard>();
  const [draft, setDraft] = useState<SprintDraft>(blankDraft);
  const [editing, setEditing] = useState<SprintItem>();
  const [deleting, setDeleting] = useState<SprintItem>();
  const [loading, setLoading] = useState(true);
  const [focusLoading, setFocusLoading] = useState(true);
  const [detailsLoading, setDetailsLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      const currentDashboard = await api<Dashboard>("/api/v1/dashboard");
      setDashboard(currentDashboard);
      void loadDetails();
      void loadFocus();
    } catch (cause) {
      setError(messageOf(cause, "首页加载失败。"));
      if (cause instanceof Error && cause.message.includes("登录")) {
        router.replace("/login");
      }
    } finally {
      setLoading(false);
    }
  }

  async function loadDetails() {
    try {
      const details = await api<DashboardDetails>("/api/v1/dashboard/details");
      setDashboard((current) => current ? { ...current, ...details, sprintItems: [...details.sprintItems, ...current.sprintItems.filter((item) => item.kind === "WEAKNESS")] } : current);
    } catch (cause) {
      setError(messageOf(cause, "近期动态和冲刺清单加载失败。"));
    } finally {
      setDetailsLoading(false);
    }
  }

  async function loadFocus() {
    try {
      const focus = await api<DashboardFocus>("/api/v1/dashboard/focus");
      setDashboard((current) => current ? { ...current, ...focus, sprintItems: [...current.sprintItems.filter((item) => item.kind !== "WEAKNESS"), ...focus.sprintItems] } : current);
    } catch (cause) {
      setError(messageOf(cause, "薄弱点聚焦加载失败。"));
    } finally {
      setFocusLoading(false);
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
            <header className="home-welcome">
              <div className="home-welcome-copy">
                <span className="home-hero-label">ZHIMIAN / AI INTERVIEW LAB</span>
                <h1>让实力，被更好地看见<span className="home-heading-dot">。</span></h1>
                <p>从模拟作答到面试复盘，把每一次练习转化为下一次进步。</p>
                <div className="home-hero-tags"><span>语音实战</span><span>文本推演</span><span>复盘提升</span></div>
              </div>
              <div className="home-hero-orbit" aria-hidden="true"><span /><span /><b>AI</b><i /></div>
              <Link className="home-record-link" href="/interviews/new">记录一场真实面试 <span aria-hidden="true">↗</span></Link>
            </header>

            <section className="home-practice" aria-label="开始面试训练">
              <div className="home-voice">
                <div className="home-training-copy">
                  <span className="home-card-label"><span /> VOICE / 语音实战</span>
                  <h2>开口作答，<br />进入面试现场。</h2>
                  <p>面对 AI 面试官的提问，<br />练习表达、节奏与临场反应。</p>
                  <Link className="home-cta" href="/ai-mock-interviews">进入语音训练 <span aria-hidden="true">↗</span></Link>
                </div>
                <div className="home-training-art" aria-hidden="true">
                  <span className="home-art-stage" />
                  <WavingInterviewer />
                  <span className="home-art-caption">YOUR AI INTERVIEW PARTNER</span>
                </div>
                <div className="home-voice-flow"><span><b>01</b>模拟提问</span><span><b>02</b>语音作答</span><span><b>03</b>面试复盘</span></div>
              </div>
              <div className="home-practice-side">
                <Link className="home-text-practice" href="/mock-interviews">
                  <div className="home-small-card-top"><span className="home-card-label">TEXT / 文本推演</span><span className="home-card-arrow" aria-hidden="true">↗</span></div>
                  <h2>拆解问题，构建高质量回答。</h2>
                  <p>不限时梳理思路，把项目经历讲得更有逻辑。</p>
                  <div className="home-text-example">
                    <span>训练示例 / PROJECT EXPERIENCE</span>
                    <strong>你如何解决项目中最有挑战的技术问题？</strong>
                    <div><span>01 问题背景</span><i aria-hidden="true">→</i><span>02 方案取舍</span><i aria-hidden="true">→</i><span>03 实际结果</span></div>
                  </div>
                  <span className="home-text-cta">进入文本训练</span>
                </Link>
                <Link className="home-materials" href="/library">
                  <span className="home-material-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M3 7a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v9H3Z" /><path d="M3 10h18" /></svg></span>
                  <div><strong>建立你的面试知识库</strong><p>整理简历与资料，为目标岗位创建面试包</p></div>
                  <span aria-hidden="true">↗</span>
                </Link>
              </div>
            </section>

            <section className="dashboard-section">
              <div className="dashboard-stats">
                {[
                  ["面试包", overview.interviewPackageCount, "/library", "M4 7h6l2 3h8v10H4ZM4 7V4h6l2 3"],
                  ["简历文件", overview.resumeFileCount, "/library", "M6 3h8l4 4v14H6ZM14 3v5h4M9 12h6M9 16h4"],
                  [
                    "待复盘真实面试",
                    overview.pendingReviewCount,
                    "/interviews",
                    "M12 8v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0",
                  ],
                  [
                    "待完成训练任务",
                    overview.pendingTrainingTaskCount,
                    "/weaknesses",
                    "m4 7 2 2 4-4M13 7h7m-16 9 2 2 4-4m3 2h7",
                  ],
                ].map(([label, value, href, icon]) => (
                  <Link
                    className="dashboard-stat"
                    href={href as string}
                    key={label as string}
                  >
                    <span className="home-stat-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d={icon as string} /></svg></span>
                    <div><span>{label}</span><strong>{value}<small>项</small></strong></div>
                    <span className="home-stat-arrow" aria-hidden="true">↗</span>
                  </Link>
                ))}
              </div>
            </section>

            <div className="dashboard-columns">
              <section className="library-section dashboard-section">
                <div className="section-heading">
                  <div>
                    <p className="profile-label">ACTIVITY / 持续积累</p>
                    <h2>训练轨迹</h2>
                  </div>
                  <Link className="home-section-link" href="/interviews">全部记录 →</Link>
                </div>
                {detailsLoading ? (
                  <p className="muted">正在加载近期动态…</p>
                ) : dashboard.recentActivities.length === 0 ? (
                  <div className="home-empty"><span aria-hidden="true">↗</span><strong>你的第一步，从这里开始</strong><p>完成一次练习，或记录一次真实面试，<br />在这里看见自己的成长。</p><Link className="home-section-link" href="/ai-mock-interviews">开始第一次练习 →</Link></div>
                ) : (
                  <ul className="dashboard-list">
                    {dashboard.recentActivities.map((item) => (
                      <li key={item.id}>
                        <div>
                          <strong>{item.title}</strong>
                          <p>{item.detail}</p>
                          <time dateTime={item.occurredAt}>{dateText(item.occurredAt)}</time>
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
                    <p className="profile-label">FOCUS / 定向提升</p>
                    <h2>能力补强</h2>
                  </div>
                  <Link className="home-section-link" href="/weaknesses">
                    查看全部 →
                  </Link>
                </div>
                {focusLoading ? (
                  <p className="muted">正在整理薄弱点聚焦…</p>
                ) : dashboard.weaknesses.length === 0 ? (
                  <div className="home-empty"><span aria-hidden="true">◎</span><strong>找到下一次突破的方向</strong><p>完成一次 AI 弱项分析，<br />获得具体的建议与训练方向。</p><Link className="home-section-link" href="/weaknesses">查看分析与建议 →</Link></div>
                ) : (
                  <ul className="dashboard-list focus-list">
                    {dashboard.weaknesses.map((item) => (
                      <li key={item.tag}>
                        <div>
                          <strong>{item.tag}</strong>
                          <p>{item.title}</p>
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
                  <p className="profile-label">NEXT / 下一阶段</p>
                  <h2>训练计划</h2>
                  <p className="muted">
                    跟进待办与训练建议，也可以添加自己的准备计划。
                  </p>
                </div>
              </div>
              {detailsLoading ? (
                <p className="muted">正在加载冲刺清单…</p>
              ) : dashboard.sprintItems.length === 0 ? (
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
              <details className="home-plan-composer" open={editing ? true : undefined} key={editing?.id ?? "new"}>
              <summary>{editing ? "编辑准备计划" : "+ 添加一项准备计划"}</summary>
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
              </details>
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
