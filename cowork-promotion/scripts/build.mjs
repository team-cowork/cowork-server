import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
    bundleJavaScript,
    inlineJson,
    replaceBundleMarker,
} from "./lib/bundle.mjs";
import { parseContent, parseRepositories } from "./lib/content.mjs";
import { renderRepositoryCard } from "./lib/ui.mjs";
import {
    generatePositionStates,
    renderTeamMembers,
    renderTechStacks,
} from "./lib/render.mjs";
import { composeTemplate, replaceGeneratedRegion } from "./lib/template.mjs";
import { loadTodoContent } from "./lib/todo-content.mjs";
import {
    renderTodoDocument,
    renderTodoHistory,
    renderTodoItems,
    renderTodoToc,
} from "./lib/todo-render.mjs";
import { escapeHtml } from "./lib/validation.mjs";

const projectDirectory = new URL("../", import.meta.url);
const sourceDirectory = new URL("../src/", import.meta.url);
const htmlDirectory = new URL("html/", sourceDirectory);
const defaultOutputDirectory = new URL("../public/", import.meta.url);
const defaultTodoDirectory = fileURLToPath(new URL("../../docs/todo/", import.meta.url));
const homeStylesheetPaths = [
    "css/tokens.css",
    "css/showcase.css",
    "css/base.css",
    "css/utilities.css",
    "css/components.css",
    "css/responsive.css",
    "css/ui.css",
];
const todoStylesheetPaths = [
    "css/tokens.css",
    "css/base.css",
    "css/utilities.css",
    "css/components.css",
    "css/responsive.css",
    "css/ui.css",
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

function inlineStyles(paths, sources) {
    return sources
        .map(
            (source, index) =>
                `/* ${paths[index]} */\n${source.replace(/<\/style/gi, "<\\/style")}`,
        )
        .join("\n");
}

function inlineLogo(html, logoSource) {
    const logoDataUrl = `data:image/svg+xml;base64,${Buffer.from(logoSource).toString("base64")}`;
    return html.replace('href="/logo.svg"', `href="${logoDataUrl}"`);
}

function bundlePage({ html, styles, stateData, script, logoSource }) {
    return inlineLogo(
        replaceBundleMarker(
            replaceBundleMarker(
                replaceBundleMarker(html, "styles", `<style>${styles}</style>`),
                "state-data",
                stateData,
            ),
            "script",
            `<script type="module">\n${script}</script>`,
        ),
        logoSource,
    );
}

function replacePageTitle(html, title, type = "website") {
    const safeTitle = escapeHtml(title);
    return html
        .replace("<title>cowork</title>", `<title>${safeTitle}</title>`)
        .replace(
            '<meta property="og:title" content="cowork" />',
            `<meta property="og:title" content="${safeTitle}" />`,
        )
        .replace(
            '<meta property="og:type" content="website" />',
            `<meta property="og:type" content="${escapeHtml(type)}" />`,
        );
}

function todoListTitle(document) {
    if (document.kind !== "snapshot") return undefined;
    if (!document.description) return document.displayDate || document.title;
    return `${document.displayDate} — ${document.description}`;
}

function todoRegistry(content) {
    return {
        documents: content.documents.map((document) => ({
            ...document,
            listTitle: todoListTitle(document),
        })),
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
    logoSource,
}) {
    let html = replaceGeneratedRegion(
        replaceGeneratedRegion(template, "todo-items", renderTodoItems(activeItems)),
        "todo-history",
        renderTodoHistory(snapshots),
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

    const title = initialDocument
        ? `${initialDocument.title} · cowork`
        : "개발 진행 현황 · cowork";
    return replacePageTitle(
        bundlePage({ html, styles, stateData, script, logoSource }),
        title,
        initialDocument ? "article" : "website",
    );
}

async function writeOutput(outputDirectory, relativePath, content) {
    const fileUrl = new URL(relativePath, outputDirectory);
    await mkdir(new URL("./", fileUrl), { recursive: true });
    await writeFile(fileUrl, content);
}

export async function build(options = {}) {
    const outputDirectory = directoryUrl(
        options.outputDirectory,
        defaultOutputDirectory,
    );
    const todoDirectory = options.todoDirectory
        ? resolve(String(options.todoDirectory))
        : defaultTodoDirectory;
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
        bundleJavaScript(sourceUrl("js/main.js"), sourceUrl("js/")),
        bundleJavaScript(sourceUrl("js/todo-main.js"), sourceUrl("js/")),
        Promise.all(
            homeStylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8")),
        ),
        Promise.all(
            todoStylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8")),
        ),
        loadTodoContent({ todoDirectory }),
    ]);

    const { team, techStacks } = parseContent({
        teamXml: teamSource,
        techStackYaml: techStackSource,
    });
    const positionStates = generatePositionStates(techStacks.positions, team);
    const featureStates = JSON.parse(featureStateSource);
    const generatedHomeHtml = replaceGeneratedRegion(
        replaceGeneratedRegion(
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
        ),
        "position-initial",
        positionStates[0].sceneInnerHTML,
    );
    const homeStateData = [
        ["/data/feature-states.json", featureStates],
        ["/data/position-states.json", positionStates],
    ]
        .map(
            ([url, states]) =>
                `<script type="application/json" data-state-url="${url}">${inlineJson(states)}</script>`,
        )
        .join("\n    ");
    const homeHtml = bundlePage({
        html: generatedHomeHtml,
        styles: inlineStyles(homeStylesheetPaths, homeStylesheetSources),
        stateData: homeStateData,
        script: homeScriptBundle,
        logoSource,
    });
    const todoStyles = inlineStyles(todoStylesheetPaths, todoStylesheetSources);
    const todoStateData = `<script type="application/json" data-todo-registry>${inlineJson(todoRegistry(todoContent))}</script>`;
    const todoPageOptions = {
        template: todoTemplate,
        activeItems: todoContent.activeItems,
        snapshots: todoContent.snapshots,
        styles: todoStyles,
        stateData: todoStateData,
        script: todoScriptBundle,
        logoSource,
    };

    await rm(outputDirectory, { recursive: true, force: true });
    await Promise.all([
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
