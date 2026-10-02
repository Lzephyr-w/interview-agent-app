"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, apiBlob } from "@/lib/api";

type Category = { id: string; name: string };
type Document = { id: string; categoryId: string; originalFilename: string; format: string; sizeBytes: number; segmentCount: number };

export default function KnowledgeLibrary() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [name, setName] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [editingCategoryId, setEditingCategoryId] = useState<string | null>(null);
  const [editingCategoryName, setEditingCategoryName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function refresh() {
    const [nextCategories, nextDocuments] = await Promise.all([
      api<Category[]>("/api/v1/knowledge/categories"),
      api<Document[]>("/api/v1/knowledge/documents"),
    ]);
    setCategories(nextCategories);
    setDocuments(nextDocuments);
    setCategoryId((current) => nextCategories.some((item) => item.id === current) ? current : nextCategories[0]?.id ?? "");
  }

  useEffect(() => { void refresh().catch((cause: unknown) => setError(cause instanceof Error ? cause.message : "知识库加载失败。")); }, []);

  async function createCategory(event: FormEvent) {
    event.preventDefault(); setError(""); setBusy(true);
    try {
      const created = await api<Category>("/api/v1/knowledge/categories", { method: "POST", body: JSON.stringify({ name }) });
      setName(""); await refresh(); setCategoryId(created.id); setNotice("类别已创建。");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "创建失败。"); }
    finally { setBusy(false); }
  }

  async function renameCategory(event: FormEvent) {
    event.preventDefault();
    if (!editingCategoryId) return;
    setError(""); setBusy(true);
    try {
      await api(`/api/v1/knowledge/categories/${editingCategoryId}`, { method: "PUT", body: JSON.stringify({ name: editingCategoryName }) });
      await refresh(); setEditingCategoryId(null); setNotice("类别名称已更新。");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "修改类别失败。"); }
    finally { setBusy(false); }
  }

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!file || !categoryId) return;
    if (file.size > 10 * 1024 * 1024) { setError("文件不能超过 10 MiB。"); return; }
    setError(""); setBusy(true);
    try {
      const body = new FormData(); body.append("categoryId", categoryId); body.append("file", file);
      await api("/api/v1/knowledge/documents", { method: "POST", body });
      setFile(null); form.reset(); await refresh(); setNotice("文档已导入。");
    } catch (cause) { setError(cause instanceof Error ? cause.message : "导入失败。"); }
    finally { setBusy(false); }
  }

  async function remove(path: string, id: string, label: string) {
    if (!window.confirm(`确定删除${label}吗？`)) return;
    setError(""); setBusy(true);
    try { await api(`${path}/${id}`, { method: "DELETE" }); await refresh(); setNotice("已删除。"); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "删除失败。"); }
    finally { setBusy(false); }
  }

  async function download(doc: Document) {
    try {
      const url = URL.createObjectURL(await apiBlob(`/api/v1/knowledge/documents/${doc.id}/content`));
      const link = document.createElement("a"); link.href = url; link.download = doc.originalFilename; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "下载失败。"); }
  }

  return <section className="library-section knowledge-workspace">
    <header className="knowledge-heading">
      <div>
        <p className="profile-label">PERSONAL KNOWLEDGE BASE</p>
        <h2>知识库</h2>
        <p className="muted">把资料按主题收好，文本模拟时可选一个或多个类别出题。</p>
      </div>
      <div className="knowledge-stats" aria-label="知识库概况">
        <span><strong>{categories.length}</strong> 个类别</span>
        <span><strong>{documents.length}</strong> 份文档</span>
      </div>
    </header>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="knowledge-notice" role="status">{notice}</p>}
    <div className="knowledge-layout">
      <aside className="knowledge-manage">
        <div className="knowledge-create">
          <div className="knowledge-panel-head">
            <span className="knowledge-step">01</span>
            <div><h3>新建类别</h3><p>例如 React、项目经历、前端工程化。</p></div>
          </div>
          <form className="knowledge-create-form" onSubmit={createCategory}>
            <input required maxLength={80} aria-label="新类别名称" value={name} onChange={(event) => setName(event.target.value)} placeholder="输入类别名称" />
            <button className="secondary-button" disabled={busy}>创建</button>
          </form>
        </div>
        <div className="knowledge-panel-head">
          <span className="knowledge-step">02</span>
          <div><h3>导入文档</h3><p>支持 MD、XLSX、DOC、DOCX，单份不超过 10 MiB。</p></div>
        </div>
        <form className="knowledge-upload-form" onSubmit={upload}>
          <label className="field">放入类别
            <select required value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
              <option value="" hidden>请选择类别</option>
              {categories.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </label>
          <label className="knowledge-file-picker">
            <span className="knowledge-file-symbol" aria-hidden="true">↥</span>
            <strong>{file ? file.name : "点击选择文件"}</strong>
            <small>{file ? `${Math.ceil(file.size / 1024)} KB · 可重新选择` : "Markdown、Excel 或 Word 文档"}</small>
            <input required type="file" accept=".md,.xlsx,.doc,.docx" aria-label="选择知识库文件" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
          <button className="primary-button" disabled={busy || !categoryId}>{busy ? "正在导入…" : "导入到知识库"}</button>
        </form>
      </aside>
      <div className="knowledge-collection">
        <div className="knowledge-collection-head">
          <div><h3>分类文档</h3><p>选择类别后，模拟面试只从其中的文档检索。</p></div>
          <span>{documents.length} 份文档</span>
        </div>
        {categories.length === 0 && <div className="knowledge-empty"><strong>还没有知识库类别</strong><p>先在左侧新建类别，再导入文档。</p></div>}
        {categories.map((category) => {
          const categoryDocuments = documents.filter((doc) => doc.categoryId === category.id);
          return <section className="knowledge-category" key={category.id} aria-label={category.name}>
            <div className="knowledge-category-head">
              <div className="knowledge-category-title">
                <span className="knowledge-category-mark" aria-hidden="true">{category.name.slice(0, 1).toUpperCase()}</span>
                {editingCategoryId === category.id ? <form className="knowledge-category-edit" onSubmit={renameCategory}>
                  <input required autoFocus maxLength={80} aria-label="类别名称" value={editingCategoryName} onChange={(event) => setEditingCategoryName(event.target.value)} />
                  <button className="secondary-button" disabled={busy}>保存</button>
                  <button className="knowledge-text-action" type="button" disabled={busy} onClick={() => setEditingCategoryId(null)}>取消</button>
                </form> : <div><h4>{category.name}</h4><small>{categoryDocuments.length} 份文档</small></div>}
              </div>
              {editingCategoryId !== category.id && <div className="knowledge-category-actions">
                <button className="knowledge-edit-button" type="button" disabled={busy} onClick={() => { setEditingCategoryId(category.id); setEditingCategoryName(category.name); }}>编辑名称</button>
                {categoryDocuments.length === 0 && <button className="knowledge-text-action" type="button" disabled={busy} onClick={() => void remove("/api/v1/knowledge/categories", category.id, `类别「${category.name}」`)}>删除类别</button>}
              </div>}
            </div>
            {categoryDocuments.length === 0 ? <p className="knowledge-category-empty">暂无文档，可从左侧导入。</p> : <ul className="knowledge-document-list">
              {categoryDocuments.map((doc) => <li className="knowledge-document" key={doc.id}>
                <span className="knowledge-format" aria-hidden="true">{doc.format === "xlsx" ? "XLS" : doc.format.toUpperCase()}</span>
                <div className="knowledge-document-info"><strong title={doc.originalFilename}>{doc.originalFilename}</strong><small>{doc.segmentCount} 个片段 · {Math.ceil(doc.sizeBytes / 1024)} KB</small></div>
                <div className="knowledge-document-actions">
                  {categories.length > 1 && <select aria-label={`移动 ${doc.originalFilename} 到类别`} title="移动到其他类别" value={doc.categoryId} onChange={async (event) => { try { await api(`/api/v1/knowledge/documents/${doc.id}/category`, { method: "PUT", body: JSON.stringify({ categoryId: event.target.value }) }); await refresh(); } catch (cause) { setError(cause instanceof Error ? cause.message : "移动失败。"); } }}>{categories.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select>}
                  <button type="button" className="secondary-button" onClick={() => void download(doc)}>下载</button>
                  <button type="button" className="knowledge-text-action" disabled={busy} onClick={() => void remove("/api/v1/knowledge/documents", doc.id, `文档「${doc.originalFilename}」`)}>删除</button>
                </div>
              </li>)}
            </ul>}
          </section>;
        })}
      </div>
    </div>
  </section>;
}
