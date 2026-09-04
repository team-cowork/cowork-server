import { escapeHtml, expectColor } from "../../scripts/lib/validation.mjs";

export function accentStyle(color) {
    const hex = expectColor(color, "component.accent");
    const channels = [1, 3, 5].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16));
    return `--ui-accent-rgb: ${channels.join(" ")};`;
}

export function renderBadge(label, { variant = "", color } = {}) {
    const className = `ui-badge${variant ? ` ui-badge--${variant}` : ""}`;
    const dot = color
        ? `<span class="ui-dot" style="${accentStyle(color)}" aria-hidden="true"></span>`
        : "";
    return `<span class="${className}">${dot}${escapeHtml(label)}</span>`;
}

export function renderAvatar(member, { profile = false } = {}) {
    const size = profile ? 44 : 32;
    const github = escapeHtml(member.github);
    return `<img class="ui-avatar${profile ? " ui-avatar--profile" : ""}" src="https://github.com/${github}.png?size=${profile ? 96 : 64}" alt="${escapeHtml(member.name)}" width="${size}" height="${size}" loading="lazy" decoding="async" />`;
}

export function renderMemberCard(member, { profile = false } = {}) {
    const details = profile
        ? `<div class="flex items-center gap-1.5 mt-1"><span class="ui-dot ui-dot--small" style="${accentStyle(member.accent)}" aria-hidden="true"></span><span class="text-xs text-gray-500 truncate">${escapeHtml(member.roles.join(" | "))}</span>${renderBadge(`${member.generation}기`, { variant: "generation" })}</div>`
        : `<p class="ui-member-card__generation">${escapeHtml(member.generation)}기</p>`;
    return `<a href="https://github.com/${escapeHtml(member.github)}" target="_blank" rel="noopener noreferrer" class="ui-member-card${profile ? " ui-member-card--profile" : ""}">${renderAvatar(member, { profile })}<div class="min-w-0"><p class="ui-member-card__name${profile ? " truncate" : ""}">${escapeHtml(member.name)}</p>${details}</div></a>`;
}

function renderRepositoryIcon() {
    return `<svg class="ui-icon" aria-hidden="true" fill="currentColor" viewBox="0 0 16 16"><path d="M2 2.5A2.5 2.5 0 0 1 4.5 0h8.75a.75.75 0 0 1 .75.75v12.5a.75.75 0 0 1-.75.75h-2.5a.75.75 0 0 1 0-1.5h1.75v-2h-8a1 1 0 0 0-.714 1.7.75.75 0 1 1-1.072 1.05A2.495 2.495 0 0 1 2 11.5Zm10.5-1h-8a1 1 0 0 0-1 1v6.708A2.486 2.486 0 0 1 4.5 9h8Z" /></svg>`;
}

function renderExternalLinkIcon() {
    return `<svg class="ui-icon" style="--icon-size: 11px" aria-hidden="true" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" viewBox="0 0 24 24"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" /><polyline points="15 3 21 3 21 9" /><line x1="10" x2="21" y1="14" y2="3" /></svg>`;
}

export function renderRepositoryCard(repository) {
    const name = escapeHtml(repository.name);
    const url = `https://github.com/team-cowork/${name}`;
    return `<a class="ui-card ui-card--interactive repo-card${repository.centered ? " repo-card--centered" : ""}" href="${url}" rel="noopener noreferrer" target="_blank">
      <div class="repo-card__header"><div class="repo-card__icon">${renderRepositoryIcon()}</div>${renderBadge(repository.label)}</div>
      <h3 class="repo-card__title">${name}</h3>
      <p class="repo-card__description">${escapeHtml(repository.description)}</p>
      <div class="repo-language-stats-frame mt-4 rounded-xl overflow-hidden border border-gray-50">
        <img class="repo-language-stats w-full block" alt="${name} language stats" src="https://github-repository-language-graph-wi.vercel.app/api?username=team-cowork&amp;repo=${name}&amp;theme=white&amp;langs_count=100" width="495" height="${repository.graphHeight}" loading="lazy" decoding="async" />
      </div>
      <div class="repo-card__footer">${renderExternalLinkIcon()}<span class="truncate">github.com/team-cowork/${name}</span></div>
    </a>`;
}
