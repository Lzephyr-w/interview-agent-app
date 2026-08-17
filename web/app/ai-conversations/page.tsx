"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";
import {
  emptyForm,
  errorMessage,
  interviewTypeLabel,
  requestId,
  upsertMessage,
  type ConversationDetail,
  type ConversationSummary,
  type Interview,
  type InterviewDetail,
  type Message,
  type Package,
  type Review,
  type Weakness,
} from "@/features/ai-conversations";

type MarkdownBlock =
  | { type: "heading"; level: number; text: string }
  | { type: "paragraph" | "blockquote" | "ul" | "ol"; text: string[] }
  | { type: "code"; text: string }
  | { type: "table"; rows: string[][] };

function safeHref(href: string) {
  return /^(https?:\/\/|mailto:|\/|#)/i.test(href) ? href : undefined;
}

function inlineMarkdownLine(text: string, lineIndex: number): React.ReactNode[] {
  const parts: React.ReactNode[] = [];
  const pattern = /(\*\*|__|~~|`|\*|_)(.+?)\1|\[([^\]]+)\]\(([^)]+)\)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text))) {
    if (match.index > lastIndex) parts.push(text.slice(lastIndex, match.index));
    if (match[3] !== undefined) {
      const href = safeHref(match[4]);
      parts.push(
        href ? (
          <a key={`${lineIndex}-${match.index}`} href={href} target="_blank" rel="noreferrer">
            {match[3]}
          </a>
        ) : (
          match[3]
        ),
      );
    } else if (match[1] === "`") {
      parts.push(<code key={`${lineIndex}-${match.index}`}>{match[2]}</code>);
    } else if (match[1] === "~~") {
      parts.push(<del key={`${lineIndex}-${match.index}`}>{match[2]}</del>);
    } else if (match[1] === "*" || match[1] === "_") {
      parts.push(<em key={`${lineIndex}-${match.index}`}>{match[2]}</em>);
    } else {
      parts.push(<strong key={`${lineIndex}-${match.index}`}>{match[2]}</strong>);
    }
    lastIndex = pattern.lastIndex;
  }
  if (lastIndex < text.length) parts.push(text.slice(lastIndex));
  return parts;
}

function inlineMarkdown(text: string) {
  return text.split("\n").flatMap((line, index) => [
    ...(index ? [<br key={`break-${index}`} />] : []),
    ...inlineMarkdownLine(line, index),
  ]);
}

function isTableSeparator(line: string) {
  return /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$/.test(line);
}

function tableCells(line: string) {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((cell) => cell.trim());
}

function parseMarkdown(content: string): MarkdownBlock[] {
  const lines = content.replace(/\r\n?/g, "\n").split("\n");
  const blocks: MarkdownBlock[] = [];
  for (let index = 0; index < lines.length; ) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }
    if (/^\s*```/.test(line)) {
      const code: string[] = [];
      index += 1;
      while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) code.push(lines[index++]);
      if (index < lines.length) index += 1;
      blocks.push({ type: "code", text: code.join("\n") });
      continue;
    }
    const heading = line.match(/^\s*(#{1,6})\s+(.+?)\s*#*\s*$/);
    if (heading) {
      blocks.push({ type: "heading", level: heading[1].length, text: heading[2] });
      index += 1;
      continue;
    }
    if (/^\s*(\*{3,}|-{3,}|_{3,})\s*$/.test(line)) {
      blocks.push({ type: "paragraph", text: ["---"] });
      index += 1;
      continue;
    }
    if (index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
      const rows = [tableCells(line)];
      index += 2;
      while (index < lines.length && lines[index].includes("|")) rows.push(tableCells(lines[index++]));
      blocks.push({ type: "table", rows });
      continue;
    }
    const list = line.match(/^\s*([-*+] |\d+[.)] )(.*)$/);
    if (list) {
      const ordered = /^\d/.test(list[1]);
      const items: string[] = [];
      while (index < lines.length) {
        const item = lines[index].match(ordered ? /^\s*\d+[.)] (.*)$/ : /^\s*[-*+] (.*)$/);
        if (!item) break;
        items.push(item[1]);
        index += 1;
      }
      blocks.push({ type: ordered ? "ol" : "ul", text: items });
      continue;
    }
    if (/^\s*>/.test(line)) {
      const quote: string[] = [];
      while (index < lines.length && /^\s*>/.test(lines[index])) quote.push(lines[index++].replace(/^\s*> ?/, ""));
      blocks.push({ type: "blockquote", text: quote });
      continue;
    }
    const paragraph: string[] = [line];
    index += 1;
    while (index < lines.length && lines[index].trim() && !/^\s*(#{1,6})\s+/.test(lines[index]) && !/^\s*```/.test(lines[index]) && !/^\s*(?:[-*+] |\d+[.)] |>|\*{3,}|-{3,}|_{3,})/.test(lines[index])) paragraph.push(lines[index++]);
    blocks.push({ type: "paragraph", text: paragraph });
  }
  return blocks;
}

