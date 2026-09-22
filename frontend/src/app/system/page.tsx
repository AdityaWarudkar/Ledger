"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { get } from "@/lib/api";
import type { Reconciliation } from "@/lib/types";
import { date, money } from "@/lib/format";
import {
  Badge,
  Button,
  ErrorNotice,
  Loading,
  PageHeader,
  Table,
} from "@/components/ui";
export default function Page() {
  const query = useQuery({
    queryKey: ["reconciliation"],
    queryFn: ({ signal }) =>
      get<Reconciliation>("/system/reconciliation", signal),
    staleTime: 0,
  });
  return (
    <>
      <PageHeader
        title="System"
        eyebrow="Ledger integrity"
        description="Compare account balances with the immutable journal."
        actions={
          <Button disabled={query.isFetching} onClick={() => query.refetch()}>
            {query.isFetching ? "Checking…" : "Run reconciliation"}
          </Button>
        }
      />
      <ErrorNotice
        error={query.error}
        retry={() => query.refetch()}
        stale={!!query.data}
      />
      {query.isPending && <Loading />}
      {query.data && (
        <>
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Reconciliation</h2>
                <p className="muted">
                  Checked {date(query.data.checkedAt)} UTC
                </p>
              </div>
              <Badge value={query.data.balanced ? "BALANCED" : "FAILED"} />
            </div>
            <Table caption="Currency reconciliation">
              <thead>
                <tr>
                  <th>Currency</th>
                  <th>Ledger entries</th>
                  <th className="amount">Net ledger amount</th>
                </tr>
              </thead>
              <tbody>
                {query.data.currencies.map((row) => (
                  <tr key={row.currency}>
                    <td>{row.currency}</td>
                    <td>{row.entryCount}</td>
                    <td className="amount">
                      {money(row.netMinor, row.currency)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <div className="panel-body">
              <p className="muted">
                {query.data.balanced
                  ? "All account balances match their journal totals. Entries net to zero in each currency."
                  : "The ledger check found a discrepancy. Review the currency totals and account mismatches."}
              </p>
            </div>
          </section>
          {query.data.mismatches.length > 0 && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Account mismatches</h2>
              </div>
              <Table caption="Account mismatches">
                <thead>
                  <tr>
                    <th>Account</th>
                    <th className="amount">Stored balance</th>
                    <th className="amount">Journal total</th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.mismatches.map((row) => (
                    <tr key={row.accountId}>
                      <td>
                        <Link
                          className="table-link"
                          href={`/accounts/${row.accountId}`}
                        >
                          {row.accountId}
                        </Link>
                      </td>
                      <td className="amount">
                        {money(row.balanceMinor, row.currency)}
                      </td>
                      <td className="amount">
                        {money(row.ledgerBalanceMinor, row.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </section>
          )}
        </>
      )}
    </>
  );
}
