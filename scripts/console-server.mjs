// Local control server for the dev console. Serves the console, proxies the API to the backend
// (so the Operations panel works same-origin, no CORS), and runs an allowlist of management
// scripts on request (so the Manage panel is real buttons). Local dev only: binds 127.0.0.1,
// runs FIXED scripts by key — no request input ever reaches a shell. Runs on bun or node, no deps.

import { createServer, request as httpRequest } from "node:http";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PORT = Number(process.env.CONSOLE_PORT || 8090);
const BACKEND = { host: "127.0.0.1", port: Number(process.env.BACKEND_PORT || 8080) };
const CONSOLE_HTML = join(ROOT, "src/main/resources/local-console/console.html");

// key -> { cmd, args, detached?, label }. Fixed commands only; no interpolation of request data.
const ACTIONS = {
  "platform-up": { cmd: "bash", args: ["scripts/up.sh"], label: "Start platform (Postgres, Keycloak, observability)" },
  "backend-start": { cmd: "bash", args: ["scripts/run.sh"], detached: true, label: "Start the backend" },
  "backend-stop": { cmd: "bash", args: ["-c", "lsof -ti :8080 -sTCP:LISTEN | xargs kill 2>/dev/null || true"], label: "Stop the backend" },
  "ui-start": { cmd: "bash", args: ["-c", "cd web && bun run dev"], detached: true, label: "Start the web UI (Next.js dev server)" },
  "ui-stop": { cmd: "bash", args: ["-c", "lsof -ti :3000 -sTCP:LISTEN | xargs kill 2>/dev/null || true"], label: "Stop the web UI" },
  "platform-down": { cmd: "bash", args: ["scripts/down.sh"], label: "Stop the platform" },
  "wipe": { cmd: "bash", args: ["scripts/down.sh", "--wipe"], label: "Stop + wipe volumes (reset data)" },
  "gate": { cmd: "bash", args: ["scripts/gate.sh"], label: "Run the full quality gate" },
  "smoke": { cmd: "bash", args: ["scripts/smoke.sh"], label: "Run the end-to-end smoke suite" },
};

const send = (res, code, body, type = "application/json") =>
  res.writeHead(code, { "Content-Type": type }).end(typeof body === "string" ? body : JSON.stringify(body));

function proxy(req, res) {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks);
    const headers = { ...req.headers, host: `${BACKEND.host}:${BACKEND.port}` };
    const up = httpRequest({ ...BACKEND, method: req.method, path: req.url, headers }, (r) => {
      res.writeHead(r.statusCode || 502, r.headers);
      r.pipe(res);
    });
    up.on("error", () => send(res, 502, { error: "backend unreachable — start it from the Manage panel" }));
    if (body.length) up.write(body);
    up.end();
  });
}

function runAction(action, res) {
  const spec = ACTIONS[action];
  if (!spec) return send(res, 404, { error: `unknown action '${action}'` });
  if (spec.detached) {
    const child = spawn(spec.cmd, spec.args, { cwd: ROOT, detached: true, stdio: "ignore" });
    child.unref();
    return send(res, 202, { action, label: spec.label, status: "started (running in the background)" });
  }
  const child = spawn(spec.cmd, spec.args, { cwd: ROOT });
  let out = "";
  const cap = (d) => { out += d; if (out.length > 20000) out = out.slice(-20000); };
  child.stdout.on("data", cap);
  child.stderr.on("data", cap);
  child.on("close", (code) => send(res, code === 0 ? 200 : 500, { action, label: spec.label, code, output: out.trim() }));
  child.on("error", (e) => send(res, 500, { action, error: String(e) }));
}

function status(res) {
  const up = httpRequest({ ...BACKEND, method: "GET", path: "/actuator/health", timeout: 1500 }, (r) => {
    r.resume();
    send(res, 200, { backend: r.statusCode === 200 ? "up" : "starting", actions: Object.entries(ACTIONS).map(([k, v]) => ({ key: k, label: v.label })) });
  });
  up.on("error", () => send(res, 200, { backend: "down", actions: Object.entries(ACTIONS).map(([k, v]) => ({ key: k, label: v.label })) }));
  up.on("timeout", () => up.destroy());
  up.end();
}

createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const path = url.pathname;
  if (path === "/" || path === "/console.html") {
    try {
      return send(res, 200, await readFile(CONSOLE_HTML, "utf8"), "text/html; charset=utf-8");
    } catch {
      return send(res, 500, "console.html not found");
    }
  }
  if (path === "/manage/status") return status(res);
  if (path.startsWith("/manage/") && req.method === "POST") return runAction(path.slice("/manage/".length), res);
  if (path.startsWith("/api/") || path.startsWith("/local/") || path.startsWith("/actuator/")) return proxy(req, res);
  send(res, 404, { error: "not found" });
}).listen(PORT, "127.0.0.1", () =>
  console.log(`tradecore dev console → http://localhost:${PORT}/console.html  (proxying API to :${BACKEND.port})`),
);
