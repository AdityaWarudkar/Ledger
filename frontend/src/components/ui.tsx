"use client";
import {
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
} from "react";
import { label, shortId } from "@/lib/format";

export function Button({
  variant = "secondary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "quiet";
}) {
  return (
    <button {...props} className={`button button-${variant} ${className}`} />
  );
}
export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`input ${props.className || ""}`} />;
}
export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={`input ${props.className || ""}`} />;
}
export function Field({
  id,
  title,
  error,
  hint,
  children,
}: {
  id: string;
  title: string;
  error?: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{title}</label>
      {children}
      {error && (
        <p id={`${id}-error`} className="field-error" role="alert">
          {error}
        </p>
      )}
      {hint && <p className="muted text-xs">{hint}</p>}
    </div>
  );
}
export function Badge({ value }: { value: string }) {
  const tone = [
    "ACTIVE",
    "COMPLETED",
    "DELIVERED",
    "HEALTHY",
    "BALANCED",
  ].includes(value)
    ? "good"
    : ["DEAD_LETTER", "FAIL", "FAILED"].includes(value)
      ? "bad"
      : "neutral";
  return <span className={`badge badge-${tone}`}>{label(value)}</span>;
}
export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
  eyebrow?: string;
}) {
  return (
    <header className="page-heading">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1 tabIndex={-1}>{title}</h1>
        <p className="muted">{description}</p>
      </div>
      <div className="actions">{actions}</div>
    </header>
  );
}
export function ErrorNotice({
  error,
  retry,
  stale = false,
}: {
  error: Error | null;
  retry?: () => void;
  stale?: boolean;
}) {
  if (!error) return null;
  return (
    <div className="notice notice-error" role="alert">
      <div>
        <strong>{error.message}</strong>
        {stale && <p>Showing previously loaded data.</p>}
      </div>
      {retry && <Button onClick={retry}>Retry</Button>}
    </div>
  );
}
export function Empty({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      <p className="muted">{children}</p>
    </div>
  );
}
export function Loading({ rows = 5 }: { rows?: number }) {
  return (
    <div className="skeleton-table" role="status" aria-label="Loading data">
      <span className="sr-only">Loading data</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-row">
          <span />
          <span />
          <span />
        </div>
      ))}
    </div>
  );
}
export function Table({
  children,
  caption,
}: {
  children: ReactNode;
  caption: string;
}) {
  return (
    <div
      className="table-scroll"
      tabIndex={0}
      role="region"
      aria-label={caption}
    >
      <table>
        <caption className="sr-only">{caption}</caption>
        {children}
      </table>
    </div>
  );
}
export function Pagination({
  page,
  next,
  onChange,
  total,
  size = 25,
}: {
  page: number;
  next: boolean;
  onChange: (page: number) => void;
  total?: number;
  size?: number;
}) {
  return (
    <div className="pagination">
      <span className="muted">
        {total !== undefined
          ? total === 0
            ? "0 results"
            : `Showing ${page * size + 1}–${Math.min((page + 1) * size, total)} of ${total}`
          : `Page ${page + 1}`}
      </span>
      <div className="actions">
        <Button disabled={page === 0} onClick={() => onChange(page - 1)}>
          Previous
        </Button>
        <Button disabled={!next} onClick={() => onChange(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
export function Copy({
  value,
  compact = false,
}: {
  value: string;
  compact?: boolean;
}) {
  const [message, setMessage] = useState("");
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(""), 2500);
    return () => clearTimeout(timer);
  }, [message]);
  return (
    <span className="copy-group">
      <code title={value}>{compact ? shortId(value) : value}</code>
      <button
        type="button"
        className="copy-button"
        aria-label={`Copy ${value}`}
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(value);
            setMessage("Copied");
          } catch {
            setMessage("Copy unavailable; select the value");
          }
        }}
      >
        {message || "Copy"}
      </button>
      <span className="sr-only" role="status">
        {message}
      </span>
    </span>
  );
}
export function Modal({
  title,
  children,
  onClose,
  busy = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  busy?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const headingId = useId();
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current!;
    dialog.showModal();
    dialog
      .querySelector<HTMLElement>("input:not(:disabled), select:not(:disabled)")
      ?.focus();
    return () => {
      dialog.close();
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby={headingId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(event) => {
        if (event.target === ref.current && !busy) {
          const rect = ref.current!.getBoundingClientRect();
          if (
            event.clientX < rect.left ||
            event.clientX > rect.right ||
            event.clientY < rect.top ||
            event.clientY > rect.bottom
          )
            onClose();
        }
      }}
    >
      <div className="modal-heading">
        <h2 id={headingId}>{title}</h2>
        <Button
          variant="quiet"
          aria-label="Close dialog"
          disabled={busy}
          onClick={onClose}
        >
          Close
        </Button>
      </div>
      {children}
    </dialog>
  );
}
