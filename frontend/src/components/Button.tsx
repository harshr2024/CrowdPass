import type { ButtonHTMLAttributes, ReactNode } from "react";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "quiet" | "danger";
  pending?: boolean;
  children: ReactNode;
}

export function Button({
  variant = "primary",
  pending = false,
  children,
  className = "",
  ...props
}: ButtonProps) {
  return (
    <button
      className={`button button--${variant} ${className}`}
      disabled={pending || props.disabled}
      aria-busy={pending}
      {...props}
    >
      {pending ? <span className="button__spinner" aria-hidden="true" /> : null}
      <span>{pending ? "Working…" : children}</span>
    </button>
  );
}
