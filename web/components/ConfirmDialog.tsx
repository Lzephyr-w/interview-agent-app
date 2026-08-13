"use client";

import { useEffect, useId, useRef } from "react";

type ConfirmTone = "primary" | "danger";

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  confirmTone?: ConfirmTone;
  alternativeLabel?: string;
  alternativeTone?: ConfirmTone;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  onAlternative?: () => void;
};

export default function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = "取消",
  confirmTone = "primary",
  alternativeLabel,
  alternativeTone = "danger",
  busy = false,
  onConfirm,
  onCancel,
  onAlternative,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const alternativeRef = useRef<HTMLButtonElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const onCancelRef = useRef(onCancel);
  const titleId = useId();
  const descriptionId = useId();
  onCancelRef.current = onCancel;

  useEffect(() => {
    if (!open) return;
    const previousFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    const focusTarget =
      confirmTone === "danger" ? cancelRef.current : confirmRef.current;
    window.requestAnimationFrame(() => focusTarget?.focus());
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        if (!busy) onCancelRef.current();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = [
        cancelRef.current,
        alternativeRef.current,
        confirmRef.current,
      ].filter(
        (item): item is HTMLButtonElement => item !== null && !item.disabled,
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, [busy, confirmTone, open]);

  if (!open) return null;

  return (
    <div
      className="confirm-dialog-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) onCancel();
      }}
    >
      <div
        className="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
      >
        <span className="confirm-dialog-mark" aria-hidden="true">
          ?
        </span>
        <p className="profile-label">请确认操作</p>
        <h2 id={titleId}>{title}</h2>
        <p id={descriptionId} className="confirm-dialog-description">
          {description}
        </p>
        <div className="confirm-dialog-actions">
          <button
            ref={cancelRef}
            className="secondary-button"
            type="button"
            onClick={onCancel}
            disabled={busy}
          >
            {cancelLabel}
          </button>
          {alternativeLabel && onAlternative && (
            <button
              ref={alternativeRef}
              className={`dialog-confirm-button ${alternativeTone}`}
              type="button"
              onClick={onAlternative}
              disabled={busy}
            >
              {busy ? "处理中…" : alternativeLabel}
            </button>
          )}
          <button
            ref={confirmRef}
            className={`dialog-confirm-button ${confirmTone}`}
            type="button"
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? "处理中…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
