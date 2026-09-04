"use client";

import { useEffect } from "react";

const controlSelector = "input, textarea, select";
const errorSelector = "[data-constraint-error='true']";

function isControl(target: EventTarget | null): target is HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement {
  return target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement;
}

function clearError(control: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement) {
  const error = control.parentElement?.querySelector<HTMLElement>(errorSelector)
    ?? control.closest(".field")?.querySelector<HTMLElement>(errorSelector);
  if (!error) return;

  const describedBy = control.getAttribute("aria-describedby")
    ?.split(/\s+/)
    .filter((id) => id && id !== error.id)
    .join(" ");
  if (describedBy) control.setAttribute("aria-describedby", describedBy);
  else control.removeAttribute("aria-describedby");

  if (control.dataset.constraintAriaInvalid === "") control.removeAttribute("aria-invalid");
  else control.setAttribute("aria-invalid", control.dataset.constraintAriaInvalid!);
  delete control.dataset.constraintAriaInvalid;
  error.remove();
}

export default function InlineConstraintValidation() {
  useEffect(() => {
    const showError = (event: Event) => {
      const control = event.target;
      if (!isControl(control)) return;

      event.preventDefault();
      const field = control.closest(".field") ?? control.parentElement;
      if (!field || field.querySelector(errorSelector)) return;

      control.dataset.constraintAriaInvalid = control.getAttribute("aria-invalid") ?? "";
      control.setAttribute("aria-invalid", "true");

      const error = document.createElement("small");
      error.id = `constraint-error-${crypto.randomUUID()}`;
      error.className = "field-error constraint-error";
      error.dataset.constraintError = "true";
      error.setAttribute("role", "alert");
      error.textContent = control.validationMessage || "请填写此字段。";
      control.insertAdjacentElement("afterend", error);
      control.setAttribute(
        "aria-describedby",
        [control.getAttribute("aria-describedby"), error.id].filter(Boolean).join(" "),
      );
    };

    const clearIfValid = (event: Event) => {
      if (isControl(event.target) && event.target.validity.valid) clearError(event.target);
    };

    // ponytail: one capture listener covers every native form without duplicating React error state.
    document.addEventListener("invalid", showError, true);
    document.addEventListener("input", clearIfValid, true);
    document.addEventListener("change", clearIfValid, true);
    return () => {
      document.removeEventListener("invalid", showError, true);
      document.removeEventListener("input", clearIfValid, true);
      document.removeEventListener("change", clearIfValid, true);
    };
  }, []);

  return null;
}
