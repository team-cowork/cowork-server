import { escapeHtml } from "./validation.mjs";

const marqueeCopiesPerRow = 4;

function colorChannels(hexColor) {
    return [1, 3, 5].map((offset) =>
        Number.parseInt(hexColor.slice(offset, offset + 2), 16),
    );
}

function renderTechnologyChip(item) {
    return `                  <span
                    class="inline-flex items-center gap-2 px-3.5 py-1.5 bg-gray-50 border border-gray-100 rounded-full text-sm text-gray-700 font-medium"
                  >
                    <span
                      style="background-color: ${escapeHtml(item.color)}"
                      class="w-2 h-2 rounded-full shrink-0"
                    ></span>
                    ${escapeHtml(item.name)}
                  </span>`;
}

export function renderTechStacks(categories) {
    return categories
        .map(
            (category) => `              <div class="flex items-start gap-6">
                <span
                  class="text-xs font-semibold text-gray-400 uppercase tracking-wider w-24 shrink-0 pt-2"
                >
                  ${escapeHtml(category.name)}
                </span>
                <div class="flex flex-wrap gap-2">
${category.items.map(renderTechnologyChip).join("\n")}
                </div>
              </div>`,
        )
        .join("\n");
}

function renderTeamMemberCard(member) {
    const github = escapeHtml(member.github);
    return `                <a
                  href="https://github.com/${github}"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="flex items-center gap-3 px-4 py-3 bg-white rounded-2xl border border-gray-100 shadow-sm shrink-0 select-none"
                  style="min-width: 200px"
                >
                  <img
                    src="https://github.com/${github}.png?size=96"
                    alt="${escapeHtml(member.name)}"
                    class="w-11 h-11 rounded-full object-cover bg-gray-100 ring-2 ring-gray-100"
                    width="44"
                    height="44"
                    loading="lazy"
                    decoding="async"
                  />
                  <div class="min-w-0">
                    <p
                      class="font-semibold text-gray-900 text-sm leading-tight truncate"
                    >
                      ${escapeHtml(member.name)}
                    </p>
                    <div class="flex items-center gap-1.5 mt-1">
                      <span
                        class="w-1.5 h-1.5 rounded-full shrink-0"
                        style="background-color: ${escapeHtml(member.accent)}"
                      ></span>
                      <span class="text-xs text-gray-500 truncate">
                        ${escapeHtml(member.roles.join(" | "))}
                      </span>
                      <span
                        class="text-xs px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-400 font-medium shrink-0"
                      >
                        ${escapeHtml(member.generation)}기
                      </span>
                    </div>
                  </div>
                </a>`;
}

function renderMarqueeRow(members, direction) {
    const cards = Array.from({ length: marqueeCopiesPerRow }, () => members)
        .flat()
        .map(renderTeamMemberCard)
        .join("\n");

    return `            <div class="overflow-hidden">
              <div class="flex gap-3 marquee-${direction} min-w-max px-3">
${cards}
              </div>
            </div>`;
}

export function renderTeamMembers(members) {
    return [renderMarqueeRow(members, "left"), renderMarqueeRow(members, "right")].join(
        "\n",
    );
}

function renderPositionTechnology(name, color) {
    const [red, green, blue] = colorChannels(color);
    const style = `background-color: rgba(${red}, ${green}, ${blue}, 0.082); color: rgb(${red}, ${green}, ${blue});`;

    return `<span class="text-xs font-semibold px-2.5 py-1 rounded-full" style="${style}">${escapeHtml(name)}</span>`;
}

function renderPositionMember(member, color) {
    const github = escapeHtml(member.github);
    const [red, green, blue] = colorChannels(color);
    const style = `background-color: rgba(${red}, ${green}, ${blue}, 0.07); border-color: rgba(${red}, ${green}, ${blue}, 0.19);`;

    return `<a href="https://github.com/${github}" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2.5 px-4 py-2.5 rounded-xl border" style="${style}"><img src="https://github.com/${github}.png?size=64" alt="${escapeHtml(member.name)}" class="w-8 h-8 rounded-full object-cover" width="32" height="32" loading="lazy" decoding="async"><div><p class="text-sm font-semibold text-gray-900 leading-tight">${escapeHtml(member.name)}</p> <p class="text-xs text-gray-400">${escapeHtml(member.generation)}기</p></div></a>`;
}

function renderPositionScene(position, members) {
    const [red, green, blue] = colorChannels(position.color);
    const color = `rgb(${red}, ${green}, ${blue})`;
    const memberMarkup = members
        .filter((member) => member.roles.includes(position.name))
        .map((member) => renderPositionMember(member, position.color))
        .join("");

    if (memberMarkup === "") {
        throw new Error(`No team members configured for position: ${position.name}`);
    }

    const technologyMarkup = position.technologies
        .map((technology) => renderPositionTechnology(technology, position.color))
        .join("");

    return `<!-- Left: text + techs --><div><div class="w-2 h-2 rounded-full mb-5" style="background-color: ${color};"></div><h2 class="text-5xl lg:text-6xl font-black tracking-tight leading-tight mb-5" style="color: ${color};">${escapeHtml(position.name)}</h2><p class="text-gray-500 text-base leading-relaxed mb-5">${escapeHtml(position.description)}</p><div class="flex flex-wrap gap-2">${technologyMarkup}</div></div><!-- Right: members --><div class="flex flex-wrap gap-3 content-start pb-1">${memberMarkup}</div>`;
}

export function generatePositionStates(positions, members) {
    return positions.map((position, index) => {
        const number = String(index + 1).padStart(2, "0");
        const [red, green, blue] = colorChannels(position.color);
        const color = `rgb(${red}, ${green}, ${blue})`;

        return {
            backgroundOuterHTML: `<span class="font-black leading-none transition-all duration-700" style="font-size: clamp(160px, 28vw, 380px); opacity: 0.04; line-height: 1; padding-left: 4vw; color: ${color};">${number}</span>`,
            counterText: `${number} / ${String(positions.length).padStart(2, "0")}`,
            dots: positions.map((_, dotIndex) =>
                dotIndex === index
                    ? `width: 32px; background-color: ${color};`
                    : "width:8px;background-color:#E5E7EB;",
            ),
            heading: position.name,
            sceneInnerHTML: renderPositionScene(position, members),
        };
    });
}
