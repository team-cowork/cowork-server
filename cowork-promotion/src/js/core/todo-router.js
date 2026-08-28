const todoModalStateKey = "__coworkTodoModal";

function normalizedTodoPath(pathname) {
    if (pathname === "/todo/") return "/todo";
    return pathname.length > 1 ? pathname.replace(/\/$/, "") : pathname;
}

function canHandleTodoAnchor(event, anchor) {
    return (
        !event.defaultPrevented &&
        event.button === 0 &&
        !event.metaKey &&
        !event.ctrlKey &&
        !event.shiftKey &&
        !event.altKey &&
        !anchor.hasAttribute("download") &&
        (!anchor.target || anchor.target === "_self")
    );
}

export function createTodoRouter({ root, registry, dashboard, dialog }) {
    let mounted = false;
    let opener = null;
    let syncing = false;
    let syncRequested = false;
    let focusRequested = false;

    function setDocumentTitle(documentModel = null) {
        const title = documentModel
            ? `${documentModel.title} · cowork`
            : "개발 진행 현황 · cowork";
        document.title = title;
        document
            .querySelector('meta[property="og:title"]')
            ?.setAttribute("content", title);
    }

    function currentLocation() {
        const url = new URL(window.location.href);
        return {
            url,
            path: normalizedTodoPath(url.pathname),
            documentModel: registry.documentByRoute.get(
                normalizedTodoPath(url.pathname),
            ),
        };
    }

    async function applyCurrentLocation(focusAfterClose) {
        const { url, path, documentModel } = currentLocation();
        if (path === "/todo") {
            dashboard.setQuery(url.searchParams.get("q") || "");
            setDocumentTitle();
            document.body.dataset.initialDocumentId = "";
            root.hidden = false;
            const wasOpen = dialog.isOpen();
            const shouldRestoreFocus = wasOpen || focusAfterClose;
            const focusTarget = opener?.isConnected ? opener : null;
            await dialog.hide({
                restoreFocus: shouldRestoreFocus
                    ? () => {
                          if (focusTarget?.isConnected) {
                              focusTarget.focus({ preventScroll: true });
                          } else {
                              dashboard.focusSearch();
                          }
                      }
                    : null,
            });
            opener = null;
            return;
        }

        if (!documentModel) return;
        setDocumentTitle(documentModel);
        dialog.showDocument(documentModel, url.hash, true);
        root.hidden = false;
        document.body.dataset.initialDocumentId = documentModel.id;
    }

    async function syncFromLocation({ focusAfterClose = false } = {}) {
        focusRequested ||= focusAfterClose;
        if (syncing) {
            syncRequested = true;
            return;
        }
        syncing = true;
        try {
            do {
                syncRequested = false;
                const shouldFocus = focusRequested;
                focusRequested = false;
                await applyCurrentLocation(shouldFocus);
            } while (syncRequested);
        } finally {
            syncing = false;
        }
    }

    function replaceSearchQuery(query) {
        const { path } = currentLocation();
        if (path !== "/todo") return;
        const url = new URL(window.location.href);
        if (query) url.searchParams.set("q", query);
        else url.searchParams.delete("q");
        url.hash = "";
        window.history.replaceState(window.history.state, "", url);
    }

    function openDocumentRoute(url, clickedAnchor) {
        const targetPath = normalizedTodoPath(url.pathname);
        const documentModel = registry.documentByRoute.get(targetPath);
        if (!documentModel) return false;

        const currentlyOpen = dialog.isOpen();
        if (!currentlyOpen) opener = clickedAnchor;
        const target = `${documentModel.route}${url.hash}`;
        const nextState = {
            ...(window.history.state || {}),
            [todoModalStateKey]: currentlyOpen
                ? Boolean(window.history.state?.[todoModalStateKey])
                : true,
        };
        if (currentlyOpen) window.history.replaceState(nextState, "", target);
        else window.history.pushState(nextState, "", target);
        syncFromLocation();
        return true;
    }

    function replaceDocumentHash(url) {
        const target = `${normalizedTodoPath(url.pathname)}${url.hash}`;
        window.history.replaceState(window.history.state, "", target);
        dialog.scrollToHash(url.hash, true);
    }

    function handleDocumentClick(event) {
        const anchor = event.target.closest?.("a[href]");
        if (!anchor || !canHandleTodoAnchor(event, anchor)) return;

        const url = new URL(anchor.href, window.location.href);
        if (url.origin !== window.location.origin) return;
        const current = currentLocation();
        const targetPath = normalizedTodoPath(url.pathname);

        if (
            dialog.isOpen() &&
            targetPath === current.path &&
            url.hash &&
            registry.documentByRoute.has(targetPath)
        ) {
            event.preventDefault();
            replaceDocumentHash(url);
            return;
        }

        if (targetPath === "/todo" && dialog.isOpen()) {
            event.preventDefault();
            requestClose();
            return;
        }

        if (registry.documentByRoute.has(targetPath)) {
            event.preventDefault();
            openDocumentRoute(url, anchor);
        }
    }

    function requestClose() {
        if (!dialog.isOpen()) return;
        if (window.history.state?.[todoModalStateKey]) {
            window.history.back();
            return;
        }

        window.history.replaceState({}, "", "/todo");
        syncFromLocation({ focusAfterClose: true });
    }

    function handlePopState() {
        syncFromLocation({ focusAfterClose: true });
    }

    function mount() {
        if (mounted) return;
        document.addEventListener("click", handleDocumentClick);
        window.addEventListener("popstate", handlePopState);
        mounted = true;
        syncFromLocation();
    }

    function unmount() {
        if (!mounted) return;
        document.removeEventListener("click", handleDocumentClick);
        window.removeEventListener("popstate", handlePopState);
        mounted = false;
    }

    return {
        mount,
        unmount,
        requestClose,
        replaceSearchQuery,
    };
}
