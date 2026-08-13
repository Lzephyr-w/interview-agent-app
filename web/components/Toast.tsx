"use client";

import { useEffect } from "react";

type ToastProps = {
  error?: string;
  notice?: string;
  onDismissError?: () => void;
  onDismissNotice?: () => void;
};

export default function Toast({
  error,
  notice,
  onDismissError,
  onDismissNotice,
}: ToastProps) {
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => onDismissNotice?.(), 5000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  if (!error && !notice) return null;

  return (
    <div className="toast-region" aria-label="页面提示">
      {error && (
        <div className="toast toast-error" role="alert">
          <span>{error}</span>
          <button
            type="button"
            aria-label="关闭错误提示"
            onClick={onDismissError}
          >
            ×
          </button>
        </div>
      )}
      {notice && (
        <div className="toast toast-notice" role="status">
          <span>{notice}</span>
          <button type="button" aria-label="关闭提示" onClick={onDismissNotice}>
            ×
          </button>
        </div>
      )}
    </div>
  );
}
