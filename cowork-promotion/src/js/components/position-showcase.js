import { elementFromMarkup } from "../core/dom.js";
import { readMotionDuration } from "../../design-system/tokens.js";
import { createSceneTransition } from "../core/transition.js";
import { createStickyShowcase } from "./sticky-showcase.js";

export function createPositionShowcase(root) {
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const background = root.querySelector("[data-showcase-background]");
    const sceneTransition = createSceneTransition(
        root.querySelector("[data-showcase-scene]"),
        {
            enterDuration: readMotionDuration("--duration-scene", root),
            leaveDuration: readMotionDuration("--duration-panel-leave", root),
            name: "pos-panel",
            reducedMotion,
        },
    );
    const showcase = createStickyShowcase({
        activeDotWidth: "var(--indicator-active)",
        inactiveDotColor: "var(--color-indicator)",
        onStateChange(state) {
            const nextBackground = elementFromMarkup(state.backgroundOuterHTML);
            if (background) {
                background.textContent = nextBackground?.textContent ?? "";
                background.style.color = nextBackground?.style.color ?? "";
            }
            sceneTransition.render(state.sceneInnerHTML);
        },
        reducedMotion,
        root,
        statesUrl: "/data/position-states.json",
    });

    return {
        mount: showcase.mount,
        unmount() {
            showcase.unmount();
            sceneTransition.destroy();
        },
    };
}
