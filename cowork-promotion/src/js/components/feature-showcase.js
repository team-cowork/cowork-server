import { createBackgroundTransition, createSceneTransition } from "../core/transition.js";
import { readMotionDuration } from "../../design-system/tokens.js";
import { createStickyShowcase } from "./sticky-showcase.js";
import { showcaseNumber } from "../core/showcase-state.js";

export function createFeatureShowcase(root) {
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sceneTransition = createSceneTransition(
        root.querySelector("[data-showcase-scene]"),
        {
            enterDuration: readMotionDuration("--duration-scene", root),
            leaveDuration: readMotionDuration("--duration-feature-leave", root),
            name: "feature-fade",
            reducedMotion,
        },
    );
    const backgroundTransition = createBackgroundTransition(
        root.querySelector("[data-showcase-background]"),
        reducedMotion,
    );
    const progressBar = root.querySelector("[data-showcase-progress]");
    const showcase = createStickyShowcase({
        activeDotWidth: "var(--indicator-feature-active)",
        inactiveDotColor: "var(--color-indicator-inverse)",
        onProgress(progress) {
            if (!progressBar) return;
            progressBar.style.transform = `scaleX(${progress})`;
        },
        onStateChange(state, index) {
            if (progressBar) progressBar.style.backgroundColor = state.color;
            backgroundTransition.render({ text: showcaseNumber(index), color: state.color });
            sceneTransition.render(state.sceneInnerHTML);
        },
        reducedMotion,
        root,
        statesUrl: "/data/feature-states.json",
    });

    return {
        mount: showcase.mount,
        unmount() {
            showcase.unmount();
            backgroundTransition.destroy();
            sceneTransition.destroy();
        },
    };
}
