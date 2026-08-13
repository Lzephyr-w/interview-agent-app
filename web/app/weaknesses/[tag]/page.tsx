"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import AppShell from "@/components/AppShell";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Evidence = {
  questionId: string;
  questionText: string;
  improvementAction: string;
  missingEvidence: string;
  recommendedAnswerStructure: string;
};
type Source = {
  interviewId: string;
  reviewReportId: string;
  company: string;
  role: string;
  interviewRound: string;
  interviewType: "REAL" | "MOCK";
  reviewedAt: string;
  evidence: Evidence[];
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

const emptyDraft = {
  title: "",
  action: "",
  status: "NOT_STARTED",
  sourceInterviewId: "",
  sourceReviewReportId: "",
};

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export default function WeaknessDetailPage() {
  const params = useParams<{ tag: string }>();
  const tag = decodeURIComponent(params.tag ?? "");
  const [item, setItem] = useState<Weakness>();
  const [draft, setDraft] = useState(emptyDraft);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");

  useEffect(() => {
    void (async () => {
      setLoading(true);
      setError("");
      try {
        setItem(
          await api<Weakness>(`/api/v1/weaknesses/${encodeURIComponent(tag)}`),
        );
      } catch (cause) {
        setError(messageOf(cause, "薄弱点详情加载失败。"));
      } finally {
        setLoading(false);
      }
    })();
  }, [tag]);

  function beginCreate(source?: Source, evidence?: Evidence) {
    if (!item) return;
    const action = evidence
      ? [
          `改进动作：${evidence.improvementAction}`,
          `缺失证据：${evidence.missingEvidence}`,
          `推荐结构：${evidence.recommendedAnswerStructure}`,
        ].join("\n")
      : [
          item.suggestion.action,
          `缺失证据：${item.suggestion.missingEvidence}`,
          `推荐结构：${item.suggestion.recommendedStructure}`,
        ].join("\n");
    setDraft({
      title: `练习：${item.suggestion.title}`,
      action,
      status: "NOT_STARTED",
      sourceInterviewId: source?.interviewId ?? "",
      sourceReviewReportId: source?.reviewReportId ?? "",
    });
    setMessage("");
    document
      .getElementById("detail-task-editor")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function saveTask(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      await api("/api/v1/training-tasks", {
        method: "POST",
        body: JSON.stringify({
          title: draft.title,
          weaknessTag: item?.tag,
          action: draft.action,
          status: draft.status,
          sourceInterviewId: draft.sourceInterviewId || null,
          sourceReviewReportId: draft.sourceReviewReportId || null,
        }),
      });
      setDraft(emptyDraft);
      setMessage("训练任务已创建，可在训练任务区继续更新状态或删除。");
    } catch (cause) {
      setError(messageOf(cause, "训练任务创建失败。"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <AppShell>
      <main className="app-page">
        {loading && (
          <section className="library-section">
            <p className="muted">正在加载薄弱点详情…</p>
          </section>
        )}
        <Toast
          error={error}
          notice={message}
          onDismissError={() => setError("")}
          onDismissNotice={() => setMessage("")}
        />
        {item && (
          <>
            <section className="hero-card">
              <p className="eyebrow">WEAKNESS DETAIL</p>
              <h1>
                薄弱点：<em>{item.tag}</em>
              </h1>
              <p className="intro">
                当前有 {item.count}{" "}
                份复盘记录带有此标签。这个数字是复盘证据数量，不是趋势或通过概率。
              </p>
            </section>
            <section className="library-section weakness-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">TRAINING SUGGESTION</p>
                  <h2>{item.suggestion.title}</h2>
                  <p>{item.suggestion.action}</p>
                </div>
                <button
                  className="primary-button"
                  type="button"
                  onClick={() => beginCreate()}
                >
                  用此建议创建任务
                </button>
              </div>
              <dl className="suggestion-list detail-suggestion">
                <div>
                  <dt>为什么建议</dt>
                  <dd>{item.suggestion.reason}</dd>
                </div>
                <div>
                  <dt>缺失证据</dt>
                  <dd>{item.suggestion.missingEvidence}</dd>
                </div>
                <div>
                  <dt>推荐回答结构</dt>
                  <dd>{item.suggestion.recommendedStructure}</dd>
                </div>
              </dl>
            </section>
            <section className="library-section weakness-section">
              <div className="section-heading">
                <div>
                  <p className="profile-label">TRACEABLE SOURCES</p>
                  <h2>来源复盘与问题</h2>
                  <p className="muted">每个来源都可以回到原面试和复盘页面。</p>
                </div>
              </div>
              <ul className="source-list detail-source-list">
                {item.sources.map((source) => (
                  <li key={source.reviewReportId}>
                    <div>
                      <strong>
                        {source.company} · {source.role} ·{" "}
                        {source.interviewRound}
                      </strong>
                      <span
                        className={`interview-type-label ${source.interviewType.toLowerCase()}`}
                      >
                        {source.interviewType === "MOCK"
                          ? "AI 模拟"
                          : "真实面试"}
                      </span>
                      <p>{new Date(source.reviewedAt).toLocaleString()}</p>
                      <div className="item-actions">
                        <Link
                          className="text-link"
                          href={`/interviews/${source.interviewId}`}
                        >
                          查看面试
                        </Link>
                        <Link
                          className="text-link"
                          href={`/interviews/${source.interviewId}/review`}
                        >
                          查看复盘
                        </Link>
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => beginCreate(source)}
                        >
                          用此复盘建议
                        </button>
                      </div>
                      <div className="evidence-list">
                        {source.evidence.length === 0 ? (
                          <p className="muted">
                            暂无逐题复盘资料，建议待补充。
                          </p>
                        ) : (
                          source.evidence.map((evidence) => (
                            <details
                              className="evidence-card"
                              key={evidence.questionId}
                            >
                              <summary>
                                {evidence.questionText || "问题文本待补充"}
                              </summary>
                              <p>
                                <b>改进动作：</b>
                                {evidence.improvementAction}
                              </p>
                              <p>
                                <b>缺失证据：</b>
                                {evidence.missingEvidence}
                              </p>
                              <p>
                                <b>推荐回答结构：</b>
                                {evidence.recommendedAnswerStructure}
                              </p>
                              <button
                                className="secondary-button"
                                type="button"
                                onClick={() => beginCreate(source, evidence)}
                              >
                                用此问题建议创建任务
                              </button>
                            </details>
                          ))
                        )}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            </section>
            <section
              className="library-section weakness-section"
              id="detail-task-editor"
            >
              <div className="section-heading">
                <div>
                  <p className="profile-label">CREATE TASK</p>
                  <h2>保存训练任务快照</h2>
                  <p className="muted">
                    任务保存标题、固定标签和练习内容；来源删除后任务仍保留。
                  </p>
                </div>
                <Link className="secondary-button" href="/weaknesses">
                  返回薄弱点
                </Link>
              </div>
              {draft.title ? (
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
                    弱项标签
                    <input readOnly value={item.tag} />
                  </label>
                  <label className="field">
                    练习内容
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
                        setDraft({ ...draft, status: event.target.value })
                      }
                    >
                      <option value="NOT_STARTED">待开始</option>
                      <option value="IN_PROGRESS">进行中</option>
                      <option value="COMPLETED">已完成</option>
                    </select>
                  </label>
                  <p className="muted">
                    {draft.sourceReviewReportId
                      ? "已关联来源复盘。"
                      : "未关联具体来源复盘。"}
                  </p>
                  <button className="primary-button" disabled={saving}>
                    {saving ? "正在保存…" : "创建训练任务"}
                  </button>
                </form>
              ) : (
                <p className="muted">
                  点击上方建议或具体问题的按钮，开始创建训练任务。
                </p>
              )}
            </section>
          </>
        )}
      </main>
    </AppShell>
  );
}
