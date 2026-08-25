package com.cowork.gateway.controller

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// TODO(temporary health dashboard): Remove this class with the temporary /health dashboard.
@RestController
class HealthDashboardController {

    // TODO(temporary health dashboard): Remove this handler with the temporary /health dashboard.
    @GetMapping(
        value = ["/health"],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    fun healthDashboard(): ResponseEntity<String> = ResponseEntity
        .ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.TEXT_HTML)
        .body(HEALTH_DASHBOARD_HTML)

    private companion object {
        private val HEALTH_DASHBOARD_HTML =
            """
            <!doctype html>
            <!-- TODO(temporary health dashboard): Remove this markup with the temporary /health dashboard. -->
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="light">
              <title>Cowork Service Health</title>
              <style>
                /* TODO(temporary health dashboard): Remove these styles with the temporary /health dashboard. */
                :root {
                  --paper: #ffffff;
                  --ink: #111111;
                  --rule: #d7d7d2;
                  --up: #18864b;
                  --degraded: #c76a00;
                  --down: #c53434;
                  color-scheme: light;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background: var(--paper);
                  color: var(--ink);
                }

                * {
                  box-sizing: border-box;
                }

                body {
                  min-width: 320px;
                  margin: 0;
                  background: var(--paper);
                }

                button {
                  font: inherit;
                }

                .shell {
                  width: min(100%, 880px);
                  margin: 0 auto;
                  padding: clamp(28px, 7vw, 72px) clamp(20px, 5vw, 48px) 56px;
                }

                .masthead {
                  display: flex;
                  align-items: flex-end;
                  justify-content: space-between;
                  gap: 24px;
                  padding-bottom: 22px;
                  border-bottom: 1px solid var(--ink);
                }

                h1 {
                  margin: 0;
                  font-size: clamp(1rem, 2.7vw, 1.35rem);
                  font-weight: 650;
                  line-height: 1;
                  letter-spacing: 0.075em;
                }

                h1 span {
                  color: rgb(17 17 17 / 48%);
                  font-weight: 450;
                }

                .refresh {
                  min-height: 32px;
                  padding: 0 0 3px;
                  border: 0;
                  border-bottom: 1px solid currentColor;
                  border-radius: 0;
                  background: transparent;
                  color: var(--ink);
                  cursor: pointer;
                  font-size: 0.8rem;
                  font-weight: 600;
                  letter-spacing: 0.01em;
                }

                .refresh:hover {
                  color: rgb(17 17 17 / 60%);
                }

                .refresh:focus-visible {
                  outline: 2px solid var(--ink);
                  outline-offset: 5px;
                }

                .refresh:disabled {
                  border-color: var(--rule);
                  color: rgb(17 17 17 / 42%);
                  cursor: wait;
                }

                .readout {
                  min-height: 58px;
                  display: flex;
                  align-items: center;
                  padding: 15px 0;
                  border-bottom: 1px solid var(--rule);
                }

                .meta {
                  display: flex;
                  align-items: baseline;
                  gap: 10px;
                  margin: 0;
                  color: rgb(17 17 17 / 58%);
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                  font-size: 0.73rem;
                  line-height: 1.6;
                }

                .meta::before {
                  width: 5px;
                  height: 5px;
                  flex: 0 0 auto;
                  border-radius: 50%;
                  background: var(--ink);
                  content: "";
                }

                .meta.is-error {
                  color: var(--down);
                }

                .meta.is-error::before {
                  background: var(--down);
                }

                .signal-board {
                  position: relative;
                }

                .service-list {
                  position: relative;
                  margin: 0;
                  padding: 0;
                  list-style: none;
                  transition: opacity 160ms ease;
                }

                .service-list::before {
                  position: absolute;
                  z-index: 0;
                  top: 0;
                  bottom: 0;
                  left: 5px;
                  width: 1px;
                  background: var(--rule);
                  content: "";
                }

                .service-list.is-stale {
                  opacity: 0.38;
                }

                .service-row {
                  position: relative;
                  display: grid;
                  grid-template-columns: 11px minmax(0, 1fr) auto;
                  align-items: center;
                  gap: 18px;
                  min-height: 62px;
                  border-bottom: 1px solid var(--rule);
                }

                .signal {
                  z-index: 1;
                  width: 11px;
                  height: 11px;
                  border: 3px solid var(--paper);
                  border-radius: 50%;
                  background: currentColor;
                  box-shadow: 0 0 0 1px currentColor;
                }

                .service-name,
                .service-status {
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                  font-size: 0.78rem;
                }

                .service-name {
                  overflow: hidden;
                  color: var(--ink);
                  font-weight: 550;
                  letter-spacing: -0.01em;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                }

                .service-status {
                  font-weight: 700;
                  letter-spacing: 0.055em;
                }

                .status-up {
                  color: var(--up);
                }

                .status-degraded {
                  color: var(--degraded);
                }

                .status-down {
                  color: var(--down);
                }

                .empty-state {
                  margin: 0;
                  padding: 30px 0 28px 29px;
                  border-bottom: 1px solid var(--rule);
                  color: rgb(17 17 17 / 58%);
                  font-size: 0.82rem;
                  line-height: 1.6;
                }

                [hidden] {
                  display: none !important;
                }

                @media (max-width: 520px) {
                  .masthead {
                    align-items: flex-start;
                  }

                  h1 {
                    max-width: 190px;
                    line-height: 1.35;
                  }

                  .service-row {
                    gap: 13px;
                  }

                  .service-name,
                  .service-status {
                    font-size: 0.72rem;
                  }
                }

                @media (prefers-reduced-motion: reduce) {
                  .service-list {
                    transition: none;
                  }
                }
              </style>
            </head>
            <body>
              <main class="shell">
                <header class="masthead">
                  <h1><span>COWORK /</span> SERVICE HEALTH</h1>
                  <button id="refresh" class="refresh" type="button">다시 확인</button>
                </header>

                <div class="readout" aria-live="polite" aria-atomic="true">
                  <p id="meta" class="meta">서비스 상태를 확인하고 있어요.</p>
                </div>

                <section id="signal-board" class="signal-board" aria-label="모듈별 서비스 상태" aria-busy="true">
                  <p id="empty-state" class="empty-state">서비스 상태를 확인하고 있어요.</p>
                  <ul id="service-list" class="service-list" hidden></ul>
                </section>
              </main>

              <script>
                // TODO(temporary health dashboard): Remove this script with the temporary /health dashboard.
                (() => {
                  "use strict";

                  const HEALTH_API_PATH = "/api/health";
                  const REFRESH_INTERVAL_MS = 10000;
                  const REQUEST_TIMEOUT_MS = 5000;
                  const ALLOWED_STATUSES = new Set(["UP", "DEGRADED", "DOWN"]);

                  const refreshButton = document.getElementById("refresh");
                  const signalBoard = document.getElementById("signal-board");
                  const serviceList = document.getElementById("service-list");
                  const emptyState = document.getElementById("empty-state");
                  const meta = document.getElementById("meta");

                  let activeRequest = false;
                  let pollTimer = null;
                  let lastServices = null;
                  let lastSuccessfulAt = null;

                  function pad(value) {
                    return String(value).padStart(2, "0");
                  }

                  function formatLocalTime(date) {
                    return [
                      date.getFullYear(),
                      "-",
                      pad(date.getMonth() + 1),
                      "-",
                      pad(date.getDate()),
                      " ",
                      pad(date.getHours()),
                      ":",
                      pad(date.getMinutes()),
                      ":",
                      pad(date.getSeconds()),
                    ].join("");
                  }

                  function setLoading(loading) {
                    activeRequest = loading;
                    refreshButton.disabled = loading;
                    refreshButton.textContent = loading ? "확인 중…" : "다시 확인";
                    signalBoard.setAttribute("aria-busy", String(loading));
                  }

                  function showMeta(message, error) {
                    meta.textContent = message;
                    meta.classList.toggle("is-error", error);
                  }

                  function showEmpty(message) {
                    emptyState.textContent = message;
                    emptyState.hidden = false;
                    serviceList.hidden = true;
                  }

                  function createServiceRow(serviceId, status) {
                    const row = document.createElement("li");
                    const signal = document.createElement("span");
                    const name = document.createElement("span");
                    const statusText = document.createElement("span");
                    const statusClass = "status-" + status.toLowerCase();

                    row.className = "service-row";
                    signal.className = "signal " + statusClass;
                    signal.setAttribute("aria-hidden", "true");
                    name.className = "service-name";
                    name.textContent = serviceId;
                    name.title = serviceId;
                    statusText.className = "service-status " + statusClass;
                    statusText.textContent = status;

                    row.append(signal, name, statusText);
                    return row;
                  }

                  function renderServices(services) {
                    serviceList.replaceChildren();
                    serviceList.classList.remove("is-stale");

                    if (services.length === 0) {
                      showEmpty("확인할 서비스가 없어요.");
                      return;
                    }

                    const rows = services.map(([serviceId, status]) => createServiceRow(serviceId, status));
                    serviceList.append(...rows);
                    emptyState.hidden = true;
                    serviceList.hidden = false;
                  }

                  function parseServices(payload) {
                    if (
                      payload === null ||
                      typeof payload !== "object" ||
                      payload.code !== 200 ||
                      payload.data === null ||
                      typeof payload.data !== "object" ||
                      Array.isArray(payload.data)
                    ) {
                      throw { kind: "payload" };
                    }

                    const services = Object.entries(payload.data);
                    const valid = services.every(
                      ([serviceId, status]) =>
                        typeof serviceId === "string" &&
                        typeof status === "string" &&
                        ALLOWED_STATUSES.has(status),
                    );

                    if (!valid) {
                      throw { kind: "payload" };
                    }

                    return services.sort(([left], [right]) => left.localeCompare(right, "en"));
                  }

                  function errorMessage(error) {
                    if (error && error.kind === "timeout") {
                      return "응답을 기다리다 시간이 초과됐어요.";
                    }
                    if (error && error.kind === "payload") {
                      return "헬스 체크 응답을 읽지 못했어요.";
                    }
                    if (error && error.kind === "network") {
                      return "헬스 체크 API에 연결하지 못했어요.";
                    }
                    if (error && error.kind === "http") {
                      if (error.status === 404) {
                        return "헬스 체크 API를 찾지 못했어요. (HTTP 404)";
                      }
                      if (error.status === 429) {
                        return "요청이 너무 많아요. 잠시 후 다시 확인할게요. (HTTP 429)";
                      }
                      return "헬스 체크 중 알 수 없는 오류가 발생했어요. (HTTP " + error.status + ")";
                    }
                    return "헬스 체크 중 알 수 없는 오류가 발생했어요.";
                  }

                  function renderFailure(error) {
                    let message = errorMessage(error);
                    if (lastSuccessfulAt !== null) {
                      message += " 마지막 확인 " + formatLocalTime(lastSuccessfulAt);
                    }
                    showMeta(message, true);

                    if (lastServices === null) {
                      emptyState.hidden = true;
                      serviceList.hidden = true;
                      return;
                    }

                    if (lastServices.length === 0) {
                      showEmpty("확인할 서비스가 없어요.");
                    } else {
                      serviceList.classList.add("is-stale");
                    }
                  }

                  async function refreshHealth() {
                    if (activeRequest) {
                      return;
                    }

                    const controller = new AbortController();
                    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
                    setLoading(true);

                    try {
                      const response = await fetch(HEALTH_API_PATH, {
                        cache: "no-store",
                        headers: { Accept: "application/json" },
                        signal: controller.signal,
                      });

                      if (!response.ok) {
                        throw { kind: "http", status: response.status };
                      }

                      let payload;
                      try {
                        payload = await response.json();
                      } catch (_) {
                        throw { kind: "payload" };
                      }

                      const services = parseServices(payload);
                      lastServices = services;
                      lastSuccessfulAt = new Date();
                      renderServices(services);
                      showMeta("마지막 확인 " + formatLocalTime(lastSuccessfulAt), false);
                    } catch (error) {
                      if (error && error.name === "AbortError") {
                        renderFailure({ kind: "timeout" });
                      } else if (error && error.kind) {
                        renderFailure(error);
                      } else if (error instanceof TypeError) {
                        renderFailure({ kind: "network" });
                      } else {
                        renderFailure({ kind: "unknown" });
                      }
                    } finally {
                      window.clearTimeout(timeout);
                      setLoading(false);
                    }
                  }

                  function startPolling() {
                    if (pollTimer !== null) {
                      window.clearInterval(pollTimer);
                    }
                    pollTimer = document.hidden
                      ? null
                      : window.setInterval(refreshHealth, REFRESH_INTERVAL_MS);
                  }

                  refreshButton.addEventListener("click", refreshHealth);
                  document.addEventListener("visibilitychange", () => {
                    if (!document.hidden) {
                      refreshHealth();
                    }
                    startPolling();
                  });

                  refreshHealth();
                  startPolling();
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
    }
}
