import { escapeHtml } from "./validation.mjs";
import { accentStyle, renderBadge, renderMemberCard } from "../../src/design-system/index.mjs";

export function renderTechStacks(categories) {
    return categories.map((category) => `
      <div class="flex flex-col sm:flex-row items-start gap-6">
        <span class="text-xs font-semibold text-gray-400 uppercase tracking-wider w-24 shrink-0 pt-2">${escapeHtml(category.name)}</span>
        <div class="flex flex-wrap gap-2">${category.items.map((item) => renderBadge(item.name, { variant: "technology", color: item.color })).join("\n")}</div>
      </div>`).join("\n");
}

function renderMarqueeRow(members, direction) {
    const groups = [false, true].map((duplicate) => `<ul class="marquee-group"${duplicate ? ' aria-hidden="true"' : ''}>${members.map((member) => `<li>${renderMemberCard(member, { profile: true, duplicate })}</li>`).join("\n")}</ul>`).join("\n");
    return `<div class="marquee-window"><div class="marquee-row marquee-${direction}">${groups}</div></div>`;
}

export function renderTeamMembers(members) {
    const midpoint = Math.ceil(members.length / 2);
    return [renderMarqueeRow(members.slice(0, midpoint), "left"), renderMarqueeRow(members.slice(midpoint), "right")].join("\n");
}

function renderPositionScene(position, members) {
    const memberMarkup = members
        .filter((member) => member.roles.includes(position.name))
        .map((member) => renderMemberCard(member))
        .join("");
    if (memberMarkup === "") {
        throw new Error(`No team members configured for position: ${position.name}`);
    }
    const technologyMarkup = position.technologies
        .map((technology) => renderBadge(technology, { variant: "accent" }))
        .join("");
    const accent = accentStyle(position.color);
    return `<div style="${accent}"><div class="ui-dot position-scene__marker"></div><h2 class="position-scene__title">${escapeHtml(position.name)}</h2><p class="position-scene__description">${escapeHtml(position.description)}</p><div class="flex flex-wrap gap-2">${technologyMarkup}</div></div><div class="flex flex-wrap gap-3 content-start pb-1" style="${accent}">${memberMarkup}</div>`;
}

export function generatePositionStates(positions, members) {
    return positions.map((position) => ({
        color: position.color,
        sceneInnerHTML: renderPositionScene(position, members),
    }));
}
