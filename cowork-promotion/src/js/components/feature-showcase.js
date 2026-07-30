import { createBackgroundTransition, createSceneTransition } from "../core/transition.js";
import { createStickyShowcase } from "./sticky-showcase.js";

export function createFeatureShowcase(root) {
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sceneTransition = createSceneTransition(
        root.querySelector('.relative[style*="min-height"] > div'),
        {
            enterDuration: 500,
            leaveDuration: 300,
            name: "feature-fade",
            reducedMotion,
        },
    );
    const backgroundTransition = createBackgroundTransition(
        root.querySelector('[aria-hidden="true"] > span'),
        reducedMotion,
    );
    const progressBar = root.querySelector(".flex-1.h-1 > div");
    const showcase = createStickyShowcase({
        activeDotWidth: "24px",
        inactiveDotColor: "rgba(255, 255, 255, 0.2)",
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
