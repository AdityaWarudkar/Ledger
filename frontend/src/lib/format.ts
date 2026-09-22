import type { Currency } from "./types";

export function money(
  minor: string,
  currency: Currency,
  includeCurrency = true,
): string {
  const value = BigInt(minor);
  const absolute = value < 0n ? -value : value;
  const whole = new Intl.NumberFormat(
    currency === "INR" ? "en-IN" : "en-US",
  ).format(absolute / 100n);
  return `${value < 0n ? "−" : ""}${includeCurrency ? `${currency} ` : ""}${whole}.${(absolute % 100n).toString().padStart(2, "0")}`;
}

export function parseAmount(input: string): string {
  const value = input.trim();
  if (!/^(0|[1-9]\d*)(\.\d{1,2})?$/.test(value))
    throw new Error(
      "Use an amount such as 125.00, with at most two decimal places.",
    );
  const [whole, fraction = ""] = value.split(".");
  const minor = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  if (minor <= 0n) throw new Error("Amount must be greater than zero.");
  if (minor > 9223372036854775807n)
    throw new Error("Amount exceeds the supported limit.");
  return minor.toString();
}

export function date(value: string | null): string {
  return value
    ? new Intl.DateTimeFormat("en-GB", {
        day: "2-digit",
        month: "short",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        timeZone: "UTC",
      }).format(new Date(value))
    : "—";
}
export function shortId(id: string): string {
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-6)}` : id;
}
export function label(value: string): string {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (letter) => letter.toUpperCase());
}
