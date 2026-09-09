import { pathToFileURL } from "node:url";

import { createAssetCollection } from "./lib/assets.mjs";
import { resolveBuildConfig } from "./lib/build-config.mjs";
import { loadBuildInput, stylesheetPaths } from "./lib/build-input.mjs";
import {
    parseContent,
    parseFeatureStates,
    parseRepositories,
} from "./lib/content.mjs";
import { generateFeatureStates } from "./lib/feature-render.mjs";
import { replaceOutputDirectory } from "./lib/output.mjs";
import {
    concatenateStyles,
    createTodoRegistry,
    renderHomePage,
    renderTodoPage,
} from "./lib/page-render.mjs";
import { generatePositionStates } from "./lib/render.mjs";

function addStylesheet(assets, name, paths, sources) {
    return assets.add(name, "css", concatenateStyles(paths, sources));
}

function createOutputFiles(assets, homeHtml, todoContent, todoPageOptions) {
    const pages = [
        ["index.html", homeHtml],
        [
            "todo/index.html",
            renderTodoPage({ ...todoPageOptions, initialDocument: null }),
        ],
        ...todoContent.documents.map((document) => [
            `${document.route.replace(/^\//, "")}/index.html`,
            renderTodoPage({
                ...todoPageOptions,
                initialDocument: document,
            }),
        ]),
    ];

    return new Map([...assets.files, ...pages]);
}

export async function build(options = {}) {
    const config = resolveBuildConfig(options);
    const input = await loadBuildInput(config);
    const assets = createAssetCollection();
    const { team, techStacks } = parseContent({
        teamXml: input.teamSource,
        techStackYaml: input.techStackSource,
    });
    const repositories = parseRepositories(input.repositorySource);
    const positionStates = generatePositionStates(techStacks.positions, team);
    const featureStates = await generateFeatureStates(
        parseFeatureStates(input.featureStateSource),
    );

    const sharedStyleUrl = addStylesheet(
        assets,
        "shared",
        stylesheetPaths.shared,
        input.stylesheetSources.shared,
    );
    const logoUrl = assets.add("logo", "svg", input.logoSource);
    const homeHtml = renderHomePage({
        template: input.homeTemplate,
        repositories,
        techStacks,
        team,
        featureStates,
        positionStates,
        styles: [
            sharedStyleUrl,
            addStylesheet(
                assets,
                "home",
                stylesheetPaths.home,
                input.stylesheetSources.home,
            ),
        ],
        script: assets.add("home", "js", input.homeScriptBundle),
        logoUrl,
        siteUrl: config.siteUrl,
    });

    const registryUrl = assets.add(
        "todo-registry",
        "json",
        JSON.stringify(createTodoRegistry(input.todoContent, assets)),
    );
    const todoPageOptions = {
        template: input.todoTemplate,
        activeItems: input.todoContent.activeItems,
        snapshots: input.todoContent.snapshots,
        styles: [
            sharedStyleUrl,
            addStylesheet(
                assets,
                "todo",
                stylesheetPaths.todo,
                input.stylesheetSources.todo,
            ),
        ],
        stateData: `<link rel="preload" href="${registryUrl}" as="fetch" crossorigin="anonymous" data-todo-registry />`,
        script: assets.add("todo", "js", input.todoScriptBundle),
        logoUrl,
        siteUrl: config.siteUrl,
    };
    const outputFiles = createOutputFiles(
        assets,
        homeHtml,
        input.todoContent,
        todoPageOptions,
    );

    await replaceOutputDirectory(config.outputDirectory, outputFiles);

    const summary = {
        teamMembers: team.length,
        techGroups: techStacks.categories.length,
        todoDocuments: input.todoContent.documents.length,
        todoItems: input.todoContent.activeItems.length,
        todoSnapshots: input.todoContent.snapshots.length,
    };
    console.log(
        `Promotion site built in public/ (${summary.techGroups} tech groups, ${summary.teamMembers} team members, ${summary.todoItems} active TODOs, ${summary.todoSnapshots} snapshots).`,
    );
    return summary;
}

const isDirectRun =
    process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) await build();
