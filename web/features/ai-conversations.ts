export type Package = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
};
export type Interview = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  interviewTime: string;
  interviewPackageId: string | null;
  simulationType: "REAL" | "AI_TEXT" | "AI_VOICE";
};
export type Review = { id: string; createdAt: string };
export type InterviewDetail = { reviews: Review[] };
export type Weakness = { tag: string; title: string };
export type ContextSource = { type: string; label: string; state: string };
export type ConversationSummary = { id: string; title: string; createdAt: string; updatedAt: string };
export type Conversation = ConversationSummary & { contextSources: ContextSource[] };
export type Message = {
  id: string;
  role: "USER" | "ASSISTANT";
  content: string;
  status: "SAVED" | "PENDING" | "COMPLETED" | "FAILED";
  errorMessage: string | null;
  clientRequestId: string | null;
  replyToMessageId: string | null;
  createdAt: string;
  updatedAt: string;
};
export type ConversationDetail = { conversation: Conversation; messages: Message[] };

export const emptyForm = { interviewPackageId: "", interviewId: "", reviewReportId: "", weaknessTag: "", title: "" };
export const interviewTypeLabel: Record<Interview["simulationType"], string> = { REAL: "手动录入", AI_TEXT: "AI 文本模拟", AI_VOICE: "AI 语音模拟" };

export function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

export function requestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

export function upsertMessage(messages: Message[], next: Message) {
  const index = messages.findIndex((message) => message.id === next.id || (message.role === "ASSISTANT" && message.replyToMessageId === next.replyToMessageId));
  if (index < 0) return [...messages, next];
  return messages.map((message, current) => (current === index ? next : message));
}
