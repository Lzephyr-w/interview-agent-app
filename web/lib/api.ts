import { getSession, signOut } from "@/lib/auth";

type ApiOptions = Omit<RequestInit, "headers"> & { headers?: HeadersInit };

export async function api<T>(
  path: string,
  options: ApiOptions = {},
): Promise<T> {
  const session = getSession();
  if (!session) throw new Error("登录已过期，请重新登录。");
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
  if (response.status === 401) signOut();
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      message?: string;
    } | null;
    throw new Error(body?.message ?? "服务暂时不可用，请稍后重试。");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export async function apiBlob(path: string): Promise<Blob> {
  const session = getSession();
  if (!session) throw new Error("登录已过期，请重新登录。");
  const response = await fetch(
    `${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}${path}`,
    { headers: { Authorization: `Bearer ${session.accessToken}` } },
  );
  if (response.status === 401) signOut();
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      message?: string;
    } | null;
    throw new Error(body?.message ?? "服务暂时不可用，请稍后重试。");
  }
  return response.blob();
}
