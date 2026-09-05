"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import ThemeToggle from "@/components/ThemeToggle";
import UserGuide from "@/components/UserGuide";
import { signOut } from "@/lib/auth";

const links = [
  { href: "/", label: "首页", hint: "概览" },
  { href: "/library", label: "资料库", hint: "资料与面试包" },
  { href: "/interviews", label: "面试记录", hint: "记录与复盘" },
  { href: "/weaknesses", label: "薄弱点", hint: "建议与任务" },
  { href: "/mock-interviews", label: "AI 文本模拟", hint: "文本题目，不限时" },
  { href: "/ai-mock-interviews", label: "AI 语音模拟", hint: "真实场景训练" },
  { href: "/ai-conversations", label: "AI 对话", hint: "自由对话" },
];

export default function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const current = links.find((link) =>
    link.href === "/" ? pathname === "/" : pathname.startsWith(link.href),
  );

  function logout() {
    void signOut();
    router.replace("/login");
    router.refresh();
  }

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <Link className="sidebar-brand" href="/">
          <span className="brand-mark">面</span>
          <span>
            面试助手<small>INTERVIEW ASSISTANT</small>
          </span>
        </Link>
        <nav className="sidebar-nav" aria-label="主导航">
          {links.map((link) => (
            <Link
              className={
                current?.href === link.href ? "sidebar-link active" : "sidebar-link"
              }
              href={link.href}
              key={link.href}
            >
              <strong>{link.label}</strong>
              <small>{link.hint}</small>
            </Link>
          ))}
        </nav>
      </aside>
      <div
        className={
          pathname.startsWith("/ai-conversations")
            ? "app-main chat-app-main"
            : "app-main"
        }
      >
        <header className="app-topbar">
          <div className="topbar-title">
            <span className="topbar-kicker">INTERVIEW ASSISTANT</span>
            <strong>{current?.label ?? "面试助手"}</strong>
          </div>
          <div className="topbar-actions">
            <UserGuide />
            <ThemeToggle />
            <button className="theme-toggle" type="button" onClick={logout}>
              退出登录
            </button>
          </div>
        </header>
        {children}
      </div>
    </div>
  );
}
