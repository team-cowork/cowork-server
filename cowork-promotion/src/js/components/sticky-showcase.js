import { sectionProgress, scrollToSectionState } from "../core/dom.js";
import { createStore } from "../core/store.js";
import { showcaseCounter } from "../core/showcase-state.js";
import { loadStates } from "../data/load-states.js";

function updateDots(buttons, index, activeColor, activeWidth, inactiveColor) {
    buttons.forEach((button, buttonIndex) => {
        const active = buttonIndex === index;
        button.style.setProperty("--dot-width", active ? activeWidth : "var(--indicator-size)");
        button.style.setProperty("--dot-color", active ? activeColor : inactiveColor);
        button.setAttribute("aria-current", active ? "step" : "false");
    });
}

export function createStickyShowcase({
    activeDotWidth,
    inactiveDotColor,
    onProgress,
    onStateChange,
    reducedMotion,
    root,
    statesUrl,
}) {
    const store = createStore({ activeIndex: 0, progress: 0, states: [] });
    const counter = root.querySelector("[data-showcase-counter]");
    const dots = Array.from(root.querySelectorAll("[data-showcase-dot]"));
    const cleanups = [];
    let scrollFrame = 0;
    let unsubscribe = () => {};

    function render(snapshot, previousSnapshot) {
        const state = snapshot.states[snapshot.activeIndex];
        if (!state) return;

        const changed = !previousSnapshot || snapshot.activeIndex !== previousSnapshot.activeIndex;
        if (changed) {
            if (counter) counter.textContent = showcaseCounter(snapshot.activeIndex, snapshot.states.length);
            updateDots(dots, snapshot.activeIndex, state.color, activeDotWidth, inactiveDotColor);
        }
        onProgress?.(snapshot.progress, state);

        if (changed && previousSnapshot) {
            root.querySelector(".showcase-content").scrollTop = 0;
            onStateChange(state, snapshot.activeIndex);
        }
    }

    function sync() {
        const { states } = store.getSnapshot();
        if (states.length === 0) return;

        const progress = sectionProgress(root);
        const activeIndex = Math.min(
            states.length - 1,
            Math.floor(progress * states.length),
        );
        store.setState({ activeIndex, progress });
    }

    function scheduleSync() {
        if (scrollFrame) return;
        scrollFrame = requestAnimationFrame(() => {
            scrollFrame = 0;
            sync();
        });
    }

    async function mount() {
        const states = await loadStates(statesUrl);
        store.setState({ states });
        unsubscribe = store.subscribe(render);

        dots.forEach((button, index) => {
            const handleClick = () =>
                scrollToSectionState(root, states.length, index, reducedMotion);
            button.addEventListener("click", handleClick);
            cleanups.push(() => button.removeEventListener("click", handleClick));
        });

        const initial = store.getSnapshot();
        render(initial, null);
        sync();
        window.addEventListener("scroll", scheduleSync, { passive: true });
        window.addEventListener("resize", scheduleSync);
        cleanups.push(
            () => window.removeEventListener("scroll", scheduleSync),
            () => window.removeEventListener("resize", scheduleSync),
        );
    }

    function unmount() {
        unsubscribe();
        cleanups.splice(0).forEach((cleanup) => cleanup());
        if (scrollFrame) cancelAnimationFrame(scrollFrame);
        scrollFrame = 0;
    }

    return { mount, unmount };
}
