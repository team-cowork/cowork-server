import { renderRepositoryCard } from "../../src/design-system/index.mjs";
import {
    homePageDescription,
    todoPageMetadata,
} from "../../src/js/core/page-metadata.js";
import { inlineJson, replaceBundleMarker } from "./bundle.mjs";
import { renderTeamMembers, renderTechStacks } from "./render.mjs";
import { renderShowcase } from "./showcase-render.mjs";
import { replaceGeneratedRegion } from "./template.mjs";
import {
    renderTodoDocument,
    renderTodoHistory,
    renderTodoItems,
    renderTodoToc,
} from "./todo-render.mjs";
import { escapeHtml } from "./validation.mjs";

export function concatenateStyles(paths, sources) {
    return sources
        .map((source, index) => `/* ${paths[index]} */\n${source}`)
        .join("\n");
}

function bundlePage({ html, styles, stateData, script, logoUrl }) {
    return replaceBundleMarker(
        replaceBundleMarker(
            replaceBundleMarker(
                html,
                "styles",
                styles
                    .map((url) => `<link rel="stylesheet" href="${url}" />`)
                    .join("\n"),
            ),
            "state-data",
            stateData,
        ),
        "script",
        `<script type="module" src="${script}"></script>`,
    ).replace('href="/logo.svg"', `href="${logoUrl}"`);
}

function renderPageMetadata(html, metadata, siteUrl) {
    const url = siteUrl ? new URL(metadata.route, siteUrl).href : metadata.route;

    return replaceBundleMarker(html, "metadata", `
    <title>${escapeHtml(metadata.title)}</title>
    <meta name="description" content="${escapeHtml(metadata.description)}" />
    <meta property="og:title" content="${escapeHtml(metadata.title)}" />
    <meta property="og:description" content="${escapeHtml(metadata.description)}" />
    <meta property="og:type" content="${escapeHtml(metadata.type)}" />
    <meta property="og:url" content="${escapeHtml(url)}" />
    <link rel="canonical" href="${escapeHtml(url)}" />`);
}

export function renderHomePage({
    template,
    repositories,
    techStacks,
    team,
    featureStates,
    positionStates,
    styles,
    script,
    logoUrl,
    siteUrl,
}) {
    let html = replaceGeneratedRegion(
        replaceGeneratedRegion(
            replaceGeneratedRegion(
                template,
                "repositories",
                repositories.map(renderRepositoryCard).join("\n"),
            ),
            "tech-stacks",
            renderTechStacks(techStacks.categories),
        ),
        "team-members",
        renderTeamMembers(team),
    );

    html = renderShowcase(html, "features", featureStates, {
        label: "기능",
        backgroundClass: "feature-index",
        dotClass: "showcase-dot",
        activeDotWidth: "var(--indicator-feature-active)",
        inactiveDotColor: "var(--color-indicator-inverse)",
    });
    html = renderShowcase(html, "positions", positionStates, {
        label: "포지션",
        backgroundClass: "position-index",
        dotClass: "showcase-dot",
        activeDotWidth: "var(--indicator-active)",
        inactiveDotColor: "var(--color-indicator)",
    });

    const stateData = [
        ["/data/feature-states.json", featureStates],
        ["/data/position-states.json", positionStates],
    ]
        .map(
            ([url, states]) =>
                `<script type="application/json" data-state-url="${url}">${inlineJson(states)}</script>`,
        )
        .join("\n    ");

    return renderPageMetadata(
        bundlePage({ html, styles, stateData, script, logoUrl }),
        {
            title: "cowork",
            description: homePageDescription,
            route: "/",
            type: "website",
        },
        siteUrl,
    );
}

function todoListTitle(document) {
    if (document.kind !== "snapshot") return undefined;
    if (!document.description) return document.displayDate || document.title;
    return `${document.displayDate} — ${document.description}`;
}

export function createTodoRegistry(content, assets) {
    return {
        documents: content.documents.map((document) => {
            const {
                id,
                route,
                title,
                kind,
                priority,
                priorityLabel,
                sourceOrder,
                active,
                searchText,
                summary,
            } = document;
            const contentUrl = assets.add(
                "todo-document",
                "json",
                JSON.stringify({
                    id,
                    route,
                    metadata: document.metadata,
                    toc: document.toc,
                    bodyHtml: document.bodyHtml,
                }),
            );

            return {
                id,
                route,
                title,
                kind,
                priority,
                priorityLabel,
                sourceOrder,
                active,
                searchText,
                summary,
                listTitle: todoListTitle(document),
                contentUrl,
            };
        }),
        activeItems: content.activeItems.map(({ id }) => id),
        history: content.snapshots.map(({ id }) => id),
    };
}

export function renderTodoPage({
    template,
    activeItems,
    snapshots,
    initialDocument,
    styles,
    stateData,
    script,
    logoUrl,
    siteUrl,
}) {
    let html = replaceGeneratedRegion(
        replaceGeneratedRegion(
            template,
            "todo-items",
            initialDocument ? "" : renderTodoItems(activeItems),
        ),
        "todo-history",
        initialDocument ? "" : renderTodoHistory(snapshots),
    );

    html = replaceGeneratedRegion(
        replaceGeneratedRegion(
            html,
            "todo-document",
            initialDocument ? renderTodoDocument(initialDocument) : "",
        ),
        "todo-toc",
        initialDocument ? renderTodoToc(initialDocument.toc) : "",
    );

    if (initialDocument) {
        html = html
            .replace('href="#main-content"', 'href="#todo-document-title"')
            .replace('class="todo-page"', 'class="todo-page todo-modal-open"')
            .replace(
                'data-initial-document-id=""',
                `data-initial-document-id="${escapeHtml(initialDocument.id)}"`,
            )
            .replace(
                "  data-todo-dashboard\n  tabindex",
                "  data-todo-dashboard\n  hidden\n  tabindex",
            )
            .replace(
                "  data-todo-dialog\n  aria",
                "  data-todo-dialog\n  open\n  aria",
            );

        if (initialDocument.toc.length > 0) {
            html = html.replace(
                "data-todo-toc-rail hidden",
                "data-todo-toc-rail",
            );
        }
    }

    return renderPageMetadata(
        bundlePage({ html, styles, stateData, script, logoUrl }),
        todoPageMetadata(initialDocument),
        siteUrl,
    );
}
