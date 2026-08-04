import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import {
    bundleJavaScript,
    inlineJson,
    replaceBundleMarker,
} from "./lib/bundle.mjs";
import { parseContent } from "./lib/content.mjs";
import {
    generatePositionStates,
    renderTeamMembers,
    renderTechStacks,
} from "./lib/render.mjs";
import { composeTemplate, replaceGeneratedRegion } from "./lib/template.mjs";

const projectDirectory = new URL("../", import.meta.url);
const sourceDirectory = new URL("../src/", import.meta.url);
const htmlDirectory = new URL("html/", sourceDirectory);
const outputDirectory = new URL("../public/", import.meta.url);
const stylesheetPaths = [
    "css/showcase.css",
    "css/base.css",
    "css/utilities.css",
    "css/components.css",
    "css/responsive.css",
];

function projectUrl(path) {
    return new URL(path, projectDirectory);
}

function sourceUrl(path) {
    return new URL(path, sourceDirectory);
}

function outputUrl(path) {
    return new URL(path, outputDirectory);
}

export async function build() {
    const [
        sourceHtml,
        techStackSource,
        teamSource,
        featureStateSource,
        logoSource,
        scriptBundle,
        stylesheetSources,
    ] = await Promise.all([
        composeTemplate(new URL("index.html", htmlDirectory), htmlDirectory),
        readFile(projectUrl("data/tech-stacks.yaml"), "utf8"),
        readFile(projectUrl("data/team-members.xml"), "utf8"),
        readFile(projectUrl("data/feature-states.json"), "utf8"),
        readFile(projectUrl("logo.svg"), "utf8"),
        bundleJavaScript(sourceUrl("js/main.js"), sourceUrl("js/")),
        Promise.all(stylesheetPaths.map((path) => readFile(sourceUrl(path), "utf8"))),
    ]);
    const { team, techStacks } = parseContent({
        teamXml: teamSource,
        techStackYaml: techStackSource,
    });
    const positionStates = generatePositionStates(techStacks.positions, team);
    const featureStates = JSON.parse(featureStateSource);
    const generatedHtml = replaceGeneratedRegion(
        replaceGeneratedRegion(
            replaceGeneratedRegion(
                sourceHtml,
                "tech-stacks",
                renderTechStacks(techStacks.categories),
            ),
            "team-members",
            renderTeamMembers(team),
        ),
        "position-initial",
        positionStates[0].sceneInnerHTML,
    );
    const inlineStyles = stylesheetSources
        .map(
            (source, index) =>
                `/* ${stylesheetPaths[index]} */\n${source.replace(/<\/style/gi, "<\\/style")}`,
        )
        .join("\n");
    const stateData = [
        ["/data/feature-states.json", featureStates],
        ["/data/position-states.json", positionStates],
    ]
        .map(
            ([url, states]) =>
                `<script type="application/json" data-state-url="${url}">${inlineJson(states)}</script>`,
        )
        .join("\n    ");
    const logoDataUrl = `data:image/svg+xml;base64,${Buffer.from(logoSource).toString("base64")}`;
    const bundledHtml = replaceBundleMarker(
        replaceBundleMarker(
            replaceBundleMarker(generatedHtml, "styles", `<style>${inlineStyles}</style>`),
            "state-data",
            stateData,
        ),
        "script",
        `<script type="module">\n${scriptBundle}</script>`,
    ).replace('href="/logo.svg"', `href="${logoDataUrl}"`);

    await rm(outputDirectory, { recursive: true, force: true });
    await mkdir(outputDirectory, { recursive: true });
    await writeFile(outputUrl("index.html"), bundledHtml);

    const summary = {
        teamMembers: team.length,
        techGroups: techStacks.categories.length,
    };
    console.log(
        `Single-file promotion site built in public/ (${summary.techGroups} tech groups, ${summary.teamMembers} team members).`,
    );
    return summary;
}

const isDirectRun =
    process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) await build();
