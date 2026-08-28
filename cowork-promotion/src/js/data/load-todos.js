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
    for (const key of ["id", "route", "title", "kind"]) {
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
        metadata: Array.isArray(documentModel.metadata) ? documentModel.metadata : [],
        toc: Array.isArray(documentModel.toc) ? documentModel.toc : [],
        bodyHtml: typeof documentModel.bodyHtml === "string" ? documentModel.bodyHtml : "",
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

function embeddedTodoRegistry() {
    const element = document.querySelector(
        'script[type="application/json"][data-todo-registry]',
    );
    if (!element) throw new Error("인라인 TODO 레지스트리를 찾을 수 없습니다.");

    try {
        return JSON.parse(element.textContent);
    } catch (error) {
        throw new Error("인라인 TODO 레지스트리를 해석할 수 없습니다.", {
            cause: error,
        });
    }
}

export function loadTodoRegistry() {
    const payload = embeddedTodoRegistry();
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

    return {
        documents,
        activeItems,
        history,
        documentById: new Map(documents.map((documentModel) => [documentModel.id, documentModel])),
        documentByRoute: new Map(
            documents.map((documentModel) => [documentModel.route, documentModel]),
        ),
    };
}
