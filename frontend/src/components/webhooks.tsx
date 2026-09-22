"use client";
import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { allAccounts, ApiError, get, request } from "@/lib/api";
import { date, label, shortId } from "@/lib/format";
import type {
  Account,
  Behavior,
  Delivery,
  DeliveryDetail,
  Endpoint,
  RegisteredEndpoint,
} from "@/lib/types";
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

async function allEndpoints(signal?: AbortSignal) {
  const items: Endpoint[] = [];
  for (let page = 0; ; page++) {
    const batch = await get<Endpoint[]>(
      `/webhooks/endpoints?page=${page}&size=100`,
      signal,
    );
    items.push(...batch);
    if (batch.length < 100) return items;
  }
}
export function Webhooks() {
  const [registering, setRegistering] = useState(false);
  const [endpointId, setEndpointId] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [endpointPage, setEndpointPage] = useState(0);
  const endpoints = useQuery({
    queryKey: ["endpoints"],
    queryFn: ({ signal }) => allEndpoints(signal),
  });
  const accounts = useQuery({
    queryKey: ["accounts", "all"],
    queryFn: ({ signal }) => allAccounts(signal),
  });
  const deliveries = useQuery({
    queryKey: ["deliveries", endpointId, status, page],
    queryFn: ({ signal }) =>
      get<Delivery[]>(
        `/webhooks/deliveries?${new URLSearchParams({ page: String(page), size: "25", ...(endpointId && { endpointId }), ...(status && { status }) })}`,
        signal,
      ),
    refetchInterval: 3000,
  });
  const name = (id: string) =>
    accounts.data?.find((account) => account.id === id)?.name || shortId(id);
  return (
    <>
      <PageHeader
        title="Webhooks"
        eyebrow="Delivery operations"
        description="Follow events from the outbox to your receiver."
        actions={
          <Button variant="primary" onClick={() => setRegistering(true)}>
            Register endpoint
          </Button>
        }
      />
      <Receiver />
      <ErrorNotice error={accounts.error} retry={() => accounts.refetch()} />
      <section className="panel">
        <div className="panel-heading">
          <h2>Endpoints</h2>
          <Button
            onClick={() => endpoints.refetch()}
            disabled={endpoints.isFetching}
          >
            Refresh endpoints
          </Button>
        </div>
        <ErrorNotice
          error={endpoints.error}
          retry={() => endpoints.refetch()}
          stale={!!endpoints.data}
        />
        {endpoints.isPending ? (
          <Loading rows={2} />
        ) : (
          endpoints.data &&
          (endpoints.data.length ? (
            <>
              <Table caption="Webhook endpoints">
                <thead>
                  <tr>
                    <th>Account</th>
                    <th>Destination</th>
                    <th>Endpoint ID</th>
                  </tr>
                </thead>
                <tbody>
                  {endpoints.data
                    .slice(endpointPage * 10, endpointPage * 10 + 10)
                    .map((endpoint) => (
                      <tr key={endpoint.id}>
                        <td>{name(endpoint.accountId)}</td>
                        <td className="break-all">
                          <code>{endpoint.url}</code>
                        </td>
                        <td>
                          <Copy value={endpoint.id} compact />
                        </td>
                      </tr>
                    ))}
                </tbody>
              </Table>
              <Pagination
                page={endpointPage}
                size={10}
                total={endpoints.data.length}
                next={(endpointPage + 1) * 10 < endpoints.data.length}
                onChange={setEndpointPage}
              />
            </>
          ) : (
            <Empty title="No endpoints registered">
              Register a receiver before creating a transfer to receive its
              events.
            </Empty>
          ))
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Deliveries</h2>
          <span className="muted">Refreshes every 3 seconds</span>
        </div>
        <div className="toolbar">
          <Select
            aria-label="Filter by endpoint"
            value={endpointId}
            onChange={(event) => {
              setEndpointId(event.target.value);
              setPage(0);
              setExpanded(null);
            }}
          >
            <option value="">All endpoints</option>
            {endpoints.data?.map((endpoint) => (
              <option key={endpoint.id} value={endpoint.id}>
                {name(endpoint.accountId)}
              </option>
            ))}
          </Select>
          <Select
            aria-label="Filter by delivery status"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
              setExpanded(null);
            }}
          >
            <option value="">All statuses</option>
            {["PENDING", "PROCESSING", "RETRY", "DELIVERED", "DEAD_LETTER"].map(
              (value) => (
                <option key={value} value={value}>
                  {label(value)}
                </option>
              ),
            )}
          </Select>
        </div>
        <ErrorNotice
          error={deliveries.error}
          retry={() => deliveries.refetch()}
          stale={!!deliveries.data}
        />
        {deliveries.isPending ? (
          <Loading />
        ) : (
          deliveries.data &&
          (deliveries.data.length ? (
            <Table caption="Webhook deliveries">
              <thead>
                <tr>
                  <th>Event</th>
                  <th>Status</th>
                  <th>Attempts</th>
                  <th>Next retry · UTC</th>
                  <th>Created · UTC</th>
                  <th>
                    <span className="sr-only">Details</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {deliveries.data.map((delivery) => (
                  <Fragment key={delivery.id}>
                    <tr>
                      <td>
                        <span className="account-name">{delivery.type}</span>
                        <code className="subline">
                          {shortId(delivery.eventId)}
                        </code>
                      </td>
                      <td>
                        <Badge value={delivery.status} />
                      </td>
                      <td>
                        {delivery.attemptCount}
                        <span className="subline">
                          {delivery.cycleAttempts} this cycle
                        </span>
                      </td>
                      <td>{date(delivery.nextAttemptAt)}</td>
                      <td>{date(delivery.createdAt)}</td>
                      <td>
                        <Button
                          aria-expanded={expanded === delivery.id}
                          aria-controls={`delivery-${delivery.id}`}
                          onClick={() =>
                            setExpanded(
                              expanded === delivery.id ? null : delivery.id,
                            )
                          }
                        >
                          {expanded === delivery.id ? "Close" : "Inspect"}
                        </Button>
                      </td>
                    </tr>
                    {expanded === delivery.id && (
                      <tr className="delivery-expanded">
                        <td colSpan={6}>
                          <div id={`delivery-${delivery.id}`}>
                            <DeliveryInspection id={delivery.id} />
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </Table>
          ) : (
            <Empty title="No deliveries found">
              Events appear after a transfer involving a registered account. Try
              another filter.
            </Empty>
          ))
        )}
        {deliveries.data && (
          <Pagination
            page={page}
            next={deliveries.data.length === 25}
            onChange={(value) => {
              setPage(value);
              setExpanded(null);
            }}
          />
        )}
      </section>
      {registering && (
        <RegisterEndpoint
          accounts={accounts.data || []}
          endpoints={endpoints.data || []}
          onClose={() => setRegistering(false)}
        />
      )}
    </>
  );
}

function RegisterEndpoint({
  accounts,
  endpoints,
  onClose,
}: {
  accounts: Account[];
  endpoints: Endpoint[];
  onClose: () => void;
}) {
  const client = useQueryClient();
  const [accountId, setAccountId] = useState("");
  const [url, setUrl] = useState("");
  const mutation = useMutation({
    mutationFn: () =>
      request<RegisteredEndpoint>("/webhooks/endpoints", {
        method: "POST",
        body: JSON.stringify({ accountId, url: url.trim() }),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["endpoints"] }),
  });
  return (
    <Modal
      title={mutation.data ? "Save your signing secret" : "Register endpoint"}
      onClose={onClose}
      busy={mutation.isPending}
    >
      {mutation.data ? (
        <>
          <div className="modal-body">
            <p>
              This secret is shown only once. Store it before closing this
              dialog.
            </p>
            <div className="key-box">
              <Copy value={mutation.data.data.signingSecret} />
            </div>
            <p className="muted">
              Use this secret to verify the timestamp and raw request body with
              HMAC-SHA256.
            </p>
          </div>
          <div className="modal-footer">
            <Button variant="primary" onClick={onClose}>
              I’ve saved the secret
            </Button>
          </div>
        </>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!mutation.isPending) mutation.mutate();
          }}
        >
          <div className="modal-body">
            <ErrorNotice error={mutation.error} />
            <Field
              id="endpoint-account"
              title="Account"
              hint="One endpoint per account. Registration applies to future events."
            >
              <Select
                id="endpoint-account"
                required
                value={accountId}
                disabled={mutation.isPending}
                onChange={(event) => setAccountId(event.target.value)}
              >
                <option value="">Choose an account</option>
                {accounts
                  .filter(
                    (account) =>
                      !endpoints.some(
                        (endpoint) => endpoint.accountId === account.id,
                      ),
                  )
                  .map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.name}
                    </option>
                  ))}
              </Select>
            </Field>
            <Field
              id="endpoint-url"
              title="Receiver URL"
              hint="The destination must be allowed by the backend configuration."
            >
              <Input
                id="endpoint-url"
                type="url"
                required
                maxLength={2048}
                value={url}
                disabled={mutation.isPending}
                onChange={(event) => setUrl(event.target.value)}
                placeholder="https://example.com/webhooks"
              />
            </Field>
            <p className="muted text-xs">
              Local demo URL:{" "}
              <code className="break-all">
                http://localhost:8080/api/webhooks/test-receiver
              </code>
            </p>
          </div>
          <div className="modal-footer">
            <Button
              type="button"
              disabled={mutation.isPending}
              onClick={onClose}
            >
              Cancel
            </Button>
            <Button variant="primary" disabled={mutation.isPending}>
              {mutation.isPending ? "Registering…" : "Register endpoint"}
            </Button>
          </div>
        </form>
      )}
    </Modal>
  );
}

