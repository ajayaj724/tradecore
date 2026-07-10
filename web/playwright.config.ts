import { defineConfig } from "@playwright/test";

// E2E against the running local stack: scripts/up.sh && scripts/run.sh (repo root), then
// `npm run dev` here. Serial — the tests share one backend and one order book.
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  timeout: 60_000,
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
  },
});
