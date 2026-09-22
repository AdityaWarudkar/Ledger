import { expect, test, type Page, type Route } from "@playwright/test";
import { money, parseAmount } from "../src/lib/format";

const accounts = [
  {
    id: "018f0000-0000-7000-8000-000000000001",
    name: "Northstar Commerce",
    currency: "INR",
    status: "ACTIVE",
    kind: "MERCHANT",
    balanceMinor: "2500000",
    createdAt: "2026-09-01T09:00:00Z",
  },
  {
    id: "018f0000-0000-7000-8000-000000000002",
    name: "Monsoon Supply Co.",
    currency: "INR",
    status: "ACTIVE",
    kind: "MERCHANT",
    balanceMinor: "0",
    createdAt: "2026-09-01T09:00:00Z",
  },
  {
    id: "018f0000-0000-7000-8000-000000000003",
    name: "Fieldwork Studio",
    currency: "USD",
    status: "ACTIVE",
    kind: "MERCHANT",
    balanceMinor: "0",
    createdAt: "2026-09-01T09:00:00Z",
  },
];
const transfer = {
  id: "019f0000-0000-7000-8000-000000000001",
  fromAccountId: accounts[0].id,
  toAccountId: accounts[1].id,
  amountMinor: "12500",
  currency: "INR",
  kind: "TRANSFER",
  status: "COMPLETED",
  createdAt: "2026-09-21T09:00:00Z",
};
const entries = [
  {
    id: "101",
    transferId: transfer.id,
    accountId: accounts[0].id,
    counterpartyId: accounts[1].id,
    counterpartyName: accounts[1].name,
    currency: "INR",
    direction: "DEBIT",
    amountMinor: "-12500",
    runningBalanceMinor: "2487500",
    createdAt: transfer.createdAt,
  },
  {
    id: "102",
    transferId: transfer.id,
    accountId: accounts[1].id,
    counterpartyId: accounts[0].id,
    counterpartyName: accounts[0].name,
    currency: "INR",
    direction: "CREDIT",
    amountMinor: "12500",
    runningBalanceMinor: "12500",
    createdAt: transfer.createdAt,
  },
];
const pageOf = (items: unknown[]) => ({
  items,
  page: 0,
  size: 25,
  totalElements: items.length,
  totalPages: 1,
});
async function json(
  route: Route,
  body: unknown,
  status = 200,
  replayed?: boolean,
) {
  await route.fulfill({
    status,
    contentType: "application/json",
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Expose-Headers": "Idempotency-Replayed",
      ...(replayed === undefined
        ? {}
        : { "Idempotency-Replayed": String(replayed) }),
    },
    body: JSON.stringify(body),
  });
}
async function setup(page: Page, post?: (route: Route) => Promise<void>) {
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "OPTIONS")
      return route.fulfill({
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET,POST,PUT,OPTIONS",
          "Access-Control-Allow-Headers": "content-type,idempotency-key",
        },
      });
    if (path === "/api/accounts") return json(route, pageOf(accounts));
    if (
      path === "/api/transfers" &&
      route.request().method() === "POST" &&
      post
    )
      return post(route);
    if (path === `/api/transfers/${transfer.id}`)
      return json(route, { transfer, entries });
    if (path === "/api/transfers") return json(route, pageOf([transfer]));
    return json(route, { detail: "Not found" }, 404);
  });
}
async function fillTransfer(page: Page) {
  await page.goto("/transfers/new");
  await page.getByLabel("From account").selectOption(accounts[0].id);
  await page.getByLabel("To account").selectOption(accounts[1].id);
  await page.getByLabel("Amount · INR").fill("125.00");
}

test("money stays exact beyond Number precision and validates boundaries", () => {
  expect(money("9223372036854775807", "USD")).toBe(
    "USD 92,233,720,368,547,758.07",
  );
  expect(money("-1", "INR")).toBe("−INR 0.01");
  expect(parseAmount("92233720368547758.07")).toBe("9223372036854775807");
  expect(parseAmount("0.01")).toBe("1");
  for (const value of [
    "0",
    "-1",
    "1.001",
    "1e3",
    "NaN",
    "92233720368547758.08",
  ])
    expect(() => parseAmount(value)).toThrow();
});

