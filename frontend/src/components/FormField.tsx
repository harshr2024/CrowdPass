import type { InputHTMLAttributes } from "react";

interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
}

export function FormField({
  label,
  error,
  hint,
  id,
  ...props
}: FormFieldProps) {
  const inputId = id ?? props.name;
  const descriptionId = `${inputId}-description`;
  return (
    <label className="form-field" htmlFor={inputId}>
      <span className="form-field__label">{label}</span>
      <input
        id={inputId}
        className="form-field__input"
        aria-invalid={Boolean(error)}
        aria-describedby={error || hint ? descriptionId : undefined}
        {...props}
      />
      {error ? (
        <span id={descriptionId} className="form-field__error" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span id={descriptionId} className="form-field__hint">
          {hint}
        </span>
      ) : null}
    </label>
  );
}