function MarkdownText({ content }: { content: string }) {
  const headingTags = ["h1", "h2", "h3", "h4", "h5", "h6"] as const;
  return (
    <div className="chat-rich-text">
      {parseMarkdown(content).map((block, index) => {
        if (block.type === "heading") {
          const Heading = headingTags[block.level - 1];
          return <Heading key={index}>{inlineMarkdown(block.text)}</Heading>;
        }
        if (block.type === "code") return <pre key={index}><code>{block.text}</code></pre>;
        if (block.type === "table") return (
          <div className="chat-table-wrap" key={index}>
            <table>
              <thead><tr>{block.rows[0]?.map((cell, cellIndex) => <th key={cellIndex}>{inlineMarkdown(cell)}</th>)}</tr></thead>
              <tbody>{block.rows.slice(1).map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{inlineMarkdown(cell)}</td>)}</tr>)}</tbody>
            </table>
          </div>
        );
        if (block.type === "ul" || block.type === "ol") {
          const List = block.type;
          return <List key={index}>{block.text.map((item, itemIndex) => <li key={itemIndex}>{inlineMarkdown(item)}</li>)}</List>;
        }
        if (block.type === "blockquote") return <blockquote key={index}>{inlineMarkdown(block.text.join("\n"))}</blockquote>;
        return <p key={index}>{inlineMarkdown(block.text.join("\n"))}</p>;
      })}
    </div>
  );
}

function TypewriterText({ content, onUpdate }: { content: string; onUpdate: () => void }) {
  const [length, setLength] = useState(0);

  useEffect(() => {
    setLength(0);
    const timer = window.setInterval(() => {
      setLength((current) => {
        const next = Math.min(current + 2, content.length);
        if (next === content.length) window.clearInterval(timer);
        return next;
      });
    }, 16);
    return () => window.clearInterval(timer);
  }, [content]);

  useEffect(() => {
    onUpdate();
  }, [length, onUpdate]);

  return <div className="chat-rich-text"><MarkdownText content={content.slice(0, length)} />{length < content.length && <span className="typing-cursor" aria-hidden="true" />}
  </div>;
}

