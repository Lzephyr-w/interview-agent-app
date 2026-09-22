import { getSession, signOut } from "@/lib/auth";

type ApiOptions = Omit<RequestInit, "headers"> & { headers?: HeadersInit };
const pendingGets = new Map<string, Promise<unknown>>();
let redirectingToLogin = false;

function redirectToLogin() {
  if (redirectingToLogin) return;
  redirectingToLogin = true;
  void signOut();
  if (typeof window !== "undefined" && window.location.pathname !== "/login") {
    window.location.replace("/login");
  }
}

export function api<T>(
  path: string,
  options: ApiOptions = {},
): Promise<T> {
  const session = getSession();
  if (!session) {
    redirectToLogin();
    return Promise.reject(new Error("登录已过期，请重新登录。"));
  }
  const request = async () => {
    const response = await fetch(
      `${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}${path}`,
      {
        ...options,
        headers: {
          Authorization: `Bearer ${session.accessToken}`,
          ...(options.body && !(options.body instanceof FormData)
            ? { "Content-Type": "application/json" }
            : {}),
          ...options.headers,
        },
      },
    );
    if (response.status === 401) redirectToLogin();
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as {
        message?: string;
      } | null;
      throw new Error(body?.message ?? "服务暂时不可用，请稍后重试。");
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  };
  if ((options.method ?? "GET").toUpperCase() !== "GET") return request();
  const key = `${session.accessToken}:${path}`;
  const pending = pendingGets.get(key) as Promise<T> | undefined;
  if (pending) return pending;
  const result = request();
  pendingGets.set(key, result);
  void result.then(
    () => pendingGets.delete(key),
    () => pendingGets.delete(key),
  );
  return result;
}

export async function streamApi(
  path: string,
  options: ApiOptions,
  onEvent: (event: string, data: string) => void,
) {
  const session = getSession();
  if (!session) { redirectToLogin(); throw new Error("登录已过期，请重新登录。"); }
  const response = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${session.accessToken}`, Accept: "text/event-stream", ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers },
  });
  if (response.status === 401) redirectToLogin();
  if (!response.ok) throw new Error(((await response.json().catch(() => null)) as { message?: string } | null)?.message ?? "服务暂时不可用，请稍后重试。");
  if (!response.body) throw new Error("服务未返回流式响应。");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const consume = (chunk: string) => {
    buffer += chunk;
    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop() ?? "";
    for (const raw of events) {
      let event = "message"; const data: string[] = [];
      for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).trim());
      }
      if (data.length) onEvent(event, data.join("\n"));
    }
  };
  while (true) {
    const next = await reader.read();
    if (next.done) { consume(decoder.decode()); break; }
    consume(decoder.decode(next.value, { stream: true }));
  }
}

export async function apiBlob(path: string): Promise<Blob> {
  const session = getSession();
  if (!session) {
    redirectToLogin();
    throw new Error("登录已过期，请重新登录。");
  }
  const response = await fetch(
    `${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}${path}`,
    { headers: { Authorization: `Bearer ${session.accessToken}` } },
  );
  if (response.status === 401) redirectToLogin();
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      message?: string;
    } | null;
    throw new Error(body?.message ?? "服务暂时不可用，请稍后重试。");
  }
  return response.blob();
}
