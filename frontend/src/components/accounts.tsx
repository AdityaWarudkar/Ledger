"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { allAccounts, ApiError, get, request } from "@/lib/api";
import type { Account, Currency, Entry, Page } from "@/lib/types";
import { date, money, shortId } from "@/lib/format";
import {
  Badge,
  Button,
  Copy,
  Empty,
  ErrorNotice,
  Field,
  Input,
  Loading,
  Modal,
  PageHeader,
  Pagination,
  Select,
  Table,
} from "./ui";
import { LedgerTable } from "./ledger-table";

export function Accounts() {
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [currency, setCurrency] = useState("");
  const query = useQuery({
    queryKey: ["accounts", "all"],
    queryFn: ({ signal }) => allAccounts(signal),
  });
  const filtered =
    query.data?.filter(
      (account) =>
        (!status || account.status === status) &&
        (!currency || account.currency === currency) &&
        `${account.name} ${account.id}`
          .toLowerCase()
          .includes(search.trim().toLowerCase()),
    ) || [];
  return (
    <>
      <PageHeader
        title="Accounts"
        description="Manage accounts and view balances."
        actions={
          <>
            <Button onClick={() => query.refetch()} disabled={query.isFetching}>
              Refresh
            </Button>
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create account
            </Button>
          </>
        }
      />
      <ErrorNotice
        error={query.error}
        retry={() => query.refetch()}
        stale={!!query.data}
      />
      <section className="panel">
        <div className="toolbar account-toolbar">
          <Input
            aria-label="Search accounts"
            placeholder="Search by name or account ID"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
          />
          <Select
            aria-label="Account status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="FROZEN">Frozen</option>
            <option value="CLOSED">Closed</option>
          </Select>
          <Select
            aria-label="Account currency"
            value={currency}
            onChange={(event) => {
              setCurrency(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All currencies</option>
            {["INR", "USD", "EUR", "GBP"].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </Select>
          <span className="count">
            {query.data ? `${filtered.length} accounts` : "Loading"}
          </span>
        </div>
        {query.isPending ? (
          <Loading />
        ) : (
          query.data &&
          (filtered.length ? (
            <Table caption="Accounts">
              <thead>
                <tr>
                  <th>Account</th>
                  <th>Currency</th>
                  <th className="amount">Balance</th>
                  <th>Status</th>
                  <th>Type</th>
                  <th>Created · UTC</th>
                </tr>
              </thead>
              <tbody>
                {filtered.slice(page * 25, (page + 1) * 25).map((account) => (
                  <tr key={account.id}>
                    <td>
                      <Link
                        className="table-link account-name"
                        href={`/accounts/${account.id}`}
                      >
                        {account.name}
                      </Link>
                      <code className="subline">{shortId(account.id)}</code>
                    </td>
                    <td>{account.currency}</td>
                    <td className="amount">
                      {money(account.balanceMinor, account.currency, false)}
                    </td>
                    <td>
                      <Badge value={account.status} />
                    </td>
                    <td>
                      <Badge value={account.kind} />
                    </td>
                    <td>{date(account.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ) : (
            <Empty title="No accounts found">
              {query.data.length
                ? "Try another search or clear the filters."
                : "Create an account to get started."}
            </Empty>
          ))
        )}
        {query.data && (
          <Pagination
            page={page}
            total={filtered.length}
            next={(page + 1) * 25 < filtered.length}
            onChange={setPage}
          />
        )}
      </section>
      {creating && <CreateAccount onClose={() => setCreating(false)} />}
    </>
  );
}

function CreateAccount({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const client = useQueryClient();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState<Currency>("INR");
  const mutation = useMutation({
    mutationFn: () =>
      request<Account>("/accounts", {
        method: "POST",
        body: JSON.stringify({ name: name.trim(), currency }),
      }),
    onSuccess: async ({ data }) => {
      await client.invalidateQueries({ queryKey: ["accounts"] });
      onClose();
      router.push(`/accounts/${data.id}`);
    },
  });
  const fields =
    mutation.error instanceof ApiError ? mutation.error.fields : {};
  return (
    <Modal title="Create account" onClose={onClose} busy={mutation.isPending}>
      <form
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          if (!mutation.isPending) mutation.mutate();
        }}
      >
        <div className="modal-body">
          <p className="muted">
            New merchant accounts start active, with a zero balance.
          </p>
          <ErrorNotice error={mutation.error} />
          <Field id="account-name" title="Account name" error={fields.name}>
            <Input
              id="account-name"
              aria-invalid={!!fields.name}
              aria-describedby={fields.name ? "account-name-error" : undefined}
              autoFocus
              required
              maxLength={120}
              value={name}
              onChange={(event) => setName(event.target.value)}
              disabled={mutation.isPending}
            />
          </Field>
          <Field
            id="currency"
            title="Currency"
            hint="Transfers require both accounts to use the same currency."
          >
            <Select
              id="currency"
              value={currency}
              onChange={(event) => setCurrency(event.target.value as Currency)}
              disabled={mutation.isPending}
            >
              {["INR", "USD", "EUR", "GBP"].map((value) => (
                <option key={value}>{value}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="modal-footer">
          <Button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={mutation.isPending || !name.trim()}
          >
            {mutation.isPending ? "Creating…" : "Create account"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

export function AccountDetail({ id }: { id: string }) {
  const [page, setPage] = useState(0);
  const account = useQuery({
    queryKey: ["account", id],
    queryFn: ({ signal }) => get<Account>(`/accounts/${id}`, signal),
  });
  const entries = useQuery({
    queryKey: ["entries", id, page],
    queryFn: ({ signal }) =>
      get<Page<Entry>>(`/accounts/${id}/entries?page=${page}&size=25`, signal),
    enabled: !!account.data,
  });
  return (
    <>
      <Link className="table-link" href="/accounts">
        ← Accounts
      </Link>
      <PageHeader
        title={account.data?.name || "Account details"}
        description="Account balance and transaction history."
        actions={account.data && <Badge value={account.data.status} />}
      />
      <ErrorNotice
        error={account.error}
        retry={() => account.refetch()}
        stale={!!account.data}
      />
      {account.isPending && <Loading rows={2} />}
      {account.data && (
        <>
          <section className="panel panel-body">
            <div className="balance-header">
              <div>
                <p className="eyebrow">Current balance</p>
                <p className="balance-value">
                  {money(account.data.balanceMinor, account.data.currency)}
                </p>
              </div>
              <Badge value={account.data.kind} />
            </div>
            <div className="metadata">
              <Copy value={id} />
              <span className="muted">
                Created {date(account.data.createdAt)} UTC
              </span>
            </div>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>Ledger entries</h2>
              <Button
                disabled={entries.isFetching}
                onClick={() => {
                  entries.refetch();
                  account.refetch();
                }}
              >
                Refresh
              </Button>
            </div>
            <ErrorNotice
              error={entries.error}
              retry={() => entries.refetch()}
              stale={!!entries.data}
            />
            {entries.isPending ? (
              <Loading />
            ) : (
              entries.data && <LedgerTable entries={entries.data.items} />
            )}
            {entries.data && (
              <Pagination
                page={page}
                total={entries.data.totalElements}
                next={page + 1 < entries.data.totalPages}
                onChange={setPage}
              />
            )}
          </section>
        </>
      )}
    </>
  );
}
