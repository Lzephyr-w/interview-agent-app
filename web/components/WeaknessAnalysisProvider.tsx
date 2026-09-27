"use client";

import { createContext, useContext, useRef, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import ConfirmDialog from "@/components/ConfirmDialog";
import { api } from "@/lib/api";

export type WeaknessEvidence = {
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

export type WeaknessAnalysis = {
  summary: string | null;
  analyzedAt: string | null;
  stale: boolean;
  items: {
    tag: string;
    title: string;
    diagnosis: string;
    action: string;
    evidence: WeaknessEvidence[];
  }[];
};

type WeaknessAnalysisContextValue = {
  analysis: WeaknessAnalysis | undefined;
  analyzing: boolean;
  loadAnalysis: () => Promise<void>;
  startAnalysis: () => Promise<WeaknessAnalysis>;
};

const WeaknessAnalysisContext = createContext<WeaknessAnalysisContextValue | null>(null);

export function useWeaknessAnalysis() {
  const context = useContext(WeaknessAnalysisContext);
  if (!context) throw new Error("useWeaknessAnalysis 必须在 WeaknessAnalysisProvider 内使用。");
  return context;
}

export default function WeaknessAnalysisProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [analysis, setAnalysis] = useState<WeaknessAnalysis>();
  const [analyzing, setAnalyzing] = useState(false);
  const [successOpen, setSuccessOpen] = useState(false);
  const pending = useRef<Promise<WeaknessAnalysis> | undefined>(undefined);
  const revision = useRef(0);

  async function loadAnalysis() {
    const currentRevision = revision.current;
    const next = await api<WeaknessAnalysis>("/api/v1/weaknesses/analysis");
    if (currentRevision === revision.current) setAnalysis(next);
  }

  function startAnalysis() {
    if (pending.current) return pending.current;
    revision.current += 1;
    setAnalyzing(true);
    const request = api<WeaknessAnalysis>("/api/v1/weaknesses/analysis", { method: "POST" })
      .then((next) => {
        setAnalysis(next);
        setSuccessOpen(true);
        return next;
      })
      .finally(() => {
        pending.current = undefined;
        setAnalyzing(false);
      });
    pending.current = request;
    return request;
  }

  return (
    <WeaknessAnalysisContext.Provider value={{ analysis, analyzing, loadAnalysis, startAnalysis }}>
      {children}
      {analyzing && pathname !== "/weaknesses" && (
        <div className="toast-region" aria-label="页面提示">
          <div className="toast toast-notice" role="status">正在进行 AI 分析，完成后会通知你。</div>
        </div>
      )}
      <ConfirmDialog
        open={successOpen}
        title="AI 分析成功"
        description="薄弱点分析已生成，可以前往薄弱点页面查看结果。"
        confirmLabel="前往查看"
        onCancel={() => setSuccessOpen(false)}
        onConfirm={() => { setSuccessOpen(false); router.push("/weaknesses"); }}
      />
    </WeaknessAnalysisContext.Provider>
  );
}
