"use client";
import { ErrorNotice } from "@/components/ui";
export default function ErrorPage({
  error,
  reset,
}: {
  error: Error;
  reset: () => void;
}) {
  return <ErrorNotice error={error} retry={reset} />;
}
