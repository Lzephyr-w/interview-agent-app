"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import AppShell from "@/components/AppShell";
import Toast from "@/components/Toast";
import { api } from "@/lib/api";

type Package = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
};

export default function AiMockInterviewsPage() {
  const [packages, setPackages] = useState<Package[]>([]);
  const [packageId, setPackageId] = useState("");
  const [error, setError] = useState("");
  const [entering, setEntering] = useState(false);
  const preparing = useRef<Record<string, Promise<void>>>({});
  const router = useRouter();
  const selected = packages.find((item) => item.id === packageId);
  function prepareSelected(id: string) {
    const key = `ai-mock-prepared:${id}`;
    if (window.sessionStorage.getItem(`ai-mock-session:${id}`)) return Promise.resolve();
    if (window.sessionStorage.getItem(key)) return Promise.resolve();
    if (!preparing.current[id]) {
      window.sessionStorage.setItem(`ai-mock-selected-at:${id}`, String(Date.now()));
      preparing.current[id] = api<{ id: string }>("/api/v1/ai-mock-interviews/prepare", {
        method: "POST", body: JSON.stringify({ interviewPackageId: id }),
      }).then((session) => { window.sessionStorage.setItem(key, session.id); })
        .catch(() => { delete preparing.current[id]; /* the room will retry if needed */ });
    }
    return preparing.current[id];
  }
  useEffect(() => {
    void api<Package[]>("/api/v1/interview-packages")
      .then(setPackages)
      .catch((caught: unknown) =>
        setError(caught instanceof Error ? caught.message : "加载面试包失败。"),
      );
  }, []);
  useEffect(() => {
    if (packageId) void prepareSelected(packageId);
  }, [packageId]);
  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero ai-mock-hero">
          <p className="eyebrow">AI MOCK INTERVIEW</p>
          <h1>
            <em>专注表达，逐题练习。</em>
          </h1>
          <p className="intro">
            选择面试包后会提前准备第一题；点击开始后进入正式面试。
          </p>
        </section>
        <Toast
          error={error}
          notice=""
          onDismissError={() => setError("")}
          onDismissNotice={() => {}}
        />
        <section className="library-section mock-section ai-mock-picker">
          <div className="ai-mock-picker-heading">
            <span>01</span>
            <div>
              <p>INTERVIEW SETUP</p>
              <h2>选择本次面试包</h2>
            </div>
          </div>
          <label className="field">
            公司 · 岗位 · 轮次
            <select
              value={packageId}
              onChange={(event) => setPackageId(event.target.value)}
            >
              <option value="">请选择面试包</option>
              {packages.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.company} · {item.role} · {item.interviewRound}
                </option>
              ))}
            </select>
          </label>
          {selected ? (
            <div className="ai-mock-package-preview">
              <span className="ai-mock-package-mark">✦</span>
              <div>
                <strong>{selected.company}</strong>
                <p>
                  {selected.role} · {selected.interviewRound}
                </p>
              </div>
              <small>
                10 道题
                <br />
                每题 5 分钟
              </small>
            </div>
          ) : (
            <p className="ai-mock-picker-empty">选择后将显示本次模拟信息。</p>
          )}
          <div className="ai-mock-picker-footer">
            <p>内容由AI生成,请仔细甄别。</p>
            <button
              className="primary-button"
              disabled={!packageId || entering}
              onClick={async () => {
                setEntering(true);
                await prepareSelected(packageId);
                router.push(`/ai-mock-interviews/room?packageId=${encodeURIComponent(packageId)}`);
              }}
            >
              {entering ? "正在进入…" : "进入面试室 →"}
            </button>
          </div>
        </section>
      </main>
    </AppShell>
  );
}
