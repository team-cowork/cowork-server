const todoPriorityRank = Object.freeze({ high: 0, medium: 1, low: 2, unknown: 3 });

export function normalizeTodoSearch(value) {
    return String(value ?? "")
        .normalize("NFKC")
        .toLocaleLowerCase("ko")
        .replace(/\s+/g, " ")
        .trim();
}

export function todoQueryTokens(value) {
    const normalized = normalizeTodoSearch(value);
    return normalized ? normalized.split(" ") : [];
}

function todoDocumentSearchText(documentModel) {
    if (documentModel.searchText) return normalizeTodoSearch(documentModel.searchText);

    const metadataText = Array.isArray(documentModel.metadata)
        ? documentModel.metadata
              .filter((entry) => entry?.label === "서비스")
              .map((entry) => entry.text ?? "")
              .join(" ")
        : "";
    return normalizeTodoSearch(
        [documentModel.title, documentModel.category, metadataText].join(" "),
    );
}

function validateTodoDocument(documentModel, index) {
    if (!documentModel || typeof documentModel !== "object") {
        throw new Error(`TODO 문서 ${index + 1}의 형식이 올바르지 않습니다.`);
    }
    for (const key of ["id", "route", "title", "kind", "contentUrl"]) {
        if (typeof documentModel[key] !== "string" || !documentModel[key]) {
            throw new Error(`TODO 문서 ${index + 1}에 ${key} 값이 없습니다.`);
        }
    }
    if (!documentModel.route.startsWith("/todo/")) {
        throw new Error(`TODO 문서 route가 /todo/ 밖을 가리킵니다: ${documentModel.route}`);
    }

    const priority = Object.hasOwn(todoPriorityRank, documentModel.priority)
        ? documentModel.priority
        : "unknown";
    return {
        ...documentModel,
        priority,
        priorityLabel:
            documentModel.priorityLabel ||
            ({ high: "높음", medium: "중간", low: "낮음", unknown: "미지정" }[
                priority
            ] ?? "미지정"),
        sourceOrder: Number.isFinite(documentModel.sourceOrder)
            ? documentModel.sourceOrder
            : index,
        searchText: todoDocumentSearchText(documentModel),
    };
}

function resolveTodoCollection(references, documents, fallback) {
    if (!Array.isArray(references)) return fallback;

    const byId = new Map(documents.map((documentModel) => [documentModel.id, documentModel]));
    const byRoute = new Map(
        documents.map((documentModel) => [documentModel.route, documentModel]),
    );
    return references
        .map((reference) => {
            if (reference && typeof reference === "object") {
                return byId.get(reference.id) || byRoute.get(reference.route);
            }
            return byId.get(reference) || byRoute.get(reference);
        })
        .filter(Boolean);
}

async function fetchTodoJson(path) {
    const url = new URL(path, window.location.href);
    if (url.origin !== window.location.origin || !url.pathname.startsWith("/assets/")) {
        throw new Error("TODO 데이터 경로가 올바르지 않습니다.");
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 12_000);
    try {
        const response = await fetch(url, { signal: controller.signal });
        if (!response.ok) throw new Error(`TODO 데이터를 불러오지 못했습니다: ${response.status}`);
        return await response.json();
    } finally {
        window.clearTimeout(timeout);
    }
}

export async function loadTodoRegistry() {
    const element = document.querySelector("link[data-todo-registry]");
    if (!element) throw new Error("TODO 레지스트리 경로를 찾을 수 없습니다.");
    const payload = await fetchTodoJson(element.href);
    const sourceDocuments = Array.isArray(payload) ? payload : payload.documents;
    if (!Array.isArray(sourceDocuments)) {
        throw new Error("TODO 레지스트리에 documents 배열이 없습니다.");
    }

    const documents = sourceDocuments.map(validateTodoDocument);
    const ids = new Set();
    const routes = new Set();
    for (const documentModel of documents) {
        if (ids.has(documentModel.id)) {
            throw new Error(`중복 TODO id입니다: ${documentModel.id}`);
        }
        if (routes.has(documentModel.route)) {
            throw new Error(`중복 TODO route입니다: ${documentModel.route}`);
        }
        ids.add(documentModel.id);
        routes.add(documentModel.route);
    }

    const defaultActiveItems = documents.filter(
        (documentModel) => documentModel.kind === "item" && documentModel.active,
    );
    const defaultHistory = documents.filter(
        (documentModel) => documentModel.kind === "snapshot",
    );
    const activeItems = resolveTodoCollection(
        Array.isArray(payload) ? null : payload.activeItems,
        documents,
        defaultActiveItems,
    ).sort(
        (left, right) =>
            todoPriorityRank[left.priority] - todoPriorityRank[right.priority] ||
            left.sourceOrder - right.sourceOrder,
    );
    const history = resolveTodoCollection(
        Array.isArray(payload) ? null : payload.history,
        documents,
        defaultHistory,
    ).sort((left, right) => left.sourceOrder - right.sourceOrder);

    const contentCache = new Map();
    const pendingRequests = new Map();
    const documentById = new Map(documents.map((documentModel) => [documentModel.id, documentModel]));

    function seedInitialDocument() {
        const initial = documentById.get(document.body.dataset.initialDocumentId);
        const article = document.querySelector("[data-todo-document]");
        const body = article?.querySelector(".todo-document__body");
        if (!initial || !body) return;
        const metadata = Array.from(article.querySelectorAll(".todo-document__metadata > div"), (row) => {
            const value = row.querySelector("dd").cloneNode(true);
            value.querySelector(".todo-priority-marker")?.remove();
            return { label: row.querySelector("dt").textContent, text: value.textContent.trim(), html: value.innerHTML.trim() };
        });
        const toc = Array.from(body.querySelectorAll("h2[id], h3[id]"), (heading) => ({
            id: heading.id, depth: Number(heading.tagName.slice(1)), text: heading.textContent,
        }));
        contentCache.set(initial.id, { ...initial, metadata, toc, bodyHtml: body.innerHTML });
    }

    async function loadDocument(documentModel) {
        if (contentCache.has(documentModel.id)) return contentCache.get(documentModel.id);
        if (pendingRequests.has(documentModel.id)) return pendingRequests.get(documentModel.id);
        const pending = fetchTodoJson(documentModel.contentUrl).then((content) => {
            if (content?.id !== documentModel.id || content.route !== documentModel.route ||
                typeof content.bodyHtml !== "string" || !Array.isArray(content.metadata) || !Array.isArray(content.toc)) {
                throw new Error("TODO 문서 내용의 형식이 올바르지 않습니다.");
            }
            const loaded = { ...documentModel, metadata: content.metadata, toc: content.toc, bodyHtml: content.bodyHtml };
            contentCache.set(documentModel.id, loaded);
            return loaded;
        }).finally(() => pendingRequests.delete(documentModel.id));
        pendingRequests.set(documentModel.id, pending);
        return pending;
    }

    seedInitialDocument();
    return {
        documents,
        activeItems,
        history,
        documentById,
        loadDocument,
        getCachedDocument: (id) => contentCache.get(id),
        documentByRoute: new Map(
            documents.map((documentModel) => [documentModel.route, documentModel]),
        ),
    };
}
