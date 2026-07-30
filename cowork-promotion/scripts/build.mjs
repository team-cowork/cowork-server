import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
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
const outputDataDirectory = new URL("data/", outputDirectory);

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
    const [sourceHtml, techStackSource, teamSource] = await Promise.all([
        composeTemplate(new URL("index.html", htmlDirectory), htmlDirectory),
        readFile(projectUrl("data/tech-stacks.yaml"), "utf8"),
        readFile(projectUrl("data/team-members.xml"), "utf8"),
    ]);
    const { team, techStacks } = parseContent({
        teamXml: teamSource,
        techStackYaml: techStackSource,
    });
    const positionStates = generatePositionStates(techStacks.positions, team);
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

    await rm(outputDirectory, { recursive: true, force: true });
    await mkdir(outputDataDirectory, { recursive: true });

    await Promise.all([
        writeFile(outputUrl("index.html"), generatedHtml),
        cp(sourceUrl("css/"), outputUrl("css/"), { recursive: true }),
        cp(sourceUrl("js/"), outputUrl("js/"), { recursive: true }),
        cp(projectUrl("logo.svg"), outputUrl("logo.svg")),
        cp(
            projectUrl("data/feature-states.json"),
            new URL("feature-states.json", outputDataDirectory),
        ),
        writeFile(
            new URL("position-states.json", outputDataDirectory),
            `${JSON.stringify(positionStates, null, 2)}\n`,
        ),
    ]);

    const summary = {
        teamMembers: team.length,
        techGroups: techStacks.categories.length,
    };
    console.log(
        `Static promotion site built in public/ (${summary.techGroups} tech groups, ${summary.teamMembers} team members).`,
    );
    return summary;
}

const isDirectRun =
    process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isDirectRun) await build();
