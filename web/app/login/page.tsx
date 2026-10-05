"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { signIn, signUp } from "@/lib/auth";
import { forgetRememberedLogin, loadRememberedLogin, rememberLogin } from "@/lib/remember-login";
import ThemeToggle from "@/components/ThemeToggle";
import UserGuide from "@/components/UserGuide";
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
  const [rememberPassword, setRememberPassword] = useState(false);
  const [savedLoginReady, setSavedLoginReady] = useState(false);
  const [fieldErrors, setFieldErrors] = useState(emptyFieldErrors);
  const submitting = useRef(false);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (registering) return;
    let active = true;
    setSavedLoginReady(false);
    void loadRememberedLogin().then((saved) => {
      if (!active || !formRef.current) return;
      setRememberPassword(Boolean(saved));
      if (saved) {
        (formRef.current.elements.namedItem("email") as HTMLInputElement).value = saved.email;
        (formRef.current.elements.namedItem("password") as HTMLInputElement).value = saved.password;
      }
      setSavedLoginReady(true);
    });
    return () => { active = false; };
  }, [registering]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current || (!registering && !savedLoginReady)) return;
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
    submitting.current = true;
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
        if (rememberPassword) await rememberLogin(email, password);
      }
      router.replace("/");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录失败。");
    } finally {
      submitting.current = false;
      setLoading(false);
    }
  }

  return (
    <main className="auth-page auth-studio">
      <Toast
        error={error}
        notice={notice}
        onDismissError={() => setError("")}
        onDismissNotice={() => setNotice("")}
      />
      <header className="auth-header">
          <div className="brand" aria-label="智面 · AI 面试训练平台">
            <span className="brand-mark">
              <img src="/images/ai-assistant.png" alt="" />
            </span>
            <span>
              智面<small>AI 面试训练平台</small>
            </span>
          </div>
          <div className="auth-top-actions">
            <UserGuide showLibraryLink={false} />
            <ThemeToggle />
          </div>
      </header>
      <div className="auth-workspace">
        <section className="auth-story" aria-labelledby="auth-story-title">
          <div className="auth-story-heading">
            <p className="auth-kicker"><span /> YOUR NEXT CHAPTER</p>
            <h2 id="auth-story-title">让每一次练习，<br />成为下一次的<em>底气。</em></h2>
            <p>从准备到上场，你的 AI 面试搭档始终在场。</p>
          </div>
          <div className="auth-orbit-scene" aria-hidden="true">
            <div className="auth-orbit auth-orbit-outer" />
            <div className="auth-orbit auth-orbit-inner" />
            <div className="auth-orbit-core">
              <img src="/images/ai-assistant-light.png" alt="" />
              <span>YOUR AI COPILOT</span>
            </div>
            <div className="auth-float-card auth-question-card">
              <span className="auth-card-caption">✦ MOCK INTERVIEW</span>
              <strong>聊聊你最有挑战的项目？</strong>
              <div className="auth-wave"><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /><i /></div>
              <span className="auth-card-note">每一次表达，都更进一步</span>
            </div>
            <div className="auth-float-card auth-growth-card">
              <span className="auth-growth-icon">↗</span>
              <div><strong>看见自己的成长</strong><span className="auth-card-note">练习 · 复盘 · 突破</span></div>
            </div>
            <span className="auth-orbit-spark auth-spark-one">✦</span>
            <span className="auth-orbit-spark auth-spark-two">+</span>
          </div>
          <div className="auth-story-footer"><span>01 / 准备</span><span>02 / 模拟</span><span>03 / 进阶</span></div>
        </section>
        <section className="auth-card" aria-labelledby="auth-title">
        <div className="auth-form-mark" aria-hidden="true">↗</div>
        <p className="eyebrow">{registering ? "CREATE ACCOUNT" : "WELCOME BACK"}</p>
        <h1 id="auth-title">
          {registering ? (
            <>
              开启你的<em>进阶之旅</em>
            </>
          ) : (
            <>
              好久不见，<em>继续向前</em>
            </>
          )}
        </h1>
        <p className="intro">
          {registering
            ? "创建账号，把每一次练习变成看得见的进步"
            : "登录智面，向下一个心动的 offer 再近一步"}
        </p>
        <form
          ref={formRef}
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
              placeholder="输入你的邮箱地址"
              required
              disabled={loading || (!registering && !savedLoginReady)}
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
                placeholder={registering ? "设置你的登录密码" : "输入你的密码"}
                type={showPassword ? "text" : "password"}
                required
                disabled={loading || (!registering && !savedLoginReady)}
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
                  placeholder="再次输入密码"
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
          {!registering && (
            <label className="auth-remember">
              <input
                type="checkbox"
                name="rememberPassword"
                checked={rememberPassword}
                disabled={loading || !savedLoginReady}
                onChange={(event) => {
                  const checked = event.target.checked;
                  setRememberPassword(checked);
                  if (!checked) void forgetRememberedLogin().catch(() => setError("无法清除已记住的密码，请稍后重试。"));
                }}
              />
              记住密码
            </label>
          )}
          <button className="primary-button" disabled={loading || (!registering && !savedLoginReady)}>
            {loading ? (registering ? "注册中…" : "登录中…") : registering ? "注册" : "登录"}
            <span aria-hidden="true">↗</span>
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
        <p className="auth-form-footnote"><span aria-hidden="true">◈</span> 专注每一次练习，让机会有备而来</p>
      </section>
      </div>
      <footer className="auth-page-footer"><span>PREPARE WITH PURPOSE.</span><span>你的下一站，值得认真准备。</span></footer>
    </main>
  );
}
