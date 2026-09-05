import { readFile } from "node:fs/promises";
import { accentStyle, renderBadge } from "../../src/design-system/index.mjs";
import { escapeHtml } from "./validation.mjs";

export async function generateFeatureStates(features) {
    return Promise.all(features.map(async (feature) => {
        const mockup = await readFile(new URL(`../../src/html/mockups/${feature.mockup}.html`, import.meta.url), "utf8");
        const accent = accentStyle(feature.color);
        return {
            color: feature.color,
            sceneInnerHTML: `<div class="feature-copy" style="${accent}">
                <span class="feature-label">${escapeHtml(feature.label)}</span>
                <h2 class="feature-title">${escapeHtml(feature.title)}</h2>
                <p class="feature-description">${escapeHtml(feature.description)}</p>
                <div class="flex flex-wrap gap-2">${feature.tags.map((tag) => renderBadge(tag, { variant: "accent" })).join("")}</div>
            </div>
            <div class="feature-mockup" aria-hidden="true" style="${accent}">${mockup}</div>`,
        };
    }));
}
