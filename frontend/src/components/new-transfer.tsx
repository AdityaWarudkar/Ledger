"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { allAccounts, ApiError, request } from "@/lib/api";
import { date, money, parseAmount, shortId } from "@/lib/format";
import type { Currency, Transfer, TransferRequest } from "@/lib/types";
import {
  Badge,
  Button,
  Copy,
  ErrorNotice,
  Field,
  Input,
  Loading,
  PageHeader,
  Select,
} from "./ui";

type Draft = {
  key: string;
  payload: TransferRequest;
  currency: Currency;
  receipt?: Transfer;
};
const storageKey = "ledger.transfer.draft";

export function NewTransfer() {
  const client = useQueryClient();
  const sending = useRef(false);
  const [ready, setReady] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [amount, setAmount] = useState("");
  const [review, setReview] = useState<Draft | null>(null);
  const [submitted, setSubmitted] = useState<Draft | null>(null);
  const [receipt, setReceipt] = useState<Transfer | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const accounts = useQuery({
    queryKey: ["accounts", "all"],
    queryFn: ({ signal }) => allAccounts(signal),
  });
  const eligible =
    accounts.data?.filter(
      (account) => account.status === "ACTIVE" && account.kind === "MERCHANT",
    ) || [];
  const source = accounts.data?.find((account) => account.id === from);
  const recipient = accounts.data?.find((account) => account.id === to);
  const currency = submitted?.currency || review?.currency || source?.currency;
  const name = (id: string) =>
    accounts.data?.find((account) => account.id === id)?.name || shortId(id);

  useEffect(() => {
    if (review || submitted || receipt)
      document.querySelector<HTMLElement>(".transfer-page h1")?.focus();
  }, [review, submitted, receipt]);

  useEffect(() => {
    try {
      const stored = sessionStorage.getItem(storageKey);
      if (stored) {
        const draft = JSON.parse(stored) as Draft;
        if (
          !draft.key ||
          !draft.payload?.fromAccountId ||
          !draft.payload.toAccountId ||
          !/^[1-9]\d*$/.test(draft.payload.amountMinor) ||
          !["INR", "USD", "EUR", "GBP"].includes(draft.currency)
        )
          throw new Error(
            "The saved transfer could not be loaded. Keep this tab open and contact your administrator.",
          );
        setSubmitted(draft);
        setFrom(draft.payload.fromAccountId);
        setTo(draft.payload.toAccountId);
        setReceipt(draft.receipt || null);
      }
      setReady(true);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause
          : new Error("Saved transfer unavailable."),
      );
    }
  }, []);

  function reviewTransfer() {
    const errors: Record<string, string> = {};
    if (!source || !eligible.some((account) => account.id === from))
      errors.from = "Select an active account.";
    if (
      !recipient ||
      recipient.id === from ||
      recipient.currency !== source?.currency ||
      !eligible.some((account) => account.id === to)
    )
      errors.to = "Select a recipient in the same currency.";
    let minor = "";
    try {
      minor = parseAmount(amount);
      if (source && BigInt(minor) > BigInt(source.balanceMinor))
        errors.amount = "Amount exceeds the available balance.";
    } catch (cause) {
      errors.amount = (cause as Error).message;
    }
    setFields(errors);
    if (Object.keys(errors).length) {
      document.getElementById(Object.keys(errors)[0])?.focus();
      return;
    }
    setReview({
      key: crypto.randomUUID(),
      currency: source!.currency,
      payload: { fromAccountId: from, toAccountId: to, amountMinor: minor },
    });
    setError(null);
  }

  async function confirmTransfer() {
    const draft = submitted || review;
    if (!draft || sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    setError(null);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(draft));
      setSubmitted(draft);
      let transfer: Transfer;
      try {
        transfer = (
          await request<Transfer>("/transfers", {
            method: "POST",
            headers: { "Idempotency-Key": draft.key },
            body: JSON.stringify(draft.payload),
          })
        ).data;
      } catch (cause) {
        setFailure(
          cause instanceof ApiError
            ? cause
            : new ApiError(0, "Unable to confirm the transfer status."),
        );
        return;
      }
      setReceipt(transfer);
      try {
        sessionStorage.setItem(
          storageKey,
          JSON.stringify({ ...draft, receipt: transfer }),
        );
      } catch {
        setError(
          new Error(
            "Transfer completed, but this browser could not save its receipt. You can find it in Transfers.",
          ),
        );
      }
      await Promise.all(
        ["accounts", "account", "entries", "transfers", "reconciliation"].map(
          (key) => client.invalidateQueries({ queryKey: [key] }),
        ),
      );
    } catch {
      setError(
        new Error(
          "This browser could not save the transfer. No new request was sent. Enable session storage and try again.",
        ),
      );
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }
  function startNew() {
    try {
      sessionStorage.removeItem(storageKey);
      setSubmitted(null);
      setReview(null);
      setReceipt(null);
      setFailure(null);
      setError(null);
      setFields({});
      setAmount("");
    } catch {
      setError(new Error("Unable to clear the saved transfer."));
    }
  }
  const rejected = failure && [400, 404, 422].includes(failure.status);
  const draft = submitted || review;
  return (
    <div className="transfer-page">
      <Link href="/transfers" className="back-link">
        ← Transfers
      </Link>
      <PageHeader
        title={
          receipt
            ? "Transfer completed"
            : submitted
              ? rejected
                ? "Transfer declined"
                : "Transfer status"
              : review
                ? "Review transfer"
                : "New transfer"
        }
        description={
          receipt
            ? "The funds have been transferred to the recipient account."
            : submitted
              ? "Review the status of your submitted transfer."
              : review
                ? "Check the details before confirming."
                : "Transfer funds between accounts in the same currency."
        }
      />
      <ErrorNotice error={error} />
      {!submitted && (
        <ol className="transfer-steps" aria-label="Transfer progress">
          <li aria-current={!review ? "step" : undefined}>
            <span>1</span>Details
          </li>
          <li aria-current={review ? "step" : undefined}>
            <span>2</span>Review & confirm
          </li>
        </ol>
      )}
      {receipt ? (
        <section className="transfer-receipt">
          <div className="receipt-heading">
            <Badge value="COMPLETED" />
            <p className="receipt-amount">
              {money(receipt.amountMinor, receipt.currency)}
            </p>
          </div>
          <dl className="review-list">
            <div>
              <dt>From</dt>
              <dd>{name(receipt.fromAccountId)}</dd>
            </div>
            <div>
              <dt>To</dt>
              <dd>{name(receipt.toAccountId)}</dd>
            </div>
            <div>
              <dt>Completed · UTC</dt>
              <dd>{date(receipt.createdAt)}</dd>
            </div>
            <div>
              <dt>Transfer reference</dt>
              <dd>
                <Copy value={receipt.id} />
              </dd>
            </div>
          </dl>
          <div className="transfer-footer">
            <Link
              className="button button-primary"
              href={`/transfers/${receipt.id}`}
            >
              View transfer
            </Link>
            <Button onClick={startNew}>New transfer</Button>
          </div>
        </section>
      ) : draft ? (
        <section className="transfer-receipt">
          <div className="receipt-heading">
            <p className="muted">Transfer amount</p>
            <p className="receipt-amount">
              {money(draft.payload.amountMinor, draft.currency)}
            </p>
          </div>
          <dl className="review-list">
            <div>
              <dt>From account</dt>
              <dd>
                {name(draft.payload.fromAccountId)}
                <code>{draft.payload.fromAccountId}</code>
              </dd>
            </div>
            <div>
              <dt>To account</dt>
              <dd>
                {name(draft.payload.toAccountId)}
                <code>{draft.payload.toAccountId}</code>
              </dd>
            </div>
            <div>
              <dt>Currency</dt>
              <dd>{draft.currency}</dd>
            </div>
          </dl>
          {submitted && !busy && (
            <div
              className={`transfer-message ${rejected ? "transfer-message-error" : ""}`}
              role="status"
            >
              <strong>
                {rejected
                  ? "Transfer was not completed"
                  : "Confirmation required"}
              </strong>
              <p>
                {failure?.message ||
                  "This transfer was previously submitted. Check its status before starting another transfer."}
              </p>
              {!rejected && (
                <p>
                  Checking will safely retry this transfer without sending the
                  funds twice.
                </p>
              )}
            </div>
          )}
          <div className="transfer-footer">
            {rejected ? (
              <Button variant="primary" onClick={startNew}>
                Start a new transfer
              </Button>
            ) : (
              <Button
                variant="primary"
                disabled={busy || !ready}
                onClick={confirmTransfer}
              >
                {busy
                  ? "Processing transfer…"
                  : submitted
                    ? "Check transfer status"
                    : `Confirm ${money(draft.payload.amountMinor, draft.currency)}`}
              </Button>
            )}
            {!submitted && (
              <Button disabled={busy} onClick={() => setReview(null)}>
                Edit details
              </Button>
            )}
          </div>
        </section>
      ) : (
        <div className="transfer-layout">
          <section className="transfer-form">
            <div className="section-title">
              <h2>Transfer details</h2>
            </div>
            <ErrorNotice
              error={accounts.error}
              retry={() => accounts.refetch()}
            />
            {accounts.isPending ? (
              <Loading rows={3} />
            ) : (
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  reviewTransfer();
                }}
              >
                <div className="transfer-fields">
                  <Field id="from" title="From account" error={fields.from}>
                    <Select
                      id="from"
                      value={from}
                      aria-invalid={!!fields.from}
                      aria-describedby={fields.from ? "from-error" : undefined}
                      onChange={(event) => {
                        setFrom(event.target.value);
                        setTo("");
                        setFields({});
                      }}
                    >
                      <option value="">Select account</option>
                      {eligible.map((account) => (
                        <option key={account.id} value={account.id}>
                          {account.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  {source && (
                    <div className="available-balance">
                      <span>Available balance</span>
                      <strong>
                        {money(source.balanceMinor, source.currency)}
                      </strong>
                    </div>
                  )}
                  <Field id="to" title="To account" error={fields.to}>
                    <Select
                      id="to"
                      disabled={!source}
                      value={to}
                      aria-invalid={!!fields.to}
                      aria-describedby={fields.to ? "to-error" : undefined}
                      onChange={(event) => setTo(event.target.value)}
                    >
                      <option value="">Select recipient</option>
                      {eligible
                        .filter(
                          (account) =>
                            account.id !== from &&
                            account.currency === source?.currency,
                        )
                        .map((account) => (
                          <option key={account.id} value={account.id}>
                            {account.name}
                          </option>
                        ))}
                    </Select>
                  </Field>
                  <Field id="amount" title="Amount" error={fields.amount}>
                    <div className="amount-input">
                      <span>{currency || "—"}</span>
                      <Input
                        id="amount"
                        inputMode="decimal"
                        autoComplete="off"
                        placeholder="0.00"
                        value={amount}
                        aria-invalid={!!fields.amount}
                        aria-describedby={
                          fields.amount ? "amount-error" : undefined
                        }
                        onChange={(event) => setAmount(event.target.value)}
                      />
                    </div>
                  </Field>
                </div>
                <div className="transfer-footer">
                  <Button variant="primary" disabled={!ready || !accounts.data}>
                    Review transfer
                  </Button>
                  <Link className="button button-quiet" href="/transfers">
                    Cancel
                  </Link>
                </div>
              </form>
            )}
          </section>
          <aside className="transfer-summary">
            <h2>Account information</h2>
            <dl>
              <dt>Sending account</dt>
              <dd>{source?.name || "No account selected"}</dd>
              {source && (
                <>
                  <dt>Account reference</dt>
                  <dd>
                    <code>{source.id}</code>
                  </dd>
                  <dt>Available balance</dt>
                  <dd className="mono">
                    {money(source.balanceMinor, source.currency)}
                  </dd>
                  <dt>Status</dt>
                  <dd>
                    <Badge value={source.status} />
                  </dd>
                </>
              )}
            </dl>
            {source && (
              <Link className="table-link" href={`/accounts/${source.id}`}>
                View account →
              </Link>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}
