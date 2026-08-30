export type Session = { accessToken: string; expiresAt: number };

const sessionKey = "interview_agent.session";

type AuthResponse = {
  access_token?: string;
  expires_in?: number;
  error_description?: string;
  msg?: string;
};

function clearSession() {
  localStorage.removeItem(sessionKey);
  document.cookie =
    "interview_agent_authenticated=; path=/; max-age=0; samesite=lax";
}

function supabaseConfig() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !anonKey)
    throw new Error(
      "尚未配置 Supabase 登录。请填写 web/.env.local 后重启前端。",
    );
  return { url, anonKey };
}

function saveSession(accessToken: string, expiresIn: number) {
  const session = {
    accessToken,
    expiresAt: Date.now() + expiresIn * 1000,
  };
  localStorage.setItem(sessionKey, JSON.stringify(session));
  document.cookie = `interview_agent_authenticated=1; path=/; max-age=${expiresIn}; samesite=lax`;
}

export function getSession(): Session | null {
  const value = localStorage.getItem(sessionKey);
  if (!value) return null;
  try {
    const session = JSON.parse(value) as Session;
    if (session.expiresAt > Date.now()) return session;
    clearSession();
    return null;
  } catch {
    clearSession();
    return null;
  }
}

export async function signIn(email: string, password: string) {
  const { url, anonKey } = supabaseConfig();
  const response = await fetch(`${url}/auth/v1/token?grant_type=password`, {
    method: "POST",
    headers: { "Content-Type": "application/json", apikey: anonKey },
    body: JSON.stringify({ email, password }),
  });
  const body = (await response.json().catch(() => ({}))) as AuthResponse;
  if (!response.ok || !body.access_token || !body.expires_in)
    throw new Error(
      body.error_description ?? body.msg ?? "登录失败，请检查邮箱和密码。",
    );

  saveSession(body.access_token, body.expires_in);
}

export async function signUp(email: string, password: string) {
  clearSession();
  const { url, anonKey } = supabaseConfig();
  let response: Response;
  try {
    response = await fetch(`${url}/auth/v1/signup`, {
      method: "POST",
      headers: { "Content-Type": "application/json", apikey: anonKey },
      body: JSON.stringify({ email, password }),
    });
  } catch {
    throw new Error("注册暂时无法完成，请稍后重试。");
  }
  const body = (await response.json().catch(() => ({}))) as AuthResponse;
  if (!response.ok) throw new Error("注册暂时无法完成，请稍后重试。");

  if (
    typeof body.access_token === "string" &&
    body.access_token.length > 0 &&
    typeof body.expires_in === "number" &&
    Number.isFinite(body.expires_in) &&
    body.expires_in > 0
  ) {
    saveSession(body.access_token, body.expires_in);
    return { needsEmailConfirmation: false };
  }
  return { needsEmailConfirmation: true };
}

export async function signOut() {
  const session = getSession();
  clearSession();
  if (!session) return;
  try {
    const { url, anonKey } = supabaseConfig();
    await fetch(`${url}/auth/v1/logout`, {
      method: "POST",
      headers: {
        apikey: anonKey,
        Authorization: `Bearer ${session.accessToken}`,
      },
    });
  } catch {
    /* Local logout still succeeds when Supabase is unavailable. */
  }
}
