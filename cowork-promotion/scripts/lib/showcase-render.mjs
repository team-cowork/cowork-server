import { showcaseCounter, showcaseNumber } from "../../src/js/core/showcase-state.js";
import { replaceGeneratedRegion } from "./template.mjs";
import { escapeHtml } from "./validation.mjs";

export function renderShowcase(html, name, states, {
    label,
    backgroundClass,
    dotClass,
    activeDotWidth,
    inactiveDotColor,
}) {
    const initial = states[0];
    const dots = states.map((_, index) => {
        const active = index === 0;
        const width = active ? activeDotWidth : "var(--indicator-size)";
        const color = active ? initial.color : inactiveDotColor;
        return `<button type="button" data-showcase-dot aria-label="${escapeHtml(label)} ${index + 1} / ${states.length}" aria-current="${active ? "step" : "false"}" class="${escapeHtml(dotClass)}" style="--dot-width: ${escapeHtml(width)}; --dot-color: ${escapeHtml(color)}"></button>`;
    }).join("\n");
    const regions = {
        background: `<span data-showcase-background class="${escapeHtml(backgroundClass)}" style="color: ${escapeHtml(initial.color)}">${showcaseNumber(0)}</span>`,
        counter: showcaseCounter(0, states.length),
        initial: initial.sceneInnerHTML,
        dots,
    };
    let result = html
        .replaceAll(`{{${name}:count}}`, String(states.length))
        .replaceAll(`{{${name}:color}}`, escapeHtml(initial.color));
    for (const [region, content] of Object.entries(regions)) {
        result = replaceGeneratedRegion(result, `${name}-${region}`, content);
    }
    return result;
}