function DeliveryInspection({ id }: { id: string }) {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["delivery", id],
    queryFn: ({ signal }) =>
      get<DeliveryDetail>(`/webhooks/deliveries/${id}`, signal),
    refetchInterval: 2000,
  });
  const replay = useMutation({
    mutationFn: () =>
      request<Delivery>(`/webhooks/deliveries/${id}/replay`, {
        method: "POST",
      }),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: ["delivery", id] }),
        client.invalidateQueries({ queryKey: ["deliveries"] }),
      ]),
  });
  const data = query.data;
  const terminal =
    data && ["DELIVERED", "DEAD_LETTER"].includes(data.delivery.status);
  let payload = data?.payload || "";
  try {
    payload = JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    /* Show the original body when it is not JSON. */
  }
  return (
    <div className="delivery-detail">
      <ErrorNotice error={query.error} retry={() => query.refetch()} />
      <ErrorNotice error={replay.error} />
      {query.isPending && <Loading rows={2} />}
      {data && (
        <>
          <div className="panel-heading">
            <div>
              <h3>Delivery attempts</h3>
              <Copy value={id} />
            </div>
            <Button
              disabled={!terminal || replay.isPending}
              onClick={() => replay.mutate()}
            >
              {replay.isPending ? "Queuing…" : "Replay delivery"}
            </Button>
          </div>
          <p className="muted">
            Replay keeps the event ID and previous attempts. It is available
            after delivery or after the retry budget is exhausted.
          </p>
          <Table caption="Delivery attempt history">
            <thead>
              <tr>
                <th>Attempt</th>
                <th>Started · UTC</th>
                <th>HTTP</th>
                <th>Latency</th>
                <th>Outcome</th>
                <th>Next retry · UTC</th>
              </tr>
            </thead>
            <tbody>
              {data.attempts.map((attempt) => (
                <tr key={attempt.number}>
                  <td>#{attempt.number}</td>
                  <td>{date(attempt.startedAt)}</td>
                  <td>{attempt.statusCode ?? "Unknown"}</td>
                  <td>
                    {attempt.latencyMs === null
                      ? "—"
                      : `${attempt.latencyMs} ms`}
                  </td>
                  <td>
                    {attempt.error ||
                      (attempt.finishedAt
                        ? "Completed"
                        : "In progress / outcome unknown")}
                  </td>
                  <td>{date(attempt.nextAttemptAt)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
          {!data.attempts.length && (
            <p className="muted">Waiting for the first attempt.</p>
          )}
          <details>
            <summary>Event payload</summary>
            <pre className="payload">{payload}</pre>
          </details>
        </>
      )}
    </div>
  );
}

function Receiver() {
  const client = useQueryClient();
  const [showReceipts, setShowReceipts] = useState(false);
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["receiver"],
    queryFn: ({ signal }) =>
      get<{ behavior: Behavior }>("/webhooks/test-receiver/behavior", signal),
    retry: false,
  });
  const mutation = useMutation({
    mutationFn: (behavior: Behavior) =>
      request<{ behavior: Behavior }>("/webhooks/test-receiver/behavior", {
        method: "PUT",
        body: JSON.stringify({ behavior }),
      }),
    onSuccess: ({ data }) => {
      client.setQueryData(["receiver"], data);
    },
  });
  const receipts = useQuery({
    queryKey: ["receipts", page],
    queryFn: ({ signal }) =>
      get<{ endpointId: string; eventId: string; receivedAt: string }[]>(
        `/webhooks/test-receiver/receipts?page=${page}&size=25`,
        signal,
      ),
    enabled: showReceipts && !!query.data,
    refetchInterval: showReceipts ? 3000 : false,
  });
  if (query.error instanceof ApiError && query.error.status === 404)
    return null;
  return (
    <section className="panel">
      <ErrorNotice error={query.error} retry={() => query.refetch()} />
      <ErrorNotice error={mutation.error} />
      {query.isPending && <Loading rows={1} />}
      {query.data && (
        <>
          <div className="receiver">
            <div>
              <p className="eyebrow">Local test receiver</p>
              <h2>Make failure visible.</h2>
              <p className="muted">
                Healthy accepts events. Fail returns 500. Slow exceeds the
                sender’s timeout.
              </p>
            </div>
            <div className="segmented" aria-label="Receiver behavior">
              {(["HEALTHY", "FAIL", "SLOW"] as Behavior[]).map((value) => (
                <button
                  key={value}
                  aria-pressed={query.data.behavior === value}
                  disabled={mutation.isPending}
                  onClick={() => mutation.mutate(value)}
                >
                  {label(value)}
                </button>
              ))}
            </div>
          </div>
          <div className="panel-body">
            <Button
              variant="quiet"
              aria-expanded={showReceipts}
              onClick={() => setShowReceipts(!showReceipts)}
            >
              {showReceipts ? "Hide" : "View"} deduplicated receipts
            </Button>
            {showReceipts && (
              <>
                <p className="muted">
                  The receiver stores each endpoint/event pair once, including
                  after replay.
                </p>
                <ErrorNotice
                  error={receipts.error}
                  retry={() => receipts.refetch()}
                />
                {receipts.isPending ? (
                  <Loading rows={2} />
                ) : (
                  receipts.data && (
                    <>
                      <Table caption="Receiver receipts">
                        <thead>
                          <tr>
                            <th>Event</th>
                            <th>Endpoint</th>
                            <th>Received · UTC</th>
                          </tr>
                        </thead>
                        <tbody>
                          {receipts.data.map((receipt) => (
                            <tr
                              key={`${receipt.endpointId}:${receipt.eventId}`}
                            >
                              <td>
                                <Copy value={receipt.eventId} compact />
                              </td>
                              <td>
                                <Copy value={receipt.endpointId} compact />
                              </td>
                              <td>{date(receipt.receivedAt)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </Table>
                      {!receipts.data.length && (
                        <p className="muted">No receipts on this page.</p>
                      )}
                      <Pagination
                        page={page}
                        next={receipts.data.length === 25}
                        onChange={setPage}
                      />
                    </>
                  )
                )}
              </>
            )}
          </div>
        </>
      )}
    </section>
  );
}
