import { execFileSync } from "node:child_process";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
    bundleJavaScript,
    inlineJson,
    replaceBundleMarker,
} from "./lib/bundle.mjs";
import { parseContent, parseFeatureStates, parseRepositories } from "./lib/content.mjs";
import {
    componentStylesheets,
    foundationStylesheets,
    renderRepositoryCard,
} from "../src/design-system/index.mjs";
import {
    generatePositionStates,
    renderTeamMembers,
    renderTechStacks,
} from "./lib/render.mjs";
import { composeTemplate, replaceGeneratedRegion } from "./lib/template.mjs";
import { renderShowcase } from "./lib/showcase-render.mjs";
import { loadTodoContent } from "./lib/todo-content.mjs";
import {
    renderTodoDocument,
    renderTodoHistory,
    renderTodoItems,
    renderTodoToc,
} from "./lib/todo-render.mjs";
import { escapeHtml } from "./lib/validation.mjs";
import { createAssetCollection } from "./lib/assets.mjs";
import { generateFeatureStates } from "./lib/feature-render.mjs";
import { homePageDescription, todoPageMetadata } from "../src/js/core/page-metadata.js";

const projectDirectory = new URL("../", import.meta.url);
const sourceDirectory = new URL("../src/", import.meta.url);
const htmlDirectory = new URL("html/", sourceDirectory);
const defaultOutputDirectory = new URL("../public/", import.meta.url);
const defaultTodoDirectory = fileURLToPath(new URL("../../docs/todo/", import.meta.url));
const defaultRepositoryDirectory = fileURLToPath(new URL("../../", import.meta.url));
const sharedStylesheetPaths = [
    ...foundationStylesheets,
    "css/base.css",
    "css/utilities.css",
    "css/responsive.css",
    ...componentStylesheets,
    "css/site.css",
];
const homeStylesheetPaths = [
    "css/home.css",
    "css/showcase.css",
];
const todoStylesheetPaths = [
    "css/todo.css",
];

function projectUrl(path) {
    return new URL(path, projectDirectory);
}

function sourceUrl(path) {
    return new URL(path, sourceDirectory);
}

function directoryUrl(value, fallback) {
    if (!value) return fallback;
    if (value instanceof URL) return value;

    const directoryPath = `${resolve(String(value))}/`;
    return pathToFileURL(directoryPath);
}

function repositorySourceUrl(repositoryDirectory) {
    let revision = process.env.VERCEL_GIT_COMMIT_SHA || process.env.GITHUB_SHA;

    if (!revision) {
        try {
            revision = execFileSync("git", ["rev-parse", "HEAD"], {
                cwd: repositoryDirectory,
                encoding: "utf8",
                stdio: ["ignore", "pipe", "ignore"],
            }).trim();
        } catch (error) {
            throw new Error(
                "Cannot determine the repository revision; provide repositorySourceUrl when building without Git metadata.",
                { cause: error },
            );
        }
    }

    return `https://github.com/team-cowork/cowork-server/blob/${encodeURIComponent(revision)}/`;
}

function concatenateStyles(paths, sources) {
    return sources
        .map(
            (source, index) =>
                `/* ${paths[index]} */\n${source}`,
        )
        .join("\n");
}

