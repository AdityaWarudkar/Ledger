"use client";
import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { allAccounts, get } from "@/lib/api";
import { date, money, shortId } from "@/lib/format";
import type { Page, Transfer, TransferDetail as Detail } from "@/lib/types";
import {
  Badge,
  Button,
  Copy,
  Empty,
  ErrorNotice,
  Loading,
  PageHeader,
  Pagination,
  Select,
  Table,
} from "./ui";
import { LedgerTable } from "./ledger-table";

export function Transfers() {
  const [page, setPage] = useState(0);
  const [accountId, setAccountId] = useState("");
  const [kind, setKind] = useState("");
  const accounts = useQuery({
    queryKey: ["accounts", "all"],
    queryFn: ({ signal }) => allAccounts(signal),
  });
  const query = useQuery({
    queryKey: ["transfers", page, accountId, kind],
    queryFn: ({ signal }) =>
      get<Page<Transfer>>(
        `/transfers?${new URLSearchParams({ page: String(page), size: "25", ...(accountId && { accountId }), ...(kind && { kind }) })}`,
        signal,
      ),
  });
  const name = (id: string) =>
    accounts.data?.find((account) => account.id === id)?.name || shortId(id);
  return (
    <>
      <PageHeader
        title="Transfers"
        eyebrow="Activity"
        description="Completed transfers and the entries behind them."
        actions={
          <Link className="button button-primary" href="/transfers/new">
            New transfer
          </Link>
        }
      />
      <ErrorNotice
        error={query.error}
        retry={() => query.refetch()}
        stale={!!query.data}
      />
      <ErrorNotice error={accounts.error} retry={() => accounts.refetch()} />
      <section className="panel">
        <div className="toolbar">
          <Select
            aria-label="Filter by account"
            value={accountId}
            onChange={(event) => {
              setAccountId(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All accounts</option>
            {accounts.data?.map((account) => (
              <option key={account.id} value={account.id}>
                {account.name} · {account.currency}
              </option>
            ))}
          </Select>
          <Select
            aria-label="Filter by type"
            value={kind}
            onChange={(event) => {
              setKind(event.target.value);
              setPage(0);
            }}
          >
            <option value="">All types</option>
            <option value="TRANSFER">Transfer</option>
            <option value="FUNDING">Opening funding</option>
          </Select>
          <Button onClick={() => query.refetch()} disabled={query.isFetching}>
            Refresh
          </Button>
        </div>
        {query.isPending ? (
          <Loading />
        ) : (
          query.data &&
          (query.data.items.length ? (
            <Table caption="Transfers">
              <thead>
                <tr>
                  <th>Transfer / posted · UTC</th>
                  <th>From</th>
                  <th>To</th>
                  <th className="amount">Amount</th>
                  <th>Type</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((transfer) => (
                  <tr key={transfer.id}>
                    <td>
                      <Link
                        className="table-link"
                        href={`/transfers/${transfer.id}`}
                      >
                        <code>{shortId(transfer.id)}</code>
                      </Link>
                      <span className="subline">
                        {date(transfer.createdAt)}
                      </span>
                    </td>
                    <td>{name(transfer.fromAccountId)}</td>
                    <td>{name(transfer.toAccountId)}</td>
                    <td className="amount">
                      {money(transfer.amountMinor, transfer.currency)}
                    </td>
                    <td>
                      <Badge value={transfer.kind} />
                    </td>
                    <td>
                      <Badge value={transfer.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ) : (
            <Empty title="No transfers found">
              Try a different filter or create a transfer.
            </Empty>
          ))
        )}
        {query.data && (
          <Pagination
            page={page}
            total={query.data.totalElements}
            next={page + 1 < query.data.totalPages}
            onChange={setPage}
          />
        )}
      </section>
    </>
  );
}
export function TransferDetail({ id }: { id: string }) {
  const query = useQuery({
    queryKey: ["transfer", id],
    queryFn: ({ signal }) => get<Detail>(`/transfers/${id}`, signal),
  });
  const transfer = query.data?.transfer;
  return (
    <>
      <Link className="table-link" href="/transfers">
        ← Transfers
      </Link>
      <PageHeader
        title="Transfer details"
        description="A transfer and its immutable journal."
        actions={transfer && <Badge value={transfer.status} />}
      />
      <ErrorNotice error={query.error} retry={() => query.refetch()} />
      {query.isPending && <Loading />}
      {transfer && (
        <>
          <section className="panel panel-body">
            <p className="balance-value">
              {money(transfer.amountMinor, transfer.currency)}
            </p>
            <Copy value={transfer.id} />
            <dl className="definition-grid">
              <div>
                <dt>From account</dt>
                <dd>
                  <Link
                    className="table-link"
                    href={`/accounts/${transfer.fromAccountId}`}
                  >
                    {transfer.fromAccountId}
                  </Link>
                </dd>
              </div>
              <div>
                <dt>To account</dt>
                <dd>
                  <Link
                    className="table-link"
                    href={`/accounts/${transfer.toAccountId}`}
                  >
                    {transfer.toAccountId}
                  </Link>
                </dd>
              </div>
              <div>
                <dt>Posted · UTC</dt>
                <dd>{date(transfer.createdAt)}</dd>
              </div>
              <div>
                <dt>Type</dt>
                <dd>
                  <Badge value={transfer.kind} />
                </dd>
              </div>
            </dl>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>Journal entries</h2>
              <span className="muted">
                {query.data!.entries.length} entries
              </span>
            </div>
            <LedgerTable entries={query.data!.entries} />
          </section>
        </>
      )}
    </>
  );
}
