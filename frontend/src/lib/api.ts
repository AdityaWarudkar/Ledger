import type { Account, Page } from "./types";

export const API_URL = (
  process.env.NEXT_PUBLIC_API_URL || "http://127.0.0.1:8080"
).replace(/\/$/, "");
type Problem = {
  detail?: string;
  title?: string;
  code?: string;
  errors?: Record<string, string>;
};
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fields: Record<string, string> = {},
    public replayed: boolean | null = null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<{ data: T; status: number; replayed: boolean | null }> {
  let response: Response;
  try {
    response = await fetch(`${API_URL}/api${path}`, {
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...options.headers,
      },
      cache: "no-store",
      signal: options.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)])
        : AbortSignal.timeout(15000),
    });
  } catch {
    throw new ApiError(
      0,
      "Could not reach the ledger service. Check that the backend is running and try again.",
    );
  }
  const replayHeader = response.headers.get("Idempotency-Replayed");
  const replayed = replayHeader === null ? null : replayHeader === "true";
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const problem = (body || {}) as Problem;
    throw new ApiError(
      response.status,
      problem.detail ||
        problem.title ||
        `Request failed (HTTP ${response.status})`,
      problem.errors,
      replayed,
    );
  }
  if (body === null)
    throw new ApiError(
      response.status,
      "The service returned an empty or unreadable response.",
    );
  return { data: body as T, status: response.status, replayed };
}

export async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  return (await request<T>(path, { signal })).data;
}

export async function allAccounts(signal?: AbortSignal): Promise<Account[]> {
  const first = await get<Page<Account>>("/accounts?size=100", signal);
  const accounts = [...first.items];
  for (let page = 1; page < first.totalPages; page++) {
    accounts.push(
      ...(await get<Page<Account>>(`/accounts?size=100&page=${page}`, signal))
        .items,
    );
  }
  return accounts;
}