function bundlePage({ html, styles, stateData, script, logoUrl }) {
    return (
        replaceBundleMarker(
            replaceBundleMarker(
                replaceBundleMarker(html, "styles", styles.map((url) => `<link rel="stylesheet" href="${url}" />`).join("\n")),
                "state-data",
                stateData,
            ),
            "script",
            `<script type="module" src="${script}"></script>`,
        ).replace('href="/logo.svg"', `href="${logoUrl}"`)
    );
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

function todoListTitle(document) {
    if (document.kind !== "snapshot") return undefined;
    if (!document.description) return document.displayDate || document.title;
    return `${document.displayDate} — ${document.description}`;
}

function todoRegistry(content, assets) {
    return {
        documents: content.documents.map((document) => {
            const { id, route, title, kind, priority, priorityLabel, sourceOrder, active, searchText, summary } = document;
            const contentUrl = assets.add("todo-document", "json", JSON.stringify({
                id, route, metadata: document.metadata, toc: document.toc, bodyHtml: document.bodyHtml,
            }));
            return {
                id, route, title, kind, priority, priorityLabel, sourceOrder, active, searchText, summary,
                listTitle: todoListTitle(document), contentUrl,
            };
        }),
        activeItems: content.activeItems.map(({ id }) => id),
        history: content.snapshots.map(({ id }) => id),
    };
}

function renderTodoShell({
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
        replaceGeneratedRegion(template, "todo-items", initialDocument ? "" : renderTodoItems(activeItems)),
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
            html = html.replace("data-todo-toc-rail hidden", "data-todo-toc-rail");
        }
    }

    return renderPageMetadata(
        bundlePage({ html, styles, stateData, script, logoUrl }),
        todoPageMetadata(initialDocument),
        siteUrl,
    );
}

async function writeOutput(outputDirectory, relativePath, content) {
    const fileUrl = new URL(relativePath, outputDirectory);
    await mkdir(new URL("./", fileUrl), { recursive: true });
    await writeFile(fileUrl, content);
}

