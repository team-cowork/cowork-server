import { readFile } from "node:fs/promises";

import {
    componentStylesheets,
    foundationStylesheets,
} from "../../src/design-system/index.mjs";
import { bundleJavaScript } from "./bundle.mjs";
import { composeTemplate } from "./template.mjs";
import { loadTodoContent } from "./todo-content.mjs";

const projectDirectory = new URL("../../", import.meta.url);
const sourceDirectory = new URL("../../src/", import.meta.url);
const htmlDirectory = new URL("html/", sourceDirectory);

export const stylesheetPaths = Object.freeze({
    shared: [
        ...foundationStylesheets,
        "css/base.css",
        "css/utilities.css",
        "css/responsive.css",
        ...componentStylesheets,
        "css/site.css",
    ],
    home: ["css/home.css", "css/showcase.css"],
    todo: ["css/todo.css"],
});

function projectUrl(path) {
    return new URL(path, projectDirectory);
}

function sourceUrl(path) {
    return new URL(path, sourceDirectory);
}

function loadStylesheets(paths) {
    return Promise.all(paths.map((path) => readFile(sourceUrl(path), "utf8")));
}

export async function loadBuildInput({
    todoDirectory,
    repositoryDirectory,
    repositorySourceUrl,
}) {
    const [
        homeTemplate,
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
        loadStylesheets(stylesheetPaths.shared),
        loadStylesheets(stylesheetPaths.home),
        loadStylesheets(stylesheetPaths.todo),
        loadTodoContent({
            todoDirectory,
            repositoryDirectory,
            repositorySourceUrl,
        }),
    ]);

    return {
        homeTemplate,
        todoTemplate,
        techStackSource,
        repositorySource,
        teamSource,
        featureStateSource,
        logoSource,
        homeScriptBundle,
        todoScriptBundle,
        stylesheetSources: {
            shared: sharedStylesheetSources,
            home: homeStylesheetSources,
            todo: todoStylesheetSources,
        },
        todoContent,
    };
}
