import { expect, Page, test } from "@playwright/test";

// The OIDC round trip for a seeded demo user; lands back on the trading screen.
async function signIn(page: Page, user: string) {
  await page.goto("/");
  // A prior test's session may not have fully cleared; drop it before signing in fresh.
  const stillIn = page.getByRole("button", { name: "Sign out" });
  if (await stillIn.isVisible().catch(() => false)) {
    await signOut(page);
  }
  await page.getByRole("button", { name: "Continue with Keycloak" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill("demo");
  await page.locator("#kc-login").click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("button", { name: "Continue with Keycloak" })).toBeVisible();
}

test("unauthenticated visitors are sent to sign in", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/signin/);
  await expect(page.getByRole("button", { name: "Continue with Keycloak" })).toBeVisible();
});

test("a trader places a resting limit order and cancels it", async ({ page }) => {
  await signIn(page, "trader1");

  // Far below the market so it rests instead of matching.
  await page.locator('input[inputmode="decimal"]').fill("50.00");
  await page.locator('input[inputmode="numeric"]').fill("1");
  await page.getByRole("button", { name: /Buy 1 ACME/ }).click();

  const row = page.locator("tbody tr").first();
  await expect(row).toContainText("Accepted");
  await row.getByRole("button", { name: "Cancel" }).click();
  await expect(row).toContainText("Cancelled", { timeout: 20_000 });

  await signOut(page);
});

test("the market ticket needs no price and discloses the collar", async ({ page }) => {
  await signIn(page, "trader1");

  await page.getByRole("button", { name: "MARKET", exact: true }).click();
  await expect(page.getByText(/protected by a 5% collar/)).toBeVisible();
  await expect(page.locator('input[inputmode="decimal"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: /at market/ })).toBeVisible();

  await signOut(page);
});

test("an ops cancel needs a second approver (four-eyes)", async ({ page }) => {
  // trader1 rests the order that ops will act on.
  await signIn(page, "trader1");
  await page.locator('input[inputmode="decimal"]').fill("50.00");
  await page.locator('input[inputmode="numeric"]').fill("1");
  await page.getByRole("button", { name: /Buy 1 ACME/ }).click();
  const firstRow = page.locator("tbody tr").first();
  await expect(firstRow).toContainText("Accepted");
  const orderId = (await firstRow.locator("td").first().textContent())!.trim().replace("#", "");
  expect(orderId).toMatch(/^\d+$/);
  await signOut(page);

  // ops1 requests the cancellation; their own Approve stays disabled.
  await signIn(page, "ops1");
  await page.getByRole("button", { name: "All accounts" }).click();
  const targetRow = page.locator("tbody tr", { hasText: `#${orderId}` }).first();
  await expect(targetRow).toContainText("Accepted"); // wait for the all-accounts view to load
  page.once("dialog", (dialog) => dialog.accept());
  await targetRow.getByRole("button", { name: "Cancel" }).click();
  const approvals = page.locator("table", { hasText: "Requested by" });
  await expect(approvals.locator("tr", { hasText: orderId })).toContainText("ops1");
  await expect(
    approvals.locator("tr", { hasText: orderId }).getByRole("button", { name: "Approve" }),
  ).toBeDisabled();
  await signOut(page);

  // ops2 approves; the order cancels through the normal event choreography.
  await signIn(page, "ops2");
  await page.getByRole("button", { name: "All accounts" }).click();
  const pending = page.locator("table", { hasText: "Requested by" }).locator("tr", { hasText: orderId });
  await pending.getByRole("button", { name: "Approve" }).click();
  await expect(
    page.locator("tbody tr", { hasText: orderId }).first(),
  ).toContainText("Cancelled", { timeout: 20_000 });
  await signOut(page);
});

test("an admin observes health and everything, touches nothing", async ({ page }) => {
  await signIn(page, "admin1");

  await expect(page.getByText(/RECONCILED|DRIFT/)).toBeVisible();
  await expect(page.getByText("New order")).toHaveCount(0); // no ticket
  await page.getByRole("button", { name: "All accounts" }).click();
  await expect(page.locator("tbody tr").first()).toBeVisible();
  await expect(page.locator("tbody").getByRole("button", { name: "Cancel" })).toHaveCount(0);
  await expect(page.locator("tbody").getByRole("button", { name: "Approve" })).toHaveCount(0);

  await signOut(page);
});
