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
type Category = { id: string; name: string };
type KnowledgeDocument = { id: string; categoryId: string };

export default function AiMockInterviewsPage() {
  const [packages, setPackages] = useState<Package[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [packageId, setPackageId] = useState("");
  const [sourceMode, setSourceMode] = useState<"STANDARD" | "KNOWLEDGE">("STANDARD");
  const [categoryIds, setCategoryIds] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [entering, setEntering] = useState(false);
  const preparing = useRef<Record<string, Promise<void>>>({});
  const router = useRouter();
  const selected = packages.find((item) => item.id === packageId);
  function prepareSelected(id: string, mode: "STANDARD" | "KNOWLEDGE", ids: string[]) {
    const selection = mode === "STANDARD" ? id : `${id}:KNOWLEDGE:${[...ids].sort().join(",")}`;
    const key = `ai-mock-prepared:${selection}`;
    if (window.sessionStorage.getItem(`ai-mock-session:${selection}`)) return Promise.resolve();
    if (window.sessionStorage.getItem(key)) return Promise.resolve();
    if (!preparing.current[selection]) {
      window.sessionStorage.setItem(`ai-mock-selected-at:${selection}`, String(Date.now()));
      preparing.current[selection] = api<{ id: string }>("/api/v1/ai-mock-interviews/prepare", {
        method: "POST", body: JSON.stringify({ interviewPackageId: id, sourceMode: mode, categoryIds: ids }),
      }).then((session) => { window.sessionStorage.setItem(key, session.id); })
        .catch(() => { delete preparing.current[selection]; /* the room will retry if needed */ });
    }
    return preparing.current[selection];
  }
  useEffect(() => {
    void Promise.all([
      api<Package[]>("/api/v1/interview-packages"),
      api<Category[]>("/api/v1/knowledge/categories"),
      api<KnowledgeDocument[]>("/api/v1/knowledge/documents"),
    ])
      .then(([items, nextCategories, nextDocuments]) => {
        setPackages(items);
        setCategories(nextCategories);
        setDocuments(nextDocuments);
      })
      .catch((caught: unknown) =>
        setError(caught instanceof Error ? caught.message : "加载面试包失败。"),
      );
  }, []);
  useEffect(() => {
    if (packageId && sourceMode === "STANDARD") void prepareSelected(packageId, "STANDARD", []);
  }, [packageId, sourceMode]);
  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero ai-mock-hero">
          <p className="eyebrow">AI MOCK INTERVIEW</p>
          <h1>
            <em>专注表达，逐题练习。</em>
          </h1>
          <p className="intro">
            选择面试包和出题方式，进入面试室后开始本轮练习。
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
              <option value="" hidden>请选择面试包</option>
              {packages.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.company} · {item.role} · {item.interviewRound}
                </option>
              ))}
            </select>
          </label>
          <fieldset className="ai-mock-source">
            <legend>出题方式</legend>
            <label><input type="radio" name="voiceSourceMode" checked={sourceMode === "STANDARD"} onChange={() => setSourceMode("STANDARD")} /> 常规模拟</label>
            <label><input type="radio" name="voiceSourceMode" checked={sourceMode === "KNOWLEDGE"} onChange={() => setSourceMode("KNOWLEDGE")} /> 基于知识库</label>
          </fieldset>
          {sourceMode === "KNOWLEDGE" && (
            <fieldset className="ai-mock-source">
              <legend>选择知识库类别（可多选）</legend>
              {categories.map((category) => {
                const count = documents.filter((document) => document.categoryId === category.id).length;
                return <label key={category.id}>
                  <input type="checkbox" checked={categoryIds.includes(category.id)} disabled={!count || (!categoryIds.includes(category.id) && (categoryIds.length >= 10 || documents.filter((document) => categoryIds.includes(document.categoryId)).length + count > 20))}
                    onChange={(event) => setCategoryIds((current) => event.target.checked ? [...current, category.id] : current.filter((id) => id !== category.id))} />
                  {category.name}（{count} 份文档）
                </label>;
              })}
              {categories.length === 0 && <p>暂无知识库类别，请先在资料库导入文档。</p>}
              <p>本场将从 {documents.filter((document) => categoryIds.includes(document.categoryId)).length} 份文档检索出题依据。</p>
            </fieldset>
          )}
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
              disabled={!packageId || entering || (sourceMode === "KNOWLEDGE" && categoryIds.length === 0)}
              onClick={async () => {
                setEntering(true);
                if (sourceMode === "STANDARD") await prepareSelected(packageId, sourceMode, []);
                const query = new URLSearchParams({ packageId, sourceMode, categoryIds: categoryIds.join(",") });
                router.push(`/ai-mock-interviews/room?${query}`);
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
