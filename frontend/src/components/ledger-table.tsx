import Link from "next/link";
import type { Entry } from "@/lib/types";
import { date, money, shortId } from "@/lib/format";
import { Badge, Empty, Table } from "./ui";

export function LedgerTable({ entries }: { entries: Entry[] }) {
  if (!entries.length)
    return (
      <Empty title="No ledger entries yet">
        Completed transfers will appear here.
      </Empty>
    );
  return (
    <Table caption="Ledger entries">
      <thead>
        <tr>
          <th>Posted · UTC</th>
          <th>Entry / transfer</th>
          <th>Account / counterparty</th>
          <th>Direction</th>
          <th className="amount">Amount</th>
          <th className="amount">Running balance</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((entry) => (
          <tr key={entry.id}>
            <td>{date(entry.createdAt)}</td>
            <td>
              <code>#{entry.id}</code>
              <Link
                className="subline table-link"
                href={`/transfers/${entry.transferId}`}
              >
                {shortId(entry.transferId)}
              </Link>
            </td>
            <td>
              <Link
                className="table-link"
                href={`/accounts/${entry.accountId}`}
              >
                {shortId(entry.accountId)}
              </Link>
              <span className="subline">{entry.counterpartyName}</span>
            </td>
            <td>
              <Badge value={entry.direction} />
            </td>
            <td className="amount">
              {money(entry.amountMinor, entry.currency)}
            </td>
            <td className="amount">
              {money(entry.runningBalanceMinor, entry.currency)}
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
