export type Currency = "INR" | "USD" | "EUR" | "GBP";
export type Account = {
  id: string;
  name: string;
  currency: Currency;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
  kind: "MERCHANT" | "CLEARING";
  balanceMinor: string;
  createdAt: string;
};
export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type Entry = {
  id: string;
  transferId: string;
  accountId: string;
  counterpartyId: string;
  counterpartyName: string;
  currency: Currency;
  direction: "DEBIT" | "CREDIT";
  amountMinor: string;
  runningBalanceMinor: string;
  createdAt: string;
};
export type TransferRequest = {
  fromAccountId: string;
  toAccountId: string;
  amountMinor: string;
};
export type Transfer = TransferRequest & {
  id: string;
  currency: Currency;
  kind: "TRANSFER" | "FUNDING";
  status: string;
  createdAt: string;
};
export type TransferDetail = { transfer: Transfer; entries: Entry[] };
export type Endpoint = {
  id: string;
  accountId: string;
  url: string;
  createdAt: string;
};
export type RegisteredEndpoint = Omit<Endpoint, "createdAt"> & {
  signingSecret: string;
};
export type DeliveryStatus =
  "PENDING" | "PROCESSING" | "RETRY" | "DELIVERED" | "DEAD_LETTER";
export type Delivery = {
  id: string;
  eventId: string;
  endpointId: string;
  type: string;
  status: DeliveryStatus;
  attemptCount: number;
  cycleAttempts: number;
  nextAttemptAt: string | null;
  leaseUntil: string | null;
  deliveredAt: string | null;
  createdAt: string;
};
export type Attempt = {
  number: number;
  startedAt: string;
  finishedAt: string | null;
  statusCode: number | null;
  latencyMs: number | null;
  error: string | null;
  nextAttemptAt: string | null;
};
export type DeliveryDetail = {
  delivery: Delivery;
  attempts: Attempt[];
  payload: string;
};
export type Behavior = "HEALTHY" | "FAIL" | "SLOW";
export type Reconciliation = {
  balanced: boolean;
  checkedAt: string;
  currencies: { currency: Currency; entryCount: number; netMinor: string }[];
  mismatches: {
    accountId: string;
    currency: Currency;
    balanceMinor: string;
    ledgerBalanceMinor: string;
  }[];
};
