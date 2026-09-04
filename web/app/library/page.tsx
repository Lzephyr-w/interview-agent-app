"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import ConfirmDialog from "@/components/ConfirmDialog";
import Toast from "@/components/Toast";
import { api, apiBlob } from "@/lib/api";

type ResumeFile = {
  id: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  parseStatus: "PENDING" | "READY" | "FAILED";
  parsedTruncated: boolean;
  createdAt: string;
};
type JobDescription = {
  id: string;
  company: string;
  role: string;
  content: string;
};
type EvidenceCard = {
  id: string;
  projectName: string;
  technologyStack: string;
  projectDescriptionAndResponsibilities: string;
  projectHighlights: string;
};
type InterviewPackage = {
  id: string;
  company: string;
  role: string;
  interviewRound: string;
  resumeFileId: string | null;
  jobDescriptionId: string | null;
  evidenceCardIds: string[];
};
type Tab =
  "resume-files" | "job-descriptions" | "evidence-cards" | "interview-packages";

const emptyJobDescription = { company: "", role: "", content: "" };
const emptyEvidenceCard = {
  projectName: "",
  technologyStack: "",
  projectDescriptionAndResponsibilities: "",
  projectHighlights: "",
};
const emptyPackage = {
  company: "",
  role: "",
  interviewRound: "",
  resumeFileId: "",
  jobDescriptionId: "",
  evidenceCardIds: [] as string[],
};
const tabs: { id: Tab; label: string; hint: string }[] = [
  { id: "resume-files", label: "简历文件", hint: "上传与管理" },
  { id: "job-descriptions", label: "岗位 JD", hint: "岗位要求" },
  { id: "evidence-cards", label: "项目证据卡", hint: "项目事实" },
  { id: "interview-packages", label: "面试包", hint: "组合资料" },
];

