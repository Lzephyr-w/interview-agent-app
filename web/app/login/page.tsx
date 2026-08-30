"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { signIn, signUp } from "@/lib/auth";
import ThemeToggle from "@/components/ThemeToggle";
import Toast from "@/components/Toast";

const emptyFieldErrors = { email: "", password: "", confirmPassword: "" };

export default function LoginPage() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState(emptyFieldErrors);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email"));
    const password = String(form.get("password"));
    const confirmPassword = String(form.get("confirmPassword"));
    const emailInput = formElement.elements.namedItem("email") as HTMLInputElement;
    const nextFieldErrors = {
      email: emailInput.validity.valueMissing
        ? "请输入邮箱地址。"
        : emailInput.validity.typeMismatch
          ? "请输入有效的邮箱地址。"
          : "",
      password: password ? "" : "请输入密码。",
      confirmPassword: registering
        ? confirmPassword
          ? password === confirmPassword
            ? ""
            : "两次输入的密码不一致。"
          : "请确认密码。"
        : "",
    };
    setError("");
    setNotice("");
    if (Object.values(nextFieldErrors).some(Boolean)) {
      setFieldErrors(nextFieldErrors);
      return;
    }
    setFieldErrors(emptyFieldErrors);
    setLoading(true);
    try {
      if (registering) {
        const result = await signUp(email, password);
        if (result.needsEmailConfirmation) {
          setRegistering(false);
          setNotice("若该邮箱可注册，请查收验证邮件后登录。");
          return;
        }
      } else {
        await signIn(email, password);
      }
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
      <Toast
        error={error}
        notice={notice}
        onDismissError={() => setError("")}
        onDismissNotice={() => setNotice("")}
      />
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
        <p className="eyebrow">{registering ? "CREATE ACCOUNT" : "WELCOME BACK"}</p>
        <h1>
          {registering ? (
            <>
              注册后，<em>开始准备。</em>
            </>
          ) : (
            <>
              登录后，<em>继续准备。</em>
            </>
          )}
        </h1>
        <p className="intro">
          {registering
            ? "使用邮箱创建 Supabase Auth 账号。"
            : "使用你的 Supabase Auth 邮箱账号进入面试助手。"}
        </p>
        <form
          className="auth-form"
          key={registering ? "register" : "login"}
          noValidate
          onSubmit={submit}
        >
          <label className="field">
            邮箱
            <input
              name="email"
              type="email"
              required
              autoComplete="email"
              aria-invalid={Boolean(fieldErrors.email)}
              aria-describedby={fieldErrors.email ? "email-error" : undefined}
              onChange={() => setFieldErrors(emptyFieldErrors)}
            />
            {fieldErrors.email && (
              <small className="field-error" id="email-error" role="alert">
                {fieldErrors.email}
              </small>
            )}
          </label>
          <div className="field">
            <label htmlFor="password">密码</label>
            <span className="password-field">
              <input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                required
                autoComplete={registering ? "new-password" : "current-password"}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? "password-error" : undefined}
                onChange={() => setFieldErrors(emptyFieldErrors)}
              />
              <button
                className="password-toggle"
                type="button"
                aria-label={showPassword ? "隐藏密码" : "显示密码"}
                aria-pressed={showPassword}
                onClick={() => setShowPassword(!showPassword)}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
                  <circle cx="12" cy="12" r="3" />
                  {showPassword && <path d="m4 4 16 16" />}
                </svg>
              </button>
            </span>
            {fieldErrors.password && (
              <small className="field-error" id="password-error" role="alert">
                {fieldErrors.password}
              </small>
            )}
          </div>
          {registering && (
            <div className="field">
              <label htmlFor="confirm-password">确认密码</label>
              <span className="password-field">
                <input
                  id="confirm-password"
                  name="confirmPassword"
                  type={showConfirmPassword ? "text" : "password"}
                  required
                  autoComplete="new-password"
                  aria-invalid={Boolean(fieldErrors.confirmPassword)}
                  aria-describedby={
                    fieldErrors.confirmPassword ? "confirm-password-error" : undefined
                  }
                  onChange={() => setFieldErrors(emptyFieldErrors)}
                />
                <button
                  className="password-toggle"
                  type="button"
                  aria-label={showConfirmPassword ? "隐藏确认密码" : "显示确认密码"}
                  aria-pressed={showConfirmPassword}
                  onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
                    <circle cx="12" cy="12" r="3" />
                    {showConfirmPassword && <path d="m4 4 16 16" />}
                  </svg>
                </button>
              </span>
              {fieldErrors.confirmPassword && (
                <small
                  className="field-error"
                  id="confirm-password-error"
                  role="alert"
                >
                  {fieldErrors.confirmPassword}
                </small>
              )}
            </div>
          )}
          <button className="primary-button" disabled={loading}>
            {loading ? (registering ? "注册中…" : "登录中…") : registering ? "注册" : "登录"}
          </button>
          <p className="auth-switch">
            {registering ? "已有账号？" : "还没有账号？"}
            <button
              className="auth-link"
              type="button"
              disabled={loading}
              onClick={() => {
                setRegistering(!registering);
                setShowPassword(false);
                setShowConfirmPassword(false);
                setError("");
                setNotice("");
                setFieldErrors(emptyFieldErrors);
              }}
            >
              {registering ? "返回登录" : "注册账号"}
            </button>
          </p>
        </form>
      </section>
    </main>
  );
}
