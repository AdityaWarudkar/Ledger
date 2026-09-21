# Dashboard design proposal

Status: awaiting approval before frontend implementation.

The dashboard is a working ledger console. Most of the screen belongs to rows,
amounts, and useful actions. A fixed sidebar supplies context; the main area
contains a title, a short explanation where necessary, and the relevant table
or form. No promotional sections, decorative statistics, or stock imagery.

## Design tokens

| Token | Value | Use |
| --- | --- | --- |
| Canvas | `#F7F8F7` | Application background |
| Surface | `#FFFFFF` | Tables, forms, dialogs |
| Text | `#202724` | Primary text |
| Secondary text | `#59635E` | Labels and supporting text |
| Border | `#DEE3DF` | 1px dividers |
| Accent | `#176B55` | Primary actions, links, focus rings |
| Accent surface | `#EAF3EE` | Selected navigation |
| Error | `#A12E2E` | Errors and failed status only |

Use the system sans-serif stack, with 12px metadata, 14px body and table text,
16px section labels, 24px page titles, and 32px account balances. IDs, keys,
and amounts use the system monospace stack and tabular numerals. Amounts align
right. Status labels always include text and do not rely on color alone.

Spacing uses 4, 8, 12, 16, 24, and 32px. Border radii are 4px for controls and
8px for tables/dialogs. The sidebar is 224px wide, content padding is 32px,
table rows are approximately 48px, and controls are at least 40px high.
No heavy shadows; transitions are limited to hover/focus at around 120ms.

## Shared shell

```text
+---------------------+----------------------------------------------------+
| Ledger              | Local workspace                                    |
| Operations          +----------------------------------------------------+
|                     | Page title                         Primary action  |
| Accounts            | Supporting context                                |
| New transfer        |                                                    |
| Transfers           | Table or focused form                              |
| Webhooks            |                                                    |
| System              |                                                    |
|                     |                                                    |
| Local environment   |                                                    |
+---------------------+----------------------------------------------------+
```

Phase 6 enables Accounts and Account detail. The remaining screens below are
planned for Phase 7 (System is optional). Navigation will only link to working
routes; planned destinations will be labeled as upcoming until implemented.
The environment label is descriptive, not a fabricated health indicator.

## Accounts

```text
Accounts                                        [Refresh] [Create account]
Manage balances and inspect the ledger.

Name                  Currency    Balance          Status       Kind
Northstar Commerce    INR         INR 24,875.00     Active       Merchant
Monsoon Supply Co.    INR         INR    125.00     Active       Merchant
Local funding...      INR        -INR 25,000.00     Active       Clearing
-----------------------------------------------------------------------
Showing 1–5 of 5                                  [Previous] [Next]
```

The names link to account detail. Sample amounts above illustrate formatting;
the implementation uses API data exclusively. No combined balance across
different currencies. Long IDs are shortened visibly, with a copy action and
the full value available accessibly. Clearing accounts have a clear text label.

Create-account dialog: name field, currency select, inline field errors, Cancel,
and Create account. Explain that accounts start at zero. Successful creation
refreshes the account list and opens the new account. Focus stays inside the
dialog, Escape closes it when idle, and focus returns to its trigger.

## Account detail

```text
Accounts / Northstar Commerce
Northstar Commerce                                  Active / Merchant
018f...0001 [Copy ID]                          [Refresh]

INR 24,875.00
Current balance                                      Created 14 Sep 2026

Ledger entries
Posted at       Entry ID    Counterparty       Debit     Credit    Balance
20 Sep, 14:47   3           Monsoon Supply      125.00       —     24,875.00
18 Sep, 09:00   2           Local funding...       —    25,000.00  25,000.00
------------------------------------------------------------------------
Newest postings first                              [Previous] [Next]
```

Render money from integer strings with BigInt, avoiding floating-point rounding.
Label dates with an explicit timezone. Running balances come from the API and
remain correct across pages. A new account has an empty state explaining that
ledger entries appear when money moves; it does not show fabricated rows.

## New transfer (Phase 7)

```text
New transfer
From account       [Northstar Commerce                    v]
To account         [Monsoon Supply Co.                     v]
Amount (INR)       [125.00                                  ]
Idempotency key    [generated key                          ] [Copy]
                                                [Send transfer]

Transfer completed                         Transfer ID: ...
[Send same request again]  [Send twice in parallel]
Request              HTTP status          Replayed          Transfer ID
Original             201                  No                ...
Retry                201                  Yes               same ID
Ledger effect: one debit and one credit, verified from the API.
```

Retries use an immutable snapshot of the submitted payload and key. Editing
the form starts a new request rather than silently changing a retry. Show
actual response headers, errors, and entry counts.

## Transfers (Phase 7)

```text
Transfers                                            [New transfer]
Account [All v]    Kind [All v]                       [Refresh]
Created at       Transfer ID      From -> To      Amount       Status
-------------------------------------------------------------------
Select transfer -> detail panel: full IDs, amount, status, entry pair
```

Use filters the API supports. Only completed transfers currently have transfer
rows; business failures are represented in webhook events, not invented as
failed transfer records. Do not offer a status filter until the API supports it.

## Webhooks (Phase 7)

```text
Webhooks                                           [Register endpoint]
Registered endpoints: account | URL | created

Test receiver       [Healthy] [Returns 500] [Slow]

Deliveries          Endpoint [All v]    Status [All v]    [Refresh]
Event type        Endpoint        State       Attempts     Next retry
Expanded row: attempt | HTTP code | latency | error | retry time
                                                    [Replay delivery]
```

The registration dialog displays the secret once with an explicit copy/save
instruction. Secrets do not appear in the endpoint table. Replay is enabled for
delivered and dead-letter rows only. Receiver controls appear only when the
backend's local receiver is available.

## System (optional)

```text
System                                            [Run reconciliation]
Last checked: timestamp
Reconciliation: balanced / mismatches found
Currency          Entry count          Net ledger amount
Account mismatches: account | stored balance | ledger balance
```

Show the reconciliation response as supplied. Metrics appear only after their
backend endpoints exist.

## States and responsive behavior

- Initial loading: restrained table-row skeletons with an accessible loading label.
- Empty: specific explanation and a useful next action, where available.
- Error: actual API Problem Details text and a Retry button.
- Refresh: retain existing rows, show refresh activity, and identify stale data
  if the request fails.
- Narrow screens: compact navigation with accessible menu controls; wide ledger
  tables scroll within their own region instead of overflowing the whole page.
- Visible focus rings, semantic tables and labels, keyboard-operable controls,
  and accessible status announcements for create/copy/error actions.

## Phase 6 implementation after approval

Next.js App Router, TypeScript, Tailwind, and TanStack Query; hand-written Button,
Input, Select, Table, Badge, and Modal components. A typed API client preserves
Problem Details and exact money strings. Accounts and Account detail are the
working routes. A small number of focused tests cover money formatting and the
account creation/navigation flow. No frontend assets or UI implementation are
created until this proposal is approved.
