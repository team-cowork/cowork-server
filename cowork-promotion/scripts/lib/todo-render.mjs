import { escapeHtml } from "./validation.mjs";

const priorityClasses = new Set(["high", "medium", "low", "unknown"]);

function priorityClass(priority) {
    return priorityClasses.has(priority) ? priority : "unknown";
}

export function renderTodoItem(item) {
    const route = escapeHtml(item.route);
    const id = escapeHtml(item.id);
    const priority = priorityClass(item.priority);

    return `      <li class="todo-list__item" data-todo-id="${id}">
        <a class="todo-list__link" href="${route}" data-todo-route="${route}">
          <span class="todo-list__title">${escapeHtml(item.title)}</span>
          <span class="todo-list__priority todo-list__priority--${priority}">${escapeHtml(item.priorityLabel)}</span>
        </a>
      </li>`;
}

export function renderTodoItems(items) {
    return items.map(renderTodoItem).join("\n");
}

export function renderTodoHistoryItem(snapshot) {
    const route = escapeHtml(snapshot.route);
    const id = escapeHtml(snapshot.id);
    const label = snapshot.description
        ? `${snapshot.displayDate} — ${snapshot.description}`
        : snapshot.displayDate || snapshot.title;

    return `      <li class="todo-list__item" data-todo-id="${id}">
        <a class="todo-list__link" href="${route}" data-todo-route="${route}">
          <span class="todo-list__title">${escapeHtml(label)}</span>
        </a>
      </li>`;
}

export function renderTodoHistory(snapshots) {
    return snapshots.map(renderTodoHistoryItem).join("\n");
}

function renderPriorityMarker(priority) {
    const value = priorityClass(priority);
    return `<span class="todo-priority-marker todo-priority-marker--${value}" aria-hidden="true"></span> `;
}

function renderMetadata(metadata, documentPriority) {
    if (!Array.isArray(metadata) || metadata.length === 0) return "";

    const entries = metadata
        .map(
            (entry) => `        <div>
          <dt>${escapeHtml(entry.label)}</dt>
          <dd>${entry.label === "우선순위" ? renderPriorityMarker(documentPriority) : ""}${entry.html}</dd>
        </div>`,
        )
        .join("\n");

    return `      <dl class="todo-document__metadata">
${entries}
      </dl>`;
}

export function renderTodoDocument(document) {
    const metadata = renderMetadata(document.metadata, document.priority);

    return `    <header class="todo-document__header">
      <h1 id="todo-document-title" class="todo-document__title" tabindex="-1">${escapeHtml(document.title)}</h1>
${metadata}
    </header>
    <div class="todo-document__body">${document.bodyHtml}</div>`;
}

export function renderTodoToc(toc) {
    return toc
        .map((entry) => {
            const depth = entry.depth === 3 ? 3 : 2;
            const href = `#${encodeURIComponent(entry.id)}`;

            return `              <li class="todo-toc__item todo-toc__item--depth-${depth}">
                <a class="todo-toc__link" href="${escapeHtml(href)}">${escapeHtml(entry.text)}</a>
              </li>`;
        })
        .join("\n");
}
