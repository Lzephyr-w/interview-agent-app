"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { signIn } from "@/lib/auth";
import ThemeToggle from "@/components/ThemeToggle";
import Toast from "@/components/Toast";

export default function LoginPage() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      await signIn(String(form.get("email")), String(form.get("password")));
      router.replace("/");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录失败。");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-page">
      <Toast error={error} onDismissError={() => setError("")} />
      <section className="auth-card">
        <div className="topline">
          <div className="brand">
            <span className="brand-mark">面</span>
            <span>
              面试助手<small>INTERVIEW ASSISTANT</small>
            </span>
          </div>
          <ThemeToggle />
        </div>
        <p className="eyebrow">WELCOME BACK</p>
        <h1>
          登录后，<em>继续准备。</em>
        </h1>
        <p className="intro">使用你的 Supabase Auth 邮箱账号进入面试助手。</p>
        <form className="auth-form" onSubmit={submit}>
          <label className="field">
            邮箱
            <input name="email" type="email" required autoComplete="email" />
          </label>
          <label className="field">
            密码
            <input
              name="password"
              type="password"
              required
              autoComplete="current-password"
            />
          </label>
          <button className="primary-button" disabled={loading}>
            {loading ? "登录中…" : "登录"}
          </button>
        </form>
      </section>
    </main>
  );
}
