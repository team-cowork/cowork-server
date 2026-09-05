import { setPageMetadata, todoPageMetadata } from "./page-metadata.js";

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
        !anchor.hasAttribute("data-todo-native") &&
        (!anchor.target || anchor.target === "_self")
    );
}

export function createTodoRouter({ root, registry, dashboard, dialog }) {
    let mounted = false;
    let opener = null;
    let navigationVersion = 0;

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

    async function syncFromLocation({ focusAfterClose = false } = {}) {
        const version = ++navigationVersion;
        const isCurrent = () => mounted && version === navigationVersion;
        const { url, path, documentModel } = currentLocation();
        if (path === "/todo") {
            dashboard.setQuery(url.searchParams.get("q") || "");
            setPageMetadata(todoPageMetadata());
            document.body.dataset.initialDocumentId = "";
            root.hidden = false;
            const wasOpen = dialog.isOpen();
            const shouldRestoreFocus = wasOpen || focusAfterClose;
            const focusTarget = opener?.isConnected ? opener : null;
            await dialog.hide({
                restoreFocus: shouldRestoreFocus
                    ? () => {
                          if (!isCurrent()) return;
                          if (focusTarget?.isConnected) {
                              focusTarget.focus({ preventScroll: true });
                          } else {
                              dashboard.focusSearch();
                          }
                      }
                    : null,
            });
            if (isCurrent()) opener = null;
            return;
        }

        if (!documentModel) return;
        setPageMetadata(todoPageMetadata(documentModel));
        const cached = registry.getCachedDocument(documentModel.id);
        if (cached) dialog.showDocument(cached, url.hash, true);
        else dialog.showLoading(documentModel);
        root.hidden = false;
        document.body.dataset.initialDocumentId = documentModel.id;
        if (cached) return;
        try {
            const content = await registry.loadDocument(documentModel);
            if (isCurrent()) {
                dialog.showDocument(content, window.location.hash, false);
            }
        } catch (error) {
            if (isCurrent()) {
                console.error("TODO 문서를 불러오지 못했습니다.", error);
                dialog.showError(documentModel);
            }
        }
    }

    function handlePrefetch(event) {
        const anchor = event.target.closest?.("a[href]");
        if (
            !anchor ||
            anchor.hasAttribute("data-todo-native") ||
            navigator.connection?.saveData ||
            /(^|-)2g$/.test(navigator.connection?.effectiveType || "")
        ) return;
        if (
            event.type === "pointerover" &&
            (event.pointerType === "touch" || anchor.contains(event.relatedTarget))
        ) return;
        const url = new URL(anchor.href, window.location.href);
        if (url.origin !== window.location.origin) return;
        const entry = registry.documentByRoute.get(normalizedTodoPath(url.pathname));
        if (entry) registry.loadDocument(entry).catch(() => {});
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
        document.addEventListener("pointerover", handlePrefetch);
        document.addEventListener("focusin", handlePrefetch);
        window.addEventListener("popstate", handlePopState);
        mounted = true;
        syncFromLocation();
    }

    function unmount() {
        if (!mounted) return;
        document.removeEventListener("click", handleDocumentClick);
        document.removeEventListener("pointerover", handlePrefetch);
        document.removeEventListener("focusin", handlePrefetch);
        window.removeEventListener("popstate", handlePopState);
        mounted = false;
        navigationVersion += 1;
    }

    return {
        mount,
        unmount,
        requestClose,
        replaceSearchQuery,
        retry: () => syncFromLocation(),
    };
}