function Field({
  label,
  value,
  onChange,
  multiline = false,
  hint,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  multiline?: boolean;
  hint?: string;
}) {
  return (
    <label className="field">
      {label}
      {hint && <small className="field-hint">{hint}</small>}
      {multiline ? (
        <textarea
          required
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <input
          required
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </label>
  );
}

export default function LibraryPage() {
  const [resumeFiles, setResumeFiles] = useState<ResumeFile[]>([]);
  const [jobDescriptions, setJobDescriptions] = useState<JobDescription[]>([]);
  const [evidenceCards, setEvidenceCards] = useState<EvidenceCard[]>([]);
  const [packages, setPackages] = useState<InterviewPackage[]>([]);
  const [jobDescription, setJobDescription] = useState(emptyJobDescription);
  const [jobDescriptionId, setJobDescriptionId] = useState<string>();
  const [evidenceCard, setEvidenceCard] = useState(emptyEvidenceCard);
  const [evidenceCardId, setEvidenceCardId] = useState<string>();
  const [interviewPackage, setInterviewPackage] = useState(emptyPackage);
  const [packageId, setPackageId] = useState<string>();
  const [activeTab, setActiveTab] = useState<Tab>("resume-files");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [selectedResumeFile, setSelectedResumeFile] = useState<File | null>(
    null,
  );
  const [uploading, setUploading] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [dialog, setDialog] = useState<
    { kind: "resource" | "resume-file"; path: string; id: string } | undefined
  >();

  async function load(showLoading = true) {
    if (showLoading) setLoading(true);
    setError("");
    try {
      const [nextFiles, nextJds, nextCards, nextPackages] = await Promise.all([
        api<ResumeFile[]>("/api/v1/resume-files"),
        api<JobDescription[]>("/api/v1/job-descriptions"),
        api<EvidenceCard[]>("/api/v1/evidence-cards"),
        api<InterviewPackage[]>("/api/v1/interview-packages"),
      ]);
      setResumeFiles(nextFiles);
      setJobDescriptions(nextJds);
      setEvidenceCards(nextCards);
      setPackages(nextPackages);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "资料加载失败。");
    } finally {
      if (showLoading) setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);
  async function save(
    path: string,
    body: unknown,
    id: string | undefined,
    done: () => void,
  ) {
    setError("");
    setMessage("");
    try {
      await api(path + (id ? `/${id}` : ""), {
        method: id ? "PUT" : "POST",
        body: JSON.stringify(body),
      });
      done();
      await load(false);
      setMessage("已保存。");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "保存失败。");
    }
  }
  function remove(path: string, id: string) {
    setDialog({ kind: "resource", path, id });
  }
  async function confirmRemove() {
    if (!dialog) return;
    setDeleting(true);
    setError("");
    setMessage("");
    try {
      await api<void>(`${dialog.path}/${dialog.id}`, { method: "DELETE" });
      await load(false);
      setDialog(undefined);
      setMessage(
        dialog.kind === "resume-file"
          ? "简历文件已删除。"
          : "已删除；相关面试包已同步更新。",
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "删除失败。");
    } finally {
      setDeleting(false);
    }
  }
  async function uploadResumeFile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setMessage("");
    if (!selectedResumeFile) {
      setError("请选择简历文件。");
      return;
    }
    if (selectedResumeFile.size > 10 * 1024 * 1024) {
      setError("简历文件不能超过 10 MiB。");
      return;
    }
    const form = event.currentTarget;
    const body = new FormData();
    body.append("file", selectedResumeFile);
    setUploading(true);
    try {
      await api<ResumeFile>("/api/v1/resume-files", { method: "POST", body });
      form.reset();
      setSelectedResumeFile(null);
      await load(false);
      setMessage("简历文件已上传。");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "上传失败。");
    } finally {
      setUploading(false);
    }
  }
  function removeResumeFile(id: string) {
    setDialog({ kind: "resume-file", path: "/api/v1/resume-files", id });
  }
  async function downloadResumeFile(file: ResumeFile) {
    try {
      const url = URL.createObjectURL(
        await apiBlob(`/api/v1/resume-files/${file.id}/content`),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = file.originalFilename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "下载失败。");
    }
  }
  async function previewResumeFile(file: ResumeFile) {
    const preview = window.open("", "_blank");
    if (!preview) {
      setError("浏览器阻止了预览窗口，请允许弹窗后重试。");
      return;
    }
    try {
      const url = URL.createObjectURL(
        await apiBlob(`/api/v1/resume-files/${file.id}/content`),
      );
      preview.opener = null;
      preview.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (cause) {
      preview.close();
      setError(cause instanceof Error ? cause.message : "预览失败。");
    }
  }

  return (
    <AppShell>
      <main className="app-page">
        <section className="hero-card page-hero library-hero">
          <p className="eyebrow">CLOSED LOOP 1</p>
          <h1>
            把经历，<em>整理成可用证据。</em>
          </h1>
          <p className="intro">
            简历文件、岗位资料和项目事实分开管理，再按需组合成面试包。
          </p>
          <div className="hero-actions">
            <Link className="text-link" href="/">
              返回首页
            </Link>
          </div>
        </section>
        <Toast
          error={error}
          notice={message}
          onDismissError={() => setError("")}
          onDismissNotice={() => setMessage("")}
        />
        {loading ? (
          <p className="muted">正在加载资料…</p>
        ) : (
          <div className="library-layout">
            <nav className="library-tabs" aria-label="资料分类">
              {tabs.map((tab) => (
                <button
                  className={
                    activeTab === tab.id ? "library-tab active" : "library-tab"
                  }
                  type="button"
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                >
                  <strong>{tab.label}</strong>
                  <small>{tab.hint}</small>
                </button>
              ))}
            </nav>
            <div className="library-content">
              {activeTab === "resume-files" && (
                <section className="library-section">
                  <h2>简历文件</h2>
                  <p className="muted">
                    仅支持 PDF、DOC、DOCX，最大 10 MiB。文件仅对当前账户可见。
                  </p>
                  <form className="library-form" onSubmit={uploadResumeFile}>
                    <label className="field">
                      选择文件
                      <input
                        required
                        type="file"
                        accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        onChange={(event) =>
                          setSelectedResumeFile(event.target.files?.[0] ?? null)
                        }
                      />
                    </label>
                    <div className="form-actions">
                      <button className="primary-button" disabled={uploading}>
                        {uploading ? "正在上传…" : "上传简历文件"}
                      </button>
                    </div>
                  </form>
                  <ul className="resource-list">
                    {resumeFiles.map((file) => (
                      <li className="resource-item" key={file.id}>
                        <div>
                          <strong>{file.originalFilename}</strong>
                          <p>
                            {file.contentType} ·{" "}
                            {Math.ceil(file.sizeBytes / 1024)} KB ·{" "}
                            {file.parseStatus === "READY"
                              ? file.parsedTruncated
                                ? "正文已解析（已截断）"
                                : "正文已解析"
                              : file.parseStatus === "FAILED"
                                ? "正文待补充"
                                : "正文待解析"}
                          </p>
                        </div>
                        <div className="item-actions">
                          {file.contentType === "application/pdf" && (
                            <button
                              className="secondary-button"
                              type="button"
                              onClick={() => void previewResumeFile(file)}
                            >
                              预览
                            </button>
                          )}
                          <button
                            className="secondary-button"
                            type="button"
                            onClick={() => void downloadResumeFile(file)}
                          >
                            下载
                          </button>
                          <button
                            className="danger-button"
                            type="button"
                            onClick={() => void removeResumeFile(file.id)}
                          >
                            删除
                          </button>
                        </div>
                      </li>
                    ))}
                    {resumeFiles.length === 0 && (
                      <li className="muted">暂无简历文件。</li>
                    )}
                  </ul>
                </section>
              )}
              {activeTab === "job-descriptions" && (
                <section className="library-section">
                  <h2>岗位 JD</h2>
                  <form
                    className="library-form"
                    onSubmit={(event: FormEvent) => {
                      event.preventDefault();
                      void save(
                        "/api/v1/job-descriptions",
                        jobDescription,
                        jobDescriptionId,
                        () => {
                          setJobDescription(emptyJobDescription);
                          setJobDescriptionId(undefined);
                        },
                      );
                    }}
                  >
                    <div className="form-row">
                      <Field
                        label="公司"
                        value={jobDescription.company}
                        onChange={(company) =>
                          setJobDescription({ ...jobDescription, company })
                        }
                      />
                      <Field
                        label="岗位"
                        value={jobDescription.role}
                        onChange={(role) =>
                          setJobDescription({ ...jobDescription, role })
                        }
                      />
                    </div>
                    <Field
                      label="JD 文本"
                      multiline
                      value={jobDescription.content}
                      onChange={(content) =>
                        setJobDescription({ ...jobDescription, content })
                      }
                    />
                    <div className="form-actions">
                      <button className="primary-button">
                        {jobDescriptionId ? "更新 JD" : "保存 JD"}
                      </button>
                      {jobDescriptionId && (
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => {
                            setJobDescription(emptyJobDescription);
                            setJobDescriptionId(undefined);
                          }}
                        >
                          取消编辑
                        </button>
                      )}
                    </div>
                  </form>
                  <ResourceList
                    items={jobDescriptions}
                    label={(item) => `${item.company} · ${item.role}`}
                    detail={(item) => item.content}
                    onEdit={(item) => {
                      setJobDescription(item);
                      setJobDescriptionId(item.id);
                    }}
                    onDelete={(id) =>
                      void remove("/api/v1/job-descriptions", id)
                    }
                  />
                </section>
              )}
              {activeTab === "evidence-cards" && (
                <section className="library-section">
                  <h2>项目证据卡</h2>
                  <form
                    className="library-form"
                    onSubmit={(event: FormEvent) => {
                      event.preventDefault();
                      void save(
                        "/api/v1/evidence-cards",
                        evidenceCard,
                        evidenceCardId,
                        () => {
                          setEvidenceCard(emptyEvidenceCard);
                          setEvidenceCardId(undefined);
                        },
                      );
                    }}
                  >
                    <Field
                      label="项目名称"
                      value={evidenceCard.projectName}
                      onChange={(projectName) =>
                        setEvidenceCard({ ...evidenceCard, projectName })
                      }
                    />
                    <Field
                      label="项目描述与职责"
                      multiline
                      value={evidenceCard.projectDescriptionAndResponsibilities}
                      onChange={(projectDescriptionAndResponsibilities) =>
                        setEvidenceCard({
                          ...evidenceCard,
                          projectDescriptionAndResponsibilities,
                        })
                      }
                    />
                    <Field
                      label="项目亮点"
                      multiline
                      hint="可按亮点分条填写，每行一个亮点"
                      value={evidenceCard.projectHighlights}
                      onChange={(projectHighlights) =>
                        setEvidenceCard({
                          ...evidenceCard,
                          projectHighlights,
                        })
                      }
                    />
                    <Field
                      label="技术栈"
                      hint="示例：React、TypeScript、Spring Boot、MySQL"
                      value={evidenceCard.technologyStack}
                      onChange={(technologyStack) =>
                        setEvidenceCard({ ...evidenceCard, technologyStack })
                      }
                    />
                    <div className="form-actions">
                      <button className="primary-button">
                        {evidenceCardId ? "更新证据卡" : "保存证据卡"}
                      </button>
                      {evidenceCardId && (
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => {
                            setEvidenceCard(emptyEvidenceCard);
                            setEvidenceCardId(undefined);
                          }}
                        >
                          取消编辑
                        </button>
                      )}
                    </div>
                  </form>
                  <ul className="resource-list">
                    {evidenceCards.map((item) => (
                      <li className="resource-item evidence-card-item" key={item.id}>
                        <div className="evidence-card-summary">
                          <h3>项目名称</h3>
                          <strong>{item.projectName || "待补充"}</strong>
                          <section>
                            <h3>描述与职责</h3>
                            <p>{item.projectDescriptionAndResponsibilities || "待补充"}</p>
                          </section>
                          <section>
                            <h3>项目亮点</h3>
                            <p>{item.projectHighlights || "待补充"}</p>
                          </section>
                          <section>
                            <h3>技术栈</h3>
                            <p>{item.technologyStack || "待补充"}</p>
                          </section>
                        </div>
                        <div className="item-actions">
                          <button className="secondary-button" type="button" onClick={() => { setEvidenceCard(item); setEvidenceCardId(item.id); }}>编辑</button>
                          <button className="danger-button" type="button" onClick={() => void remove("/api/v1/evidence-cards", item.id)}>删除</button>
                        </div>
                      </li>
                    ))}
                    {evidenceCards.length === 0 && <li className="muted">暂无资料。</li>}
                  </ul>
                </section>
              )}
              {activeTab === "interview-packages" && (
                <section className="library-section">
                  <h2>面试包</h2>
                  <p className="muted">
                    选择已有简历文件、JD
                    和项目证据卡，组成一次面试所用资料；文件上传在“简历文件”Tab
                    完成。
                  </p>
                  <form
                    className="library-form"
                    onSubmit={(event: FormEvent) => {
                      event.preventDefault();
                      if (!interviewPackage.resumeFileId) {
                        setError("请选择一份简历文件。");
                        return;
                      }
                      void save(
                        "/api/v1/interview-packages",
                        interviewPackage,
                        packageId,
                        () => {
                          setInterviewPackage(emptyPackage);
                          setPackageId(undefined);
                        },
                      );
                    }}
                  >
                    <div className="form-row">
                      <Field
                        label="公司"
                        value={interviewPackage.company}
                        onChange={(company) =>
                          setInterviewPackage({ ...interviewPackage, company })
                        }
                      />
                      <Field
                        label="岗位"
                        value={interviewPackage.role}
                        onChange={(role) =>
                          setInterviewPackage({ ...interviewPackage, role })
                        }
                      />
                    </div>
                    <Field
                      label="面试轮次"
                      value={interviewPackage.interviewRound}
                      onChange={(interviewRound) =>
                        setInterviewPackage({
                          ...interviewPackage,
                          interviewRound,
                        })
                      }
                    />
                    <label className="field">
                      选择已上传的简历文件
                      <select
                        required
                        value={interviewPackage.resumeFileId}
                        onChange={(event) =>
                          setInterviewPackage({
                            ...interviewPackage,
                            resumeFileId: event.target.value,
                          })
                        }
                      >
                        <option value="">请选择简历文件</option>
                        {resumeFiles.map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.originalFilename}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="field">
                      关联 JD
                      <select
                        required
                        value={interviewPackage.jobDescriptionId}
                        onChange={(event) =>
                          setInterviewPackage({
                            ...interviewPackage,
                            jobDescriptionId: event.target.value,
                          })
                        }
                      >
                        <option value="">请选择 JD</option>
                        {jobDescriptions.map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.company} · {item.role}
                          </option>
                        ))}
                      </select>
                    </label>
                    <div className="field">
                      关联项目证据卡
                      <div className="check-list">
                        {evidenceCards.length ? (
                          evidenceCards.map((item) => (
                            <label key={item.id}>
                              <input
                                type="checkbox"
                                checked={interviewPackage.evidenceCardIds.includes(
                                  item.id,
                                )}
                                onChange={(event) =>
                                  setInterviewPackage({
                                    ...interviewPackage,
                                    evidenceCardIds: event.target.checked
                                      ? [
                                          ...interviewPackage.evidenceCardIds,
                                          item.id,
                                        ]
                                      : interviewPackage.evidenceCardIds.filter(
                                          (id) => id !== item.id,
                                        ),
                                  })
                                }
                              />
                              {item.projectName}
                            </label>
                          ))
                        ) : (
                          <span className="muted">
                            尚无证据卡，可稍后补充。
                          </span>
                        )}
                      </div>
                    </div>
                    <div className="form-actions">
                      <button className="primary-button">
                        {packageId ? "更新面试包" : "创建面试包"}
                      </button>
                      {packageId && (
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => {
                            setInterviewPackage(emptyPackage);
                            setPackageId(undefined);
                          }}
                        >
                          取消编辑
                        </button>
                      )}
                    </div>
                  </form>
                  <ResourceList
                    items={packages}
                    label={(item) =>
                      `${item.company} · ${item.role} · ${item.interviewRound}`
                    }
                    detail={(item) =>
                      `关联 ${item.evidenceCardIds.length} 张项目证据卡`
                    }
                    onEdit={(item) => {
                      setInterviewPackage({
                        company: item.company,
                        role: item.role,
                        interviewRound: item.interviewRound,
                        resumeFileId: item.resumeFileId ?? "",
                        jobDescriptionId: item.jobDescriptionId ?? "",
                        evidenceCardIds: item.evidenceCardIds,
                      });
                      setPackageId(item.id);
                    }}
                    onDelete={(id) =>
                      void remove("/api/v1/interview-packages", id)
                    }
                  />
                </section>
              )}
            </div>
          </div>
        )}
      </main>
      <ConfirmDialog
        open={dialog !== undefined}
        title={
          dialog?.kind === "resume-file"
            ? "删除这份简历文件？"
            : "删除这项资料？"
        }
        description={
          dialog?.kind === "resume-file"
            ? "关联面试包将不再使用这份文件，操作无法撤销。"
            : "这项资料会被删除，相关面试包会同步更新，操作无法撤销。"
        }
        confirmLabel="确认删除"
        confirmTone="danger"
        busy={deleting}
        onConfirm={() => void confirmRemove()}
        onCancel={() => setDialog(undefined)}
      />
    </AppShell>
  );
}

function ResourceList<T extends { id: string }>({
  items,
  label,
  detail,
  onEdit,
  onDelete,
}: {
  items: T[];
  label: (item: T) => string;
  detail: (item: T) => string;
  onEdit: (item: T) => void;
  onDelete: (id: string) => void;
}) {
  return (
    <ul className="resource-list">
      {items.map((item) => (
        <li className="resource-item" key={item.id}>
          <div>
            <strong>{label(item)}</strong>
            <p>{detail(item)}</p>
          </div>
          <div className="item-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={() => onEdit(item)}
            >
              编辑
            </button>
            <button
              className="danger-button"
              type="button"
              onClick={() => onDelete(item.id)}
            >
              删除
            </button>
          </div>
        </li>
      ))}
      {items.length === 0 && <li className="muted">暂无资料。</li>}
    </ul>
  );
}
