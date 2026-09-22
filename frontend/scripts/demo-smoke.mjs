import { chromium, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const frontend = process.env.DEMO_URL || "http://127.0.0.1:3000";
const api = process.env.DEMO_API_URL || "http://127.0.0.1:8080";
const output = fileURLToPath(
  new URL("../../docs/screenshots/", import.meta.url),
);
const read = async (path) => {
  const response = await fetch(`${api}/api${path}`, {
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok)
    throw new Error(`HTTP ${response.status}: ${await response.text()}`);
  return response.json();
};
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
await mkdir(output, { recursive: true });
const screenshot = (name) =>
  page.screenshot({ path: `${output}/${name}.png`, fullPage: true });

try {
  const source = await read("/accounts/018f0000-0000-7000-8000-000000000001");
  const allAccounts = [];
  for (let index = 0; ; index++) {
    const batch = await read(`/accounts?page=${index}&size=100`);
    allAccounts.push(...batch.items);
    if (index + 1 >= batch.totalPages) break;
  }
  let recipient = allAccounts.find(
    (account) =>
      account.name === "Harbour Coffee" &&
      account.currency === "INR" &&
      account.status === "ACTIVE",
  );
  await page.goto(`${frontend}/accounts`);
  await expect(
    page.getByRole("heading", { name: "Accounts", exact: true }),
  ).toBeVisible();
  if (!recipient) {
    await page
      .getByRole("button", { name: "Create account", exact: true })
      .click();
    await page.getByLabel("Account name").fill("Harbour Coffee");
    const created = page.waitForResponse(
      (response) =>
        response.url() === `${api}/api/accounts` &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Create account", exact: true })
      .click();
    recipient = await (await created).json();
    await expect(
      page.getByRole("heading", { name: "Harbour Coffee", exact: true }),
    ).toBeVisible();
  }
  const endpoints = [];
  for (let index = 0; ; index++) {
    const batch = await read(`/webhooks/endpoints?page=${index}&size=100`);
    endpoints.push(...batch);
    if (batch.length < 100) break;
  }
  let endpoint = endpoints.find((item) => item.accountId === recipient.id);
  await page.goto(`${frontend}/webhooks`);
  if (!endpoint) {
    await page
      .getByRole("button", { name: "Register endpoint", exact: true })
      .click();
    await page
      .getByLabel("Account", { exact: true })
      .selectOption(recipient.id);
    await page
      .getByLabel("Receiver URL")
      .fill("http://localhost:8080/api/webhooks/test-receiver");
    const registered = page.waitForResponse(
      (response) =>
        response.url() === `${api}/api/webhooks/endpoints` &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Register endpoint", exact: true })
      .click();
    const response = await registered;
    expect(response.status()).toBe(201);
    endpoint = await response.json();
    await expect(
      page.getByRole("heading", { name: "Save your signing secret" }),
    ).toBeVisible();
    // This local receiver reads its own secret; never print it or include it in screenshots.
    await page.getByRole("button", { name: "I’ve saved the secret" }).click();
  }
  expect(endpoint.url).toBe("http://localhost:8080/api/webhooks/test-receiver");
  await page.goto(`${frontend}/developers`);
  await page.getByRole("button", { name: "Healthy", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Healthy", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.goto(`${frontend}/accounts`);
  await expect(
    page.getByRole("table", { name: "Accounts", exact: true }),
  ).toBeVisible();
  await screenshot("accounts");
  await page.goto(`${frontend}/accounts/${source.id}`);
  await expect(
    page.getByRole("table", { name: "Ledger entries", exact: true }),
  ).toBeVisible();
  await screenshot("account-detail");

  await page.goto(`${frontend}/developers/transfers`);
  await page.getByLabel("From account").selectOption(source.id);
  await page.getByLabel("To account").selectOption(recipient.id);
  await page.getByLabel("Amount · INR").fill("1.00");
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url() === `${api}/api/transfers` &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Send twice in parallel" }).click();
  const transferResponse = await responsePromise;
  expect(transferResponse.status()).toBe(201);
  const transfer = await transferResponse.json();
  await expect(
    page.getByText("Verified: one transfer, two balanced ledger entries."),
  ).toBeVisible();
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
  const after = await read(`/accounts/${source.id}`);
  expect(BigInt(source.balanceMinor) - BigInt(after.balanceMinor)).toBe(100n);
  await screenshot("transfer-proof");
  await page.goto(`${frontend}/transfers`);
  await expect(
    page.getByRole("table", { name: "Transfers", exact: true }),
  ).toBeVisible();
  await screenshot("transfers");
  await page.goto(`${frontend}/transfers/${transfer.id}`);
  await expect(
    page.getByRole("table", { name: "Ledger entries", exact: true }),
  ).toBeVisible();

  let delivery;
  await expect
    .poll(
      async () => {
        const deliveries = await read(
          `/webhooks/deliveries?endpointId=${endpoint.id}`,
        );
        for (const item of deliveries) {
          const detail = await read(`/webhooks/deliveries/${item.id}`);
          if (
            JSON.parse(detail.payload).data.transferId === transfer.id ||
            JSON.parse(detail.payload).data.id === transfer.id
          ) {
            delivery = detail;
            return detail.delivery.status;
          }
        }
        return "waiting";
      },
      { timeout: 30000 },
    )
    .toBe("DELIVERED");
  const receiptCount = async () =>
    (
      await read(
        `/webhooks/test-receiver/receipts?endpointId=${endpoint.id}&size=100`,
      )
    ).filter((receipt) => receipt.eventId === delivery.delivery.eventId).length;
  expect(await receiptCount()).toBe(1);
  await page.goto(`${frontend}/developers`);
  await page.getByRole("button", { name: "Fail", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Fail", exact: true }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.goto(`${frontend}/webhooks`);
  await page.getByLabel("Filter by endpoint").selectOption(endpoint.id);
  await page
    .getByRole("button", { name: "Inspect", exact: true })
    .first()
    .click();
  await page
    .getByRole("button", { name: "Replay delivery", exact: true })
    .click();
  await expect
    .poll(
      async () =>
        (
          await read(`/webhooks/deliveries/${delivery.delivery.id}`)
        ).attempts.some((attempt) => attempt.statusCode === 500),
      { timeout: 20000 },
    )
    .toBe(true);
  await page.goto(`${frontend}/developers`);
  await page.getByRole("button", { name: "Healthy", exact: true }).click();
  await expect
    .poll(
      async () =>
        (await read(`/webhooks/deliveries/${delivery.delivery.id}`)).delivery
          .status,
      { timeout: 30000 },
    )
    .toBe("DELIVERED");
  expect(await receiptCount()).toBe(1);
  await page.goto(`${frontend}/webhooks`);
  await page.getByLabel("Filter by endpoint").selectOption(endpoint.id);
  await page
    .getByRole("button", { name: "Inspect", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: "Replay delivery", exact: true }),
  ).toBeEnabled();
  await screenshot("webhooks");
  const reconciliation = await read("/system/reconciliation");
  expect(reconciliation.balanced).toBe(true);
  await page.goto(`${frontend}/system`);
  await expect(page.getByText("Balanced", { exact: true })).toBeVisible();
  await screenshot("system");
  await page.setViewportSize({ width: 390, height: 844 });
  for (const route of ["accounts", "transfers/new", "webhooks"]) {
    await page.goto(`${frontend}/${route}`);
    if (route === "transfers/new")
      await expect(page.getByLabel("Amount", { exact: true })).toBeVisible();
    else await expect(page.getByRole("table").first()).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await screenshot(`${route.replace("/", "-")}-mobile`);
  }
  expect(errors).toEqual([]);
  const summary = {
    checkedAt: new Date().toISOString(),
    result: "PASS",
    transferId: transfer.id,
    parallelRequests: 2,
    balanceDeltaMinor: "100",
    deduplicatedReceipts: 1,
    reconciliation,
  };
  await writeFile(
    `${output}/verification.json`,
    JSON.stringify(summary, null, 2) + "\n",
  );
  console.log(JSON.stringify(summary, null, 2));
} finally {
  await fetch(`${api}/api/webhooks/test-receiver/behavior`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ behavior: "HEALTHY" }),
    signal: AbortSignal.timeout(15000),
  });
  await browser.close();
}
