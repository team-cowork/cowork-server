import { normalizeTodoSearch, todoQueryTokens } from "../data/load-todos.js";

function createTodoListRow(documentModel, historyRow = false) {
    const item = document.createElement("li");
    item.className = "todo-list__item";
    item.dataset.todoId = documentModel.id;

    const link = document.createElement("a");
    link.className = "todo-list__link";
    link.href = documentModel.route;
    link.dataset.todoRoute = documentModel.route;

    const title = document.createElement("span");
    title.className = "todo-list__title";
    title.textContent = documentModel.listTitle || documentModel.title;
    link.append(title);

    if (!historyRow) {
        const priority = document.createElement("span");
        priority.className = `todo-list__priority todo-list__priority--${documentModel.priority}`;
        priority.textContent = documentModel.priorityLabel;
        link.append(priority);
    }

    item.append(link);
    return item;
}

function ensureTodoRows(list, documentModels, historyRow = false) {
    const existingIds = new Set(
        Array.from(list.querySelectorAll("[data-todo-id]"), (row) => row.dataset.todoId),
    );
    if (
        existingIds.size === documentModels.length &&
        documentModels.every((documentModel) => existingIds.has(documentModel.id))
    ) {
        return;
    }

    list.replaceChildren(
        ...documentModels.map((documentModel) =>
            createTodoListRow(documentModel, historyRow),
        ),
    );
}

export function createTodoDashboard(root, registry, options = {}) {
    const input = root.querySelector("[data-todo-search]");
    const form = input?.closest("form");
    const itemList = root.querySelector("[data-todo-items]");
    const historyList = root.querySelector("[data-todo-history]");
    const empty = root.querySelector("[data-todo-empty]");
    const live = root.querySelector("[data-todo-live]");
    let mounted = false;
    let currentQuery = "";

    if (!input || !form || !itemList || !historyList || !empty || !live) {
        throw new Error("TODO dashboard markup이 완전하지 않습니다.");
    }

    function filteredItemIds(query) {
        const tokens = todoQueryTokens(query);
        if (tokens.length === 0) {
            return new Set(registry.activeItems.map((documentModel) => documentModel.id));
        }
        return new Set(
            registry.activeItems
                .filter((documentModel) =>
                    tokens.every((token) => documentModel.searchText.includes(token)),
                )
                .map((documentModel) => documentModel.id),
        );
    }

    function applyQuery(query, { announce = false, notify = false } = {}) {
        currentQuery = normalizeTodoSearch(query);
        if (input.value !== query) input.value = query;

        const visibleIds = filteredItemIds(currentQuery);
        for (const row of itemList.querySelectorAll("[data-todo-id]")) {
            row.hidden = !visibleIds.has(row.dataset.todoId);
        }

        const hasResults = visibleIds.size > 0;
        itemList.hidden = !hasResults;
        empty.hidden = hasResults;
        if (announce) {
            live.textContent = hasResults
                ? `${visibleIds.size}개의 작업이 표시됩니다.`
                : "검색 결과가 없습니다.";
        }
        if (notify) options.onQueryChange?.(currentQuery);
    }

    function handleInput() {
        applyQuery(input.value, { announce: true, notify: true });
    }

    function handleSubmit(event) {
        event.preventDefault();
        applyQuery(input.value, { announce: true, notify: true });
    }

    function mount(initialQuery = "") {
        if (mounted) return;
        ensureTodoRows(itemList, registry.activeItems);
        ensureTodoRows(historyList, registry.history, true);
        input.addEventListener("input", handleInput);
        form.addEventListener("submit", handleSubmit);
        applyQuery(initialQuery);
        mounted = true;
    }

    function unmount() {
        if (!mounted) return;
        input.removeEventListener("input", handleInput);
        form.removeEventListener("submit", handleSubmit);
        mounted = false;
    }

    function setQuery(query, announce = false) {
        applyQuery(query, { announce });
    }

    function focusSearch() {
        input.focus({ preventScroll: true });
    }

    return {
        mount,
        unmount,
        setQuery,
        focusSearch,
        query: () => currentQuery,
    };
}