export default function AiConversationsPage() {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [detail, setDetail] = useState<ConversationDetail>();
  const [packages, setPackages] = useState<Package[]>([]);
  const [interviews, setInterviews] = useState<Interview[]>([]);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [weaknesses, setWeaknesses] = useState<Weakness[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [draft, setDraft] = useState("");
  const [draftRequestId, setDraftRequestId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [sending, setSending] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [selectedConversationId, setSelectedConversationId] = useState<string>();
  const [showNew, setShowNew] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<ConversationSummary>();
  const [typingMessageId, setTypingMessageId] = useState<string>();
  const contentScrollRef = useRef<HTMLDivElement>(null);
  const shouldAutoScrollRef = useRef(true);
  const conversationRequestRef = useRef(0);

  const scrollToBottom = useCallback(() => {
    const content = contentScrollRef.current;
    if (shouldAutoScrollRef.current && content)
      content.scrollTop = content.scrollHeight;
  }, []);

  function updateAutoScroll() {
    const content = contentScrollRef.current;
    if (content)
      shouldAutoScrollRef.current =
        content.scrollHeight - content.scrollTop - content.clientHeight < 24;
  }

  useEffect(() => {
    scrollToBottom();
  }, [detail?.conversation.id, detail?.messages, scrollToBottom]);

  useEffect(() => {
    async function load() {
      setLoading(true);
      try {
        const [
          loadedConversations,
          loadedPackages,
          loadedInterviews,
          loadedWeaknesses,
        ] = await Promise.all([
          api<ConversationSummary[]>("/api/v1/ai-conversations"),
          api<Package[]>("/api/v1/interview-packages"),
          api<Interview[]>("/api/v1/interviews"),
          api<Weakness[]>("/api/v1/weaknesses"),
        ]);
        setConversations(loadedConversations);
        setPackages(loadedPackages);
        setInterviews(loadedInterviews);
        setWeaknesses(loadedWeaknesses);
        if (loadedConversations[0])
          await selectConversation(loadedConversations[0].id);
      } catch (cause) {
        setError(errorMessage(cause, "AI 对话加载失败，请稍后重试。"));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, []);

  async function selectConversation(id: string) {
    const request = ++conversationRequestRef.current;
    shouldAutoScrollRef.current = true;
    setSelectedConversationId(id);
    setDetail(undefined);
    setConversationLoading(true);
    setError("");
    setTypingMessageId(undefined);
    try {
      const loaded = await api<ConversationDetail>(
        `/api/v1/ai-conversations/${id}`,
      );
      if (request === conversationRequestRef.current) setDetail(loaded);
    } catch (cause) {
      if (request === conversationRequestRef.current)
        setError(errorMessage(cause, "对话加载失败，请稍后重试。"));
    } finally {
      if (request === conversationRequestRef.current)
        setConversationLoading(false);
    }
  }

  async function selectInterview(interviewId: string) {
    setForm({ ...form, interviewId, reviewReportId: "" });
    setReviews([]);
    if (!interviewId) return;
    try {
      const loaded = await api<InterviewDetail>(
        `/api/v1/interviews/${interviewId}`,
      );
      setReviews(loaded.reviews);
    } catch (cause) {
      setError(errorMessage(cause, "复盘选项加载失败，请稍后重试。"));
    }
  }

  async function createConversation(event: FormEvent) {
    event.preventDefault();
    setCreating(true);
    setError("");
    try {
      const created = await api<ConversationDetail>(
        "/api/v1/ai-conversations",
        {
          method: "POST",
          body: JSON.stringify({
            interviewPackageId: form.interviewPackageId || null,
            interviewId: form.interviewId || null,
            reviewReportId: form.reviewReportId || null,
            weaknessTag: form.weaknessTag || null,
            title: form.title || null,
          }),
        },
      );
      const summary: ConversationSummary = {
        id: created.conversation.id,
        title: created.conversation.title,
        createdAt: created.conversation.createdAt,
        updatedAt: created.conversation.updatedAt,
      };
      setConversations((items) => [summary, ...items]);
      setSelectedConversationId(created.conversation.id);
      setDetail(created);
      setForm(emptyForm);
      setReviews([]);
      setShowNew(false);
      setNotice("Agent 对话已创建；它会按需查询你的资料并调用受控业务工具。");
    } catch (cause) {
      setError(errorMessage(cause, "新建对话失败，请稍后重试。"));
    } finally {
      setCreating(false);
    }
  }

  async function send(event: FormEvent) {
    event.preventDefault();
    if (!detail || !draft.trim()) return;
    shouldAutoScrollRef.current = true;
    setSending(true);
    setError("");
    const clientRequestId = draftRequestId ?? requestId();
    if (!draftRequestId) setDraftRequestId(clientRequestId);
    let savedDetail: ConversationDetail | undefined;
    let savedUserMessage: Message | undefined;
    try {
      const saved = await api<ConversationDetail>(
        `/api/v1/ai-conversations/${detail.conversation.id}/messages`,
        {
          method: "POST",
          body: JSON.stringify({ content: draft, clientRequestId }),
        },
      );
      const userMessage = [...saved.messages]
        .reverse()
        .find((message) => message.role === "USER");
      savedDetail = saved;
      savedUserMessage = userMessage;
      if (!userMessage)
        throw new Error("问题保存后未找到对应消息，请刷新后重试。");
      setDraft("");
      setDraftRequestId(undefined);
      setDetail({
        ...saved,
        messages: [
          ...saved.messages,
          {
            id: `pending-${userMessage.id}`,
            role: "ASSISTANT",
            content: "",
            status: "PENDING",
            errorMessage: null,
            clientRequestId: null,
            replyToMessageId: userMessage.id,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        ],
      });
      const replied = await api<Message>(
        `/api/v1/ai-conversations/${detail.conversation.id}/messages/${userMessage.id}/reply`,
        { method: "POST" },
      );
      setTypingMessageId(replied.status === "COMPLETED" ? replied.id : undefined);
      setDetail((current) =>
        current
          ? { ...current, messages: upsertMessage(current.messages, replied) }
          : current,
      );
      setConversations((items) =>
        items.map((item) =>
          item.id === detail.conversation.id
            ? { ...item, updatedAt: replied.updatedAt }
            : item,
        ),
      );
    } catch (cause) {
      if (savedDetail && savedUserMessage) {
        setDetail({
          ...savedDetail,
          messages: [
            ...savedDetail.messages,
            {
              id: `failed-${savedUserMessage.id}`,
              role: "ASSISTANT",
              content: "",
              status: "FAILED",
              errorMessage: "AI 回复请求失败，请重试。",
              clientRequestId: null,
              replyToMessageId: savedUserMessage.id,
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            },
          ],
        });
      }
      setError(
        errorMessage(
          cause,
          "问题可能已保存；若未显示，请刷新后重试，输入内容仍保留。",
        ),
      );
    } finally {
      setSending(false);
    }
  }

  async function requestReply(messageId: string) {
    if (!detail) return;
    shouldAutoScrollRef.current = true;
    setSending(true);
    setError("");
    setDetail((current) =>
      current
        ? {
            ...current,
            messages: current.messages.map((message) =>
              message.replyToMessageId === messageId
                ? {
                    ...message,
                    status: "PENDING",
                    errorMessage: null,
                  }
                : message,
            ),
          }
        : current,
    );
    try {
      const replied = await api<Message>(
        `/api/v1/ai-conversations/${detail.conversation.id}/messages/${messageId}/reply`,
        { method: "POST" },
      );
      setTypingMessageId(replied.status === "COMPLETED" ? replied.id : undefined);
      setDetail((current) =>
        current
          ? { ...current, messages: upsertMessage(current.messages, replied) }
          : current,
      );
      setConversations((items) =>
        items.map((item) =>
          item.id === detail.conversation.id
            ? { ...item, updatedAt: replied.updatedAt }
            : item,
        ),
      );
      setNotice("已重新请求 AI 回复。");
    } catch (cause) {
      setDetail((current) =>
        current
          ? {
              ...current,
              messages: current.messages.map((message) =>
                message.replyToMessageId === messageId
                  ? {
                      ...message,
                      status: "FAILED",
                      errorMessage: "AI 回复请求失败，请重试。",
                    }
                  : message,
              ),
            }
          : current,
      );
      setError(errorMessage(cause, "重试失败，请稍后再试。"));
    } finally {
      setSending(false);
    }
  }

  function retry(message: Message) {
    void requestReply(message.replyToMessageId ?? message.id);
  }

  async function deleteConversation() {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setCreating(true);
    try {
      await api<void>(`/api/v1/ai-conversations/${target.id}`, {
        method: "DELETE",
      });
      const remaining = conversations.filter((item) => item.id !== target.id);
      setConversations(remaining);
      setDeleteTarget(undefined);
      if (detail?.conversation.id === target.id) {
        setDetail(undefined);
        if (remaining[0]) await selectConversation(remaining[0].id);
        else setSelectedConversationId(undefined);
      }
      setNotice("对话已删除；面试、复盘和训练任务未受影响。");
    } catch (cause) {
      setError(errorMessage(cause, "删除失败，请稍后重试。"));
    } finally {
      setCreating(false);
    }
  }

  return (
    <AppShell>
      <main className="app-page chat-page">
        <Toast
          error={error}
          notice={notice}
          onDismissError={() => setError("")}
          onDismissNotice={() => setNotice("")}
        />
     {/*    <section className="hero-card page-hero chat-hero">
          <p className="eyebrow">AI CONVERSATION</p>
          <h1>
            让线索连接成，<em>更完整的准备。</em>
          </h1>
          <p className="intro">围绕你的资料、面试与复盘，自由追问并继续训练。</p>
        </section> */}
        {loading ? (
          <section className="library-section chat-loading">
            <p className="muted">正在加载对话…</p>
          </section>
        ) : (
          <section
            className={`chat-layout${sidebarCollapsed ? " sidebar-collapsed" : ""}`}
          >
            <aside className="chat-sidebar-panel" aria-label="AI 对话列表">
              <div className="chat-sidebar-scroll">
                {sidebarCollapsed ? (
                  <button
                    className="chat-sidebar-toggle"
                    type="button"
                    aria-label="展开历史对话"
                    onClick={() => setSidebarCollapsed(false)}
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="m9 5 7 7-7 7" />
                    </svg>
                  </button>
                ) : (
                  <>
                    <div className="section-heading">
                      <div>
                        <p className="profile-label">历史对话</p>
                      </div>
                      <div className="chat-sidebar-actions">
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => setShowNew(true)}
                        >
                          新建
                        </button>
                        <button
                          className="chat-sidebar-toggle"
                          type="button"
                          aria-label="折叠历史对话"
                          onClick={() => {
                            setShowNew(false);
                            setSidebarCollapsed(true);
                          }}
                        >
                          ‹
                        </button>
                      </div>
                    </div>
                    {showNew && (
                      <div
                        className="chat-create-backdrop"
                        onMouseDown={(event) => {
                          if (event.target === event.currentTarget && !creating)
                            setShowNew(false);
                        }}
                      >
                        <form
                          className="library-form chat-new-form chat-create-dialog"
                          role="dialog"
                          aria-modal="true"
                          aria-labelledby="chat-create-title"
                          onSubmit={createConversation}
                        >
                          <h2 id="chat-create-title">新建 AI Agent 对话</h2>
                          <p className="muted">
                            面试包只是启动线索；不选择时 Agent 会按需查询全部个人资料。
                          </p>
                          <label className="field">
                            面试包
                            <select
                              value={form.interviewPackageId}
                              onChange={(event) =>
                                setForm({
                                  ...form,
                                  interviewPackageId: event.target.value,
                                })
                              }
                            >
                              <option value="">不指定，由 Agent 自主查询</option>
                              {packages.map((item) => (
                                <option key={item.id} value={item.id}>
                                  {item.company} · {item.role} ·{" "}
                                  {item.interviewRound}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label className="field">
                            关联面试（可选）
                            <select
                              value={form.interviewId}
                              onChange={(event) =>
                                void selectInterview(event.target.value)
                              }
                            >
                              <option value="">不关联</option>
                              {interviews.map((item) => (
                                <option key={item.id} value={item.id}>
                                  【{interviewTypeLabel[item.simulationType]}】
                                  {new Date(item.interviewTime).toLocaleString()}
                                  {" · "}{item.company} · {item.role} ·{" "}
                                  {item.interviewRound}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label className="field">
                            关联复盘（可选）
                            <select
                              disabled={!form.interviewId}
                              value={form.reviewReportId}
                              onChange={(event) =>
                                setForm({
                                  ...form,
                                  reviewReportId: event.target.value,
                                })
                              }
                            >
                              <option value="">不关联</option>
                              {reviews.map((item) => (
                                <option key={item.id} value={item.id}>
                                  复盘{" "}
                                  {new Date(item.createdAt).toLocaleDateString(
                                    "zh-CN",
                                  )}
                                </option>
                              ))}
                            </select>
                            {!form.interviewId && (
                              <small className="muted">请先选择关联面试</small>
                            )}
                          </label>
                          <label className="field">
                            薄弱点标签（可选）
                            <select
                              value={form.weaknessTag}
                              onChange={(event) =>
                                setForm({
                                  ...form,
                                  weaknessTag: event.target.value,
                                })
                              }
                            >
                              <option value="">不关联</option>
                              {weaknesses.map((item) => (
                                <option key={item.tag} value={item.tag}>
                                  {item.tag}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label className="field">
                            标题（可选）
                            <input
                              value={form.title}
                              maxLength={80}
                              onChange={(event) =>
                                setForm({ ...form, title: event.target.value })
                              }
                            />
                          </label>
                          <div className="form-actions chat-create-actions">
                            <button
                              className="secondary-button"
                              type="button"
                              disabled={creating}
                              onClick={() => setShowNew(false)}
                            >
                              取消
                            </button>
                            <button
                              className="primary-button"
                              disabled={creating}
                            >
                              {creating ? "正在创建…" : "创建对话"}
                            </button>
                          </div>
                        </form>
                      </div>
                    )}
                    <div className="chat-conversation-list">
                      {conversations.length === 0 ? (
                        <p className="muted">
                          暂无历史对话。选择面试包后创建第一场对话。
                        </p>
                      ) : (
                        conversations.map((item) => (
                          <div
                            key={item.id}
                            className={
                              selectedConversationId === item.id
                                ? "chat-conversation active"
                                : "chat-conversation"
                            }
                          >
                            <button
                              className="chat-conversation-select"
                              type="button"
                              onClick={() => void selectConversation(item.id)}
                            >
                              <strong>{item.title}</strong>
                              <small>
                                {new Date(item.updatedAt).toLocaleString(
                                  "zh-CN",
                                )}
                              </small>
                            </button>
                            <button
                              className="chat-delete-button"
                              type="button"
                              aria-label={`删除对话：${item.title}`}
                              title="删除对话"
                              onClick={() => setDeleteTarget(item)}
                            >
                              <svg viewBox="0 0 24 24" aria-hidden="true">
                                <path d="M4 7h16M10 11v6m4-6v6M9 7l1-2h4l1 2m-9 0 1 13h10l1-13" />
                              </svg>
                            </button>
                          </div>
                        ))
                      )}
                    </div>
                  </>
                )}
              </div>
            </aside>
            <section className="chat-main" aria-label="当前 AI 对话">
              {conversationLoading ? (
                <div className="library-section">
                  <p className="muted">正在加载对话…</p>
                </div>
              ) : !detail ? (
                <div className="library-section">
                  <h2>开始一场 Agent 对话</h2>
                  <p className="muted">
                    Agent 可以自主查找资料、连续调用业务工具并创建训练任务。
                  </p>
                </div>
              ) : (
                <>
                  <div className="chat-content-scroll" ref={contentScrollRef} onScroll={updateAutoScroll}>
                    <div className="chat-heading">
                      <div>
                        <p className="profile-label">当前会话</p>
                        <h2>{detail.conversation.title}</h2>
                      </div>
                    </div>
                    <section
                      className="chat-context"
                      aria-labelledby="chat-context-title"
                    >
                      <h3 id="chat-context-title">Agent 启动线索与权限</h3>
                      <p className="muted">
                        下列资料会直接提供给 Agent；它也可按需查询当前账户的其他资料。
                      </p>
                      <ul>
                        {detail.conversation.contextSources.map(
                          (source, index) => (
                            <li key={`${source.type}-${source.label}-${index}`}>
                              <strong>{source.type}</strong>
                              <span>{source.label}</span>
                              <small>{source.state}</small>
                            </li>
                          ),
                        )}
                      </ul>
                    </section>
                    <div
                      className="chat-messages"
                      role="log"
                      aria-live="polite"
                      aria-label="对话消息"
                    >
                      {detail.messages.length === 0 ? (
                        <p className="muted">
                          输入目标开始对话。Agent 会自主查询资料、调用工具并完成多步任务。
                        </p>
                      ) : (
                        detail.messages.map((message) => (
                          <div
                            className={`chat-turn ${message.role === "USER" ? "user" : "assistant"}`}
                            key={message.id}
                          >
                            <article className="chat-message">
                              {message.status === "PENDING" ? (
                                <p className="muted">正在生成回复…</p>
                              ) : message.status === "FAILED" ? (
                                <div>
                                  <p className="error">
                                    {message.errorMessage ??
                                      "AI 回复失败，请重试。"}
                                  </p>
                                  <button
                                    className="secondary-button"
                                    type="button"
                                    disabled={sending}
                                    onClick={() => retry(message)}
                                  >
                                    重试回复
                                  </button>
                                </div>
                              ) : message.id === typingMessageId ? (
                                <TypewriterText content={message.content} onUpdate={scrollToBottom} />
                              ) : (
                                <MarkdownText content={message.content} />
                              )}
                            </article>
                            <div className="chat-message-person">
                              <span className="chat-avatar" aria-hidden="true">
                                {message.role === "USER" ? "我" : "AI"}
                              </span>
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                  <form className="chat-composer" onSubmit={send}>
                    <label
                      className="field"
                      htmlFor="chat-draft"
                      aria-label="提问"
                    >
                      <textarea
                        id="chat-draft"
                        value={draft}
                        onChange={(event) => {
                          setDraft(event.target.value);
                          setDraftRequestId(undefined);
                        }}
                        onKeyDown={(event) => {
                          if (
                            (event.ctrlKey || event.metaKey) &&
                            event.key === "Enter"
                          ) {
                            event.preventDefault();
                            event.currentTarget.form?.requestSubmit();
                          }
                        }}
                        placeholder="例如：查看我的复盘，找出最重要的弱项并创建一个训练任务。"
                      />
                    </label>
                    <div className="form-actions chat-composer-actions">
                      <span className="muted">Ctrl/Cmd + Enter 发送</span>
                      <button
                        className="primary-button"
                        disabled={sending || !draft.trim()}
                      >
                        {sending ? "正在处理…" : "发送"}
                      </button>
                    </div>
                  </form>
                </>
              )}
            </section>
          </section>
        )}
      </main>
      <ConfirmDialog
        open={deleteTarget !== undefined}
        title="删除这场 AI 对话？"
        description={`“${deleteTarget?.title ?? ""}”及其消息会被删除；面试、模拟、复盘和训练任务不会受影响。`}
        confirmLabel="确认删除"
        confirmTone="danger"
        busy={creating}
        onConfirm={() => void deleteConversation()}
        onCancel={() => setDeleteTarget(undefined)}
      />
    </AppShell>
  );
}
