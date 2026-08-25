// TODO(temporary health dashboard): Remove this file with the temporary /health dashboard.
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
