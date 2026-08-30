"use client";

import { useEffect } from "react";
import { useParams } from "next/navigation";
import AppShell from "@/components/AppShell";

export default function WeaknessRedirectPage() {
  const { tag } = useParams<{ tag: string }>();

  useEffect(() => {
    window.location.replace(`/weaknesses#${encodeURIComponent(tag)}`);
  }, [tag]);

  return <AppShell><main className="app-page"><p className="muted">正在跳转到薄弱点分析…</p></main></AppShell>;
}
