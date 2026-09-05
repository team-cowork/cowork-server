import { readMotionDuration } from "../../design-system/tokens.js";

function appendTodoMetadata(header, metadata, priority) {
    if (!metadata.length) return;

    const list = document.createElement("dl");
    list.className = "todo-document__metadata";
    for (const entry of metadata) {
        const row = document.createElement("div");
        const term = document.createElement("dt");
        const description = document.createElement("dd");
        term.textContent = entry.label || "항목";
        if (typeof entry.html === "string" && entry.html) {
            description.innerHTML = entry.html;
        } else {
            description.textContent = entry.text || "";
        }
        if (entry.label === "우선순위") {
            const marker = document.createElement("span");
            const markerPriority = ["high", "medium", "low", "unknown"].includes(
                priority,
            )
                ? priority
                : "unknown";
            marker.className = `todo-priority-marker todo-priority-marker--${markerPriority}`;
            marker.setAttribute("aria-hidden", "true");
            description.prepend(marker, " ");
        }
        row.append(term, description);
        list.append(row);
    }
    header.append(list);
}

function renderTodoDocument(article, tocList, tocRail, documentModel) {
    const header = document.createElement("header");
    header.className = "todo-document__header";

    const title = document.createElement("h1");
    title.id = "todo-document-title";
    title.tabIndex = -1;
    title.className = "todo-document__title";
    title.textContent = documentModel.title;
    header.append(title);
    appendTodoMetadata(header, documentModel.metadata, documentModel.priority);

    const body = document.createElement("div");
    body.className = "todo-document__body";
    body.innerHTML = documentModel.bodyHtml;
    article.replaceChildren(header, body);

    const tocItems = documentModel.toc.filter(
        (entry) => entry && (entry.depth === 2 || entry.depth === 3) && entry.id,
    );
    tocList.replaceChildren(
        ...tocItems.map((entry) => {
            const item = document.createElement("li");
            item.className = `todo-toc__item todo-toc__item--depth-${entry.depth}`;
            const link = document.createElement("a");
            link.className = "todo-toc__link";
            link.href = `#${encodeURIComponent(entry.id)}`;
            link.textContent = entry.text;
            item.append(link);
            return item;
        }),
    );
    tocRail.hidden = tocItems.length === 0;
}

export function restoreFocusAndScroll({
    focus,
    scrollX,
    scrollY,
    scrollTo,
}) {
    focus?.();
    scrollTo({ left: scrollX, top: scrollY, behavior: "auto" });
}

