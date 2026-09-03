import { createBackgroundTransition, createSceneTransition } from "../core/transition.js";
import { readMotionDuration } from "../../design-system/tokens.js";
import { createStickyShowcase } from "./sticky-showcase.js";

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
        label: "기능",
        onProgress(progress, state) {
            if (!progressBar) return;
            progressBar.style.width = `${progress * 100}%`;
            progressBar.style.backgroundColor = state.color;
        },
        onStateChange(state) {
            backgroundTransition.render(state.backgroundOuterHTML);
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
