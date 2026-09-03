import { showcaseNumber } from "../core/showcase-state.js";
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
        onStateChange(state, index) {
            if (background) {
                background.textContent = showcaseNumber(index);
                background.style.color = state.color;
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