export function createTodoDialog(dialog, options = {}) {
    const closeButton = dialog.querySelector("[data-todo-close]");
    const scroller = dialog.querySelector("[data-todo-dialog-scroller]");
    const article = dialog.querySelector("[data-todo-document]");
    const tocList = dialog.querySelector("[data-todo-toc]");
    const tocRail = dialog.querySelector("[data-todo-toc-rail]");
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let mounted = false;
    let lockedScrollX = 0;
    let lockedScrollY = 0;
    let bodyStyle = null;
    let closingPromise = null;
    let cancelClose = null;
    let renderVersion = 0;
    let renderedId = document.body.dataset.initialDocumentId || "";

    if (!closeButton || !scroller || !article || !tocList || !tocRail) {
        throw new Error("TODO dialog markup이 완전하지 않습니다.");
    }

    function lockPageScroll() {
        if (bodyStyle) return;
        lockedScrollX = window.scrollX;
        lockedScrollY = window.scrollY;
        bodyStyle = {
            position: document.body.style.position,
            top: document.body.style.top,
            width: document.body.style.width,
            overflow: document.body.style.overflow,
        };
        document.body.classList.add("todo-modal-open");
        document.body.style.position = "fixed";
        document.body.style.top = `-${lockedScrollY}px`;
        document.body.style.width = "100%";
        document.body.style.overflow = "hidden";
    }

    function unlockPageScroll(restoreFocus) {
        if (!bodyStyle) {
            restoreFocus?.();
            return;
        }
        const previous = bodyStyle;
        bodyStyle = null;
        document.body.classList.remove("todo-modal-open");
        document.body.style.position = previous.position;
        document.body.style.top = previous.top;
        document.body.style.width = previous.width;
        document.body.style.overflow = previous.overflow;
        restoreFocusAndScroll({
            focus: restoreFocus,
            scrollX: lockedScrollX,
            scrollY: lockedScrollY,
            scrollTo: (position) => window.scrollTo(position),
        });
    }

    function handleCancel(event) {
        event.preventDefault();
        options.onCloseRequest?.("escape");
    }

    function handleCloseButton(event) {
        event.preventDefault();
        options.onCloseRequest?.("button");
    }

    function mount() {
        if (mounted) return;
        dialog.addEventListener("cancel", handleCancel);
        closeButton.addEventListener("click", handleCloseButton);
        mounted = true;
    }

    function unmount() {
        if (!mounted) return;
        dialog.removeEventListener("cancel", handleCancel);
        closeButton.removeEventListener("click", handleCloseButton);
        renderVersion += 1;
        cancelClose?.();
        if (dialog.open) dialog.close();
        unlockPageScroll();
        mounted = false;
    }

    function scrollToHash(hash, smooth = false) {
        if (!hash) {
            scroller.scrollTo({ top: 0, behavior: "auto" });
            return false;
        }
        let id;
        try {
            id = decodeURIComponent(hash.replace(/^#/, ""));
        } catch {
            return false;
        }
        const target = document.getElementById(id);
        if (!target || !article.contains(target)) return false;
        const top =
            target.getBoundingClientRect().top -
            scroller.getBoundingClientRect().top +
            scroller.scrollTop;
        scroller.scrollTo({
            top,
            behavior: smooth && !reducedMotion.matches ? "smooth" : "auto",
        });
        return true;
    }

    function openDialog(hash, focus) {
        const version = ++renderVersion;
        cancelClose?.();
        dialog.classList.remove("todo-dialog--closing");

        if (dialog.hasAttribute("open") && !dialog.matches(":modal")) {
            dialog.removeAttribute("open");
        }
        if (!dialog.open) {
            lockPageScroll();
            dialog.showModal();
        }
        scroller.scrollTop = 0;
        requestAnimationFrame(() => {
            if (version !== renderVersion || !dialog.open) return;
            if (hash) scrollToHash(hash);
            if (focus) closeButton.focus({ preventScroll: true });
        });
    }

    function showDocument(documentModel, hash = "", focus = true) {
        const restoreFocus = focus || article.contains(document.activeElement);
        if (renderedId !== documentModel.id) {
            renderTodoDocument(article, tocList, tocRail, documentModel);
        }
        renderedId = documentModel.id;
        article.removeAttribute("aria-busy");
        openDialog(hash, restoreFocus);
    }

    function showStatus(documentModel, failed) {
        const focus = !dialog.open || article.contains(document.activeElement);
        renderTodoDocument(article, tocList, tocRail, {
            ...documentModel,
            metadata: [],
            toc: [],
            bodyHtml: "",
        });
        renderedId = "";
        const body = article.querySelector(".todo-document__body");
        const status = document.createElement("p");
        status.setAttribute("role", "status");
        status.textContent = failed
            ? "문서를 불러오지 못했습니다. 다시 시도해 주세요."
            : "문서를 불러오고 있습니다.";
        body.append(status);
        if (failed) {
            article.removeAttribute("aria-busy");
            const retry = document.createElement("button");
            retry.type = "button";
            retry.className = "ui-button ui-button--primary";
            retry.textContent = "다시 시도";
            retry.addEventListener("click", () => options.onRetry?.());
            const fallback = document.createElement("a");
            fallback.href = documentModel.route;
            fallback.dataset.todoNative = "";
            fallback.className = "ui-button ui-button--secondary";
            fallback.textContent = "문서 페이지 열기";
            const actions = document.createElement("div");
            actions.className = "todo-document__actions";
            actions.append(retry, fallback);
            body.append(actions);
        } else {
            article.setAttribute("aria-busy", "true");
        }
        openDialog("", focus);
    }

    function hide({ restoreFocus } = {}) {
        renderVersion += 1;
        if (!dialog.open) {
            unlockPageScroll(restoreFocus);
            return Promise.resolve();
        }
        if (closingPromise) return closingPromise;
        if (reducedMotion.matches) {
            dialog.close();
            unlockPageScroll(restoreFocus);
            return Promise.resolve();
        }
        closingPromise = new Promise((resolve) => {
            let finished = false;
            const finish = (cancelled = false) => {
                if (finished) return;
                finished = true;
                window.clearTimeout(timeout);
                dialog.removeEventListener("animationend", handleAnimationEnd);
                dialog.classList.remove("todo-dialog--closing");
                if (!cancelled) {
                    if (dialog.open) dialog.close();
                    unlockPageScroll(restoreFocus);
                }
                closingPromise = null;
                cancelClose = null;
                resolve();
            };
            const handleAnimationEnd = (event) => {
                if (event.target === dialog && event.animationName === "todo-dialog-leave") {
                    finish();
                }
            };
            cancelClose = () => finish(true);
            dialog.classList.add("todo-dialog--closing");
            const timeout = window.setTimeout(
                () => finish(),
                readMotionDuration("--duration-dialog", dialog) + 40,
            );
            dialog.addEventListener("animationend", handleAnimationEnd);
        });
        return closingPromise;
    }

    return {
        mount,
        unmount,
        showDocument,
        showLoading: (documentModel) => showStatus(documentModel, false),
        showError: (documentModel) => showStatus(documentModel, true),
        hide,
        scrollToHash,
        isOpen: () => dialog.open,
    };
}