test("parallel requests share an immutable key and show verified ledger proof", async ({
  page,
}) => {
  const requests: { key: string; body: unknown }[] = [];
  await setup(page);
  let count = 0;
  await page.route("**/api/transfers", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    const index = count++;
    requests.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postDataJSON(),
    });
    // Hold the first response until the second request arrives to prove requests overlap.
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(2);
    await json(route, transfer, 201, index > 0);
  });
  await fillTransfer(page);
  await expect(page.getByLabel("To account").locator("option")).toHaveCount(2);
  await page.getByRole("button", { name: "Send twice in parallel" }).click();
  await expect(
    page.getByText("Verified: one transfer, two balanced ledger entries."),
  ).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[0].body).toEqual({
    fromAccountId: accounts[0].id,
    toAccountId: accounts[1].id,
    amountMinor: "12500",
  });
  await expect(page.getByLabel("Amount · INR")).toBeDisabled();
  const results = page.getByRole("table", {
    name: "Request results",
    exact: true,
  });
  await expect(
    results.getByRole("cell", { name: "Yes", exact: true }),
  ).toHaveCount(1);
  await expect(
    results.getByRole("cell", { name: "No", exact: true }),
  ).toHaveCount(1);
  await page.getByRole("button", { name: "Send same request again" }).click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2]).toEqual(requests[0]);
});

test("unknown network outcome survives reload without issuing a new key", async ({
  page,
}) => {
  const requests: { key: string; body: unknown }[] = [];
  await setup(page, async (route) => {
    requests.push({
      key: route.request().headers()["idempotency-key"],
      body: route.request().postDataJSON(),
    });
    if (requests.length === 1) return route.abort("failed");
    return json(route, transfer, 201, true);
  });
  await fillTransfer(page);
  await page
    .getByRole("button", { name: "Send transfer", exact: true })
    .click();
  await expect(
    page.getByText("Could not reach the ledger service.", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Start a new transfer" }),
  ).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("Amount · INR")).toBeDisabled();
  await page.getByRole("button", { name: "Send same request again" }).click();
  await expect(
    page.getByText("Verified: one transfer, two balanced ledger entries."),
  ).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[1]).toEqual(requests[0]);
});

test("invalid precision never sends and business errors retain their request", async ({
  page,
}) => {
  let sent = 0;
  await setup(page, async (route) => {
    sent++;
    await json(route, { detail: "Insufficient balance" }, 422, false);
  });
  await fillTransfer(page);
  await page.getByLabel("Amount · INR").fill("0.001");
  await page
    .getByRole("button", { name: "Send transfer", exact: true })
    .click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "at most two decimal places",
  );
  expect(sent).toBe(0);
  await page.getByLabel("Amount · INR").fill("125.00");
  await page
    .getByRole("button", { name: "Send transfer", exact: true })
    .click();
  await expect(
    page.getByText("Insufficient balance", { exact: true }),
  ).toBeVisible();
  expect(sent).toBe(1);
  await expect(page.getByLabel("From account")).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Start a new transfer" }),
  ).toBeVisible();
});

test("account dialog supports Escape and returns keyboard focus", async ({
  page,
}) => {
  await setup(page);
  await page.goto("/accounts");
  const create = page.getByRole("button", {
    name: "Create account",
    exact: true,
  });
  await create.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByLabel("Account name")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(create).toBeFocused();
});

test("mobile navigation and tables stay within the viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.goto("/accounts");
  await expect(
    page.getByRole("link", { name: "Northstar Commerce" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "Menu", exact: true }).click();
  await page.getByRole("link", { name: "New transfer", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "New transfer", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("webhook tables containing copy controls do not widen the mobile page", async ({page}) => {
  await setup(page);
  await page.route("**/api/webhooks/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/behavior")) return json(route, {behavior: "HEALTHY"});
    if (path.endsWith("/endpoints")) return json(route, [{id: "019f0000-0000-7000-8000-000000000008", accountId: accounts[0].id, url: "http://localhost:8080/api/webhooks/test-receiver", createdAt: transfer.createdAt}]);
    return json(route, []);
  });
  await page.setViewportSize({width: 390, height: 844});
  await page.goto("/webhooks");
  await expect(page.getByRole("table", {name: "Webhook endpoints", exact: true})).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const scroller = page.getByRole("region", {name: "Webhook endpoints", exact: true});
  expect(await scroller.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
});
