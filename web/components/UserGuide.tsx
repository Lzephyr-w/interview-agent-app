"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";

const steps = [
  ["01", "准备资料", "上传简历、录入岗位 JD；项目证据卡可选，用来补充真实经历。"],
  ["02", "创建面试包", "在资料库组合简历、JD 和项目事实，作为模拟面试的训练资料。"],
  ["03", "开始训练", "文本和语音模拟需要面试包；AI 对话也可以不指定面试包直接开始。"],
  ["04", "复盘成长", "完成逐题复盘后启动 AI 分析，再根据薄弱点创建训练任务。"],
];

export default function UserGuide() {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <>
      <button
        className="guide-toggle"
        type="button"
        onClick={() => setOpen(true)}
        aria-label="打开使用手册"
      >
        <span aria-hidden="true">✦</span>
        使用手册
      </button>
      <dialog
        ref={dialogRef}
        className="guide-dialog"
        aria-labelledby="guide-title"
        onClose={() => setOpen(false)}
      >
        <div className="guide-dialog-header">
          <div>
            <p className="profile-label">WELCOME TO YOUR PRACTICE ROOM</p>
            <h2 id="guide-title">使用手册</h2>
            <p className="guide-lead">
              从资料准备，到模拟训练、逐题复盘和薄弱点任务，完成一次可持续的练习闭环。
            </p>
          </div>
          <button
            className="guide-close"
            type="button"
            onClick={() => setOpen(false)}
            aria-label="关闭使用手册"
          >
            ×
          </button>
        </div>

        <div className="guide-steps" aria-label="使用流程">
          {steps.map(([number, title, description]) => (
            <div className="guide-step" key={number}>
              <span className="guide-step-number">{number}</span>
              <div>
                <h3>{title}</h3>
                <p>{description}</p>
              </div>
            </div>
          ))}
        </div>

        <div className="guide-sections">
          <section>
            <span className="guide-section-icon" aria-hidden="true">▣</span>
            <div>
              <h3>资料库</h3>
              <p>先准备简历和岗位 JD，再按需要补充项目证据卡并创建面试包。</p>
            </div>
          </section>
          <section>
            <span className="guide-section-icon" aria-hidden="true">◌</span>
            <div>
              <h3>模拟训练</h3>
              <p>文本模拟适合快速练习，语音模拟更接近真实面试，AI 对话可自由追问和创建任务。</p>
            </div>
          </section>
          <section>
            <span className="guide-section-icon" aria-hidden="true">↗</span>
            <div>
              <h3>复盘成长</h3>
              <p>在面试记录完成逐题复盘，在薄弱点启动 AI 分析并管理训练任务。</p>
            </div>
          </section>
        </div>

        <div className="guide-dialog-footer">
          <span>小提示：没有项目证据卡也可以先创建面试包，之后再补充。</span>
          <Link className="dialog-confirm-button primary" href="/library" onClick={() => setOpen(false)}>
            去资料库
          </Link>
        </div>
      </dialog>
    </>
  );
}
