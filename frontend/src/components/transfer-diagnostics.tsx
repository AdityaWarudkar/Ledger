"use client";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { allAccounts, ApiError, get, request } from "@/lib/api";
import { money, parseAmount } from "@/lib/format";
import type {
  Currency,
  Transfer,
  TransferDetail,
  TransferRequest,
} from "@/lib/types";
import {
  Button,
  Copy,
  ErrorNotice,
  Field,
  Input,
  Loading,
  PageHeader,
  Select,
  Table,
} from "./ui";
import { LedgerTable } from "./ledger-table";

type Snapshot = { key: string; payload: TransferRequest; currency: Currency };
type Result = {
  status: number;
  replayed: boolean | null;
  id?: string;
  error?: string;
};
const storageKey = "ledger.transfer.diagnostics";
export function TransferDiagnostics() {
  const client = useQueryClient();
  const lock = useRef(false);
  const [ready, setReady] = useState(false);
  const [key, setKey] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [results, setResults] = useState<Result[]>([]);
  const [error, setError] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    try {
      const stored = sessionStorage.getItem(storageKey);
      if (stored) {
        const draft = JSON.parse(stored) as Snapshot;
        if (
          !draft.key ||
          !draft.payload?.fromAccountId ||
          !draft.payload.toAccountId ||
          !/^[1-9]\d*$/.test(draft.payload.amountMinor) ||
          !["INR", "USD", "EUR", "GBP"].includes(draft.currency)
        )
          throw new Error(
            "The saved request could not be read. Preserve this tab's session data before clearing it.",
          );
        setSnapshot(draft);
        setKey(draft.key);
        setFrom(draft.payload.fromAccountId);
        setTo(draft.payload.toAccountId);
        setAmount(
          `${BigInt(draft.payload.amountMinor) / 100n}.${(BigInt(draft.payload.amountMinor) % 100n).toString().padStart(2, "0")}`,
        );
      } else setKey(crypto.randomUUID());
      setReady(true);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause
          : new Error("Saved request unavailable."),
      );
    }
  }, []);
  const accounts = useQuery({
    queryKey: ["accounts", "all"],
    queryFn: ({ signal }) => allAccounts(signal),
  });
  const eligible =
    accounts.data?.filter(
      (account) => account.status === "ACTIVE" && account.kind === "MERCHANT",
    ) || [];
  const source = accounts.data?.find((account) => account.id === from);
  const transferId = results.find((result) => result.id)?.id;
  const proof = useQuery({
    queryKey: ["transfer", transferId],
    queryFn: ({ signal }) =>
      get<TransferDetail>(`/transfers/${transferId}`, signal),
    enabled: !!transferId,
  });
  const verified = !!(
    snapshot &&
    proof.data &&
    proof.data.entries.length === 2 &&
    results
      .filter((result) => result.id)
      .every((result) => result.id === proof.data.transfer.id) &&
    proof.data.entries.some(
      (entry) =>
        entry.accountId === snapshot.payload.fromAccountId &&
        entry.amountMinor === `-${snapshot.payload.amountMinor}`,
    ) &&
    proof.data.entries.some(
      (entry) =>
        entry.accountId === snapshot.payload.toAccountId &&
        entry.amountMinor === snapshot.payload.amountMinor,
    )
  );
  const canReset =
    results.some((result) => result.id) ||
    (results.length > 0 &&
      results.every((result) => [400, 404, 422].includes(result.status)));
  async function send(count: number) {
    if (lock.current || !ready) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      let draft = snapshot;
      if (!draft) {
        if (!source || !to || from === to)
          throw new Error(
            "Choose two different active accounts in the same currency.",
          );
        draft = {
          key,
          currency: source.currency,
          payload: {
            fromAccountId: from,
            toAccountId: to,
            amountMinor: parseAmount(amount),
          },
        };
        sessionStorage.setItem(storageKey, JSON.stringify(draft));
        setSnapshot(draft);
      }
      const saved = draft;
      const batch = await Promise.all(
        Array.from({ length: count }, async (): Promise<Result> => {
          try {
            const response = await request<Transfer>("/transfers", {
              method: "POST",
              headers: { "Idempotency-Key": saved.key },
              body: JSON.stringify(saved.payload),
            });
            return {
              status: response.status,
              replayed: response.replayed,
              id: response.data.id,
            };
          } catch (cause) {
            const failure =
              cause instanceof ApiError
                ? cause
                : new ApiError(
                    0,
                    "The outcome is unknown. Retry this saved request.",
                  );
            return {
              status: failure.status,
              replayed: failure.replayed,
              error: failure.message,
            };
          }
        }),
      );
      setResults((previous) => [...previous, ...batch]);
      await Promise.all(
        [
          "accounts",
          "account",
          "entries",
          "transfers",
          "transfer",
          "reconciliation",
        ].map((name) => client.invalidateQueries({ queryKey: [name] })),
      );
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause
          : new Error("Could not save the request. No request was sent."),
      );
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  function reset() {
    try {
      sessionStorage.removeItem(storageKey);
      setSnapshot(null);
      setResults([]);
      setAmount("");
      setKey(crypto.randomUUID());
      setError(null);
    } catch {
      setError(new Error("Could not clear the saved request."));
    }
  }
  return (
    <>
      <PageHeader
        title="Transfer requests"

        description="Inspect request replay behavior and journal entries."
      />
      <ErrorNotice error={accounts.error} retry={() => accounts.refetch()} />
      <ErrorNotice error={error} />
      <div className="form-layout">
        <section className="panel panel-body">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void send(1);
            }}
          >
            {accounts.isPending && <Loading rows={2} />}
            <Field
              id="from"
              title="From account"
              hint={
                source
                  ? `Available ${money(source.balanceMinor, source.currency)}`
                  : undefined
              }
            >
              <Select
                id="from"
                required
                value={from}
                disabled={!!snapshot || busy}
                onChange={(event) => {
                  setFrom(event.target.value);
                  setTo("");
                }}
              >
                <option value="">Choose an account</option>
                {eligible.map((account) => (
                  <option value={account.id} key={account.id}>
                    {account.name} · {account.currency}
                  </option>
                ))}
                {snapshot &&
                  !eligible.some((account) => account.id === from) && (
                    <option value={from}>{from}</option>
                  )}
              </Select>
            </Field>
            <Field id="to" title="To account">
              <Select
                id="to"
                required
                value={to}
                disabled={!!snapshot || busy || !source}
                onChange={(event) => setTo(event.target.value)}
              >
                <option value="">Choose a recipient</option>
                {eligible
                  .filter(
                    (account) =>
                      account.currency === source?.currency &&
                      account.id !== from,
                  )
                  .map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
                {snapshot && !eligible.some((account) => account.id === to) && (
                  <option value={to}>{to}</option>
                )}
              </Select>
            </Field>
            <Field
              id="amount"
              title={`Amount${snapshot?.currency || source?.currency ? ` · ${snapshot?.currency || source?.currency}` : ""}`}
              hint="Use up to two decimal places."
            >
              <Input
                id="amount"
                required
                inputMode="decimal"
                placeholder="0.00"
                value={amount}
                disabled={!!snapshot || busy}
                onChange={(event) => setAmount(event.target.value)}
              />
            </Field>
            <div className="key-box">
              <p className="eyebrow">Idempotency key</p>
              {key ? (
                <Copy value={key} />
              ) : (
                <span className="muted">Preparing request…</span>
              )}
              <p className="muted">
                {snapshot
                  ? "This key and payload are locked for safe retries."
                  : "Generated for this transfer. Both parallel requests use this key."}
              </p>
            </div>
            <div className="actions">
              <Button variant="primary" disabled={!ready || busy}>
                {busy
                  ? "Sending…"
                  : snapshot
                    ? "Send same request again"
                    : "Send transfer"}
              </Button>
              <Button
                type="button"
                disabled={
                  !ready || busy || (!snapshot && (!from || !to || !amount))
                }
                onClick={() => send(2)}
              >
                Send twice in parallel
              </Button>
            </div>
            {snapshot && (
              <p className="muted mt-4">
                The request is saved in this tab. If the connection fails, retry
                it here to resolve the outcome.
              </p>
            )}
            {canReset && (
              <Button
                type="button"
                variant="quiet"
                disabled={busy}
                onClick={reset}
              >
                Start a new transfer
              </Button>
            )}
          </form>
        </section>
      </div>
      {results.length > 0 && (
        <section className="panel result-section">
          <div className="panel-heading">
            <h2>Request results</h2>
            <span className="muted">{results.length} responses</span>
          </div>
          <Table caption="Request results">
            <thead>
              <tr>
                <th>Request</th>
                <th>HTTP status</th>
                <th>Replayed</th>
                <th>Transfer / outcome</th>
              </tr>
            </thead>
            <tbody>
              {results.map((result, index) => (
                <tr key={index}>
                  <td>#{index + 1}</td>
                  <td>{result.status || "Unknown"}</td>
                  <td>
                    {result.replayed === null
                      ? "Not reported"
                      : result.replayed
                        ? "Yes"
                        : "No"}
                  </td>
                  <td>
                    {result.id ? (
                      <Copy value={result.id} compact />
                    ) : (
                      result.error
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
        </section>
      )}
      {transferId && (
        <section className="panel">
          <div className="panel-heading">
            <h2>Ledger verification</h2>
          </div>
          <ErrorNotice error={proof.error} retry={() => proof.refetch()} />
          {proof.isPending ? (
            <Loading rows={2} />
          ) : (
            proof.data && (
              <>
                <div
                  className={`notice ${verified ? "notice-success" : "notice-error"}`}
                  role="status"
                >
                  {verified
                    ? "Verified: one transfer, two balanced ledger entries."
                    : "The returned journal does not match this request. Inspect the entries before continuing."}
                </div>
                <LedgerTable entries={proof.data.entries} />
              </>
            )
          )}
        </section>
      )}
    </>
  );
}
