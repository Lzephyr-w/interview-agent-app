"use client";

import { useEffect, useState } from "react";

type Theme = "light" | "dark";
const storageKey = "interview-agent.theme";

export default function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>();

  useEffect(() => {
    const saved = localStorage.getItem(storageKey);
    const next: Theme =
      saved === "light" || saved === "dark"
        ? saved
        : window.matchMedia("(prefers-color-scheme: dark)").matches
          ? "dark"
          : "light";
    document.documentElement.dataset.theme = next;
    setTheme(next);
  }, []);

  function toggle() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    localStorage.setItem(storageKey, next);
    setTheme(next);
  }

  // ponytail: localStorage keeps the only setting across route changes and reloads; add a settings store only when settings expand.
  return (
    <button
      className="theme-toggle"
      type="button"
      onClick={toggle}
      aria-label="切换亮色或暗色主题"
    >
      <span aria-hidden="true">{theme === "dark" ? "☀" : "☾"}</span>
      {theme === "dark" ? "亮色" : "暗色"}
    </button>
  );
}