export async function build(options = {}) {
    const assets = createAssetCollection();
    const deploymentHost = process.env.VERCEL_PROJECT_PRODUCTION_URL || process.env.VERCEL_URL;
    const configuredSiteUrl = options.siteUrl || process.env.SITE_URL || (deploymentHost ? `https://${deploymentHost}` : null);
    const siteUrl = configuredSiteUrl ? new URL(configuredSiteUrl) : null;
    if (siteUrl && (!['http:', 'https:'].includes(siteUrl.protocol) || siteUrl.username || siteUrl.password || siteUrl.pathname !== '/' || siteUrl.search || siteUrl.hash)) {
        throw new Error("SITE_URL must be an HTTP(S) origin without a path, credentials, query or fragment.");
    }
    const outputDirectory = directoryUrl(
        options.outputDirectory,
        defaultOutputDirectory,
    );
    const todoDirectory = options.todoDirectory
        ? resolve(String(options.todoDirectory))
        : defaultTodoDirectory;
    const repositoryDirectory = options.repositoryDirectory
        ? resolve(String(options.repositoryDirectory))
        : defaultRepositoryDirectory;
    const [
        sourceHtml,
        todoTemplate,
        techStackSource,
        repositorySource,
        teamSource,
        featureStateSource,
        logoSource,
        homeScriptBundle,
        todoScriptBundle,
        sharedStylesheetSources,
        homeStylesheetSources,
        todoStylesheetSources,
        todoContent,
    ] = await Promise.all([
        composeTemplate(new URL("index.html", htmlDirectory), htmlDirectory),
        composeTemplate(new URL("todo.html", htmlDirectory), htmlDirectory),
        readFile(projectUrl("data/tech-stacks.yaml"), "utf8"),
        readFile(projectUrl("data/repositories.json"), "utf8"),
        readFile(projectUrl("data/team-members.xml"), "utf8"),
        readFile(projectUrl("data/feature-states.json"), "utf8"),
        readFile(projectUrl("logo.svg"), "utf8"),
        bundleJavaScript(sourceUrl("js/main.js"), sourceDirectory),
        bundleJavaScript(sourceUrl("js/todo-main.js"), sourceDirectory),
        Promise.all(sharedStylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8"))),
        Promise.all(
            homeStylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8")),
        ),
        Promise.all(
            todoStylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8")),
        ),
        loadTodoContent({
            todoDirectory,
            repositoryDirectory,
            repositorySourceUrl: options.repositorySourceUrl
                ?? repositorySourceUrl(repositoryDirectory),
        }),
    ]);

    const { team, techStacks } = parseContent({
        teamXml: teamSource,
        techStackYaml: techStackSource,
    });
    const positionStates = generatePositionStates(techStacks.positions, team);
    const featureStates = await generateFeatureStates(parseFeatureStates(featureStateSource));
    let generatedHomeHtml = replaceGeneratedRegion(
        replaceGeneratedRegion(
            replaceGeneratedRegion(
                sourceHtml,
                "repositories",
                parseRepositories(repositorySource).map(renderRepositoryCard).join("\n"),
            ),
            "tech-stacks",
            renderTechStacks(techStacks.categories),
        ),
        "team-members",
        renderTeamMembers(team),
    );
    generatedHomeHtml = renderShowcase(generatedHomeHtml, "features", featureStates, {
        label: "기능",
        backgroundClass: "feature-index",
        dotClass: "showcase-dot",
        activeDotWidth: "var(--indicator-feature-active)",
        inactiveDotColor: "var(--color-indicator-inverse)",
    });
    generatedHomeHtml = renderShowcase(generatedHomeHtml, "positions", positionStates, {
        label: "포지션",
        backgroundClass: "position-index",
        dotClass: "showcase-dot",
        activeDotWidth: "var(--indicator-active)",
        inactiveDotColor: "var(--color-indicator)",
    });
    const homeStateData = [
        ["/data/feature-states.json", featureStates],
        ["/data/position-states.json", positionStates],
    ]
        .map(
            ([url, states]) =>
                `<script type="application/json" data-state-url="${url}">${inlineJson(states)}</script>`,
        )
        .join("\n    ");
    const sharedStyleUrl = assets.add("shared", "css", concatenateStyles(sharedStylesheetPaths, sharedStylesheetSources));
    const logoUrl = assets.add("logo", "svg", logoSource);
    const homeHtml = renderPageMetadata(bundlePage({
        html: generatedHomeHtml,
        styles: [sharedStyleUrl, assets.add("home", "css", concatenateStyles(homeStylesheetPaths, homeStylesheetSources))],
        stateData: homeStateData,
        script: assets.add("home", "js", homeScriptBundle),
        logoUrl,
    }), { title: "cowork", description: homePageDescription, route: "/", type: "website" }, siteUrl);
    const registryUrl = assets.add("todo-registry", "json", JSON.stringify(todoRegistry(todoContent, assets)));
    const todoStateData = `<link rel="preload" href="${registryUrl}" as="fetch" crossorigin="anonymous" data-todo-registry />`;
    const todoPageOptions = {
        template: todoTemplate,
        activeItems: todoContent.activeItems,
        snapshots: todoContent.snapshots,
        styles: [sharedStyleUrl, assets.add("todo", "css", concatenateStyles(todoStylesheetPaths, todoStylesheetSources))],
        stateData: todoStateData,
        script: assets.add("todo", "js", todoScriptBundle),
        logoUrl,
        siteUrl,
    };

    await rm(outputDirectory, { recursive: true, force: true });
    await Promise.all([
        ...Array.from(assets.files, ([path, content]) => writeOutput(outputDirectory, path, content)),
        writeOutput(outputDirectory, "index.html", homeHtml),
        writeOutput(
            outputDirectory,
            "todo/index.html",
            renderTodoShell({ ...todoPageOptions, initialDocument: null }),
        ),
        ...todoContent.documents.map((document) =>
            writeOutput(
                outputDirectory,
                `${document.route.replace(/^\//, "")}/index.html`,
                renderTodoShell({ ...todoPageOptions, initialDocument: document }),
            ),
        ),
    ]);

    const summary = {
        teamMembers: team.length,
        techGroups: techStacks.categories.length,
        todoDocuments: todoContent.documents.length,
        todoItems: todoContent.activeItems.length,
        todoSnapshots: todoContent.snapshots.length,
    };
    console.log(
        `Promotion site built in public/ (${summary.techGroups} tech groups, ${summary.teamMembers} team members, ${summary.todoItems} active TODOs, ${summary.todoSnapshots} snapshots).`,
    );
    return summary;
}

const isDirectRun =
    process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) await build();
