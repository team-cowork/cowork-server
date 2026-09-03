import { elementFromMarkup } from "./dom.js";
import { readMotionDuration } from "../../design-system/tokens.js";

function createScheduler() {
    const timers = new Set();
    const frames = new Set();

    function after(delay, callback) {
        const timer = window.setTimeout(() => {
            timers.delete(timer);
            callback();
        }, delay);
        timers.add(timer);
    }

    function afterTwoFrames(callback) {
        const first = requestAnimationFrame(() => {
            frames.delete(first);
            const second = requestAnimationFrame(() => {
                frames.delete(second);
                callback();
            });
            frames.add(second);
        });
        frames.add(first);
    }

    function clear() {
        timers.forEach((timer) => window.clearTimeout(timer));
        frames.forEach((frame) => cancelAnimationFrame(frame));
        timers.clear();
        frames.clear();
    }

    return { after, afterTwoFrames, clear };
}

export function createSceneTransition(
    element,
    { enterDuration, leaveDuration, name, reducedMotion },
) {
    const scheduler = createScheduler();
    let version = 0;

    function render(html) {
        if (!element) return;

        const currentVersion = ++version;
        const isCurrent = () => currentVersion === version;
        const leave = reducedMotion.matches ? 0 : leaveDuration;
        const enter = reducedMotion.matches ? 0 : enterDuration;

        element.classList.remove(`${name}-enter-active`, `${name}-enter-from`);
        element.classList.add(`${name}-leave-active`, `${name}-leave-to`);

        scheduler.after(leave, () => {
            if (!isCurrent()) return;

            element.innerHTML = html;
            element.classList.remove(`${name}-leave-active`, `${name}-leave-to`);
            element.classList.add(`${name}-enter-active`, `${name}-enter-from`);

            scheduler.afterTwoFrames(() => {
                if (isCurrent()) element.classList.remove(`${name}-enter-from`);
            });
            scheduler.after(enter, () => {
                if (isCurrent()) element.classList.remove(`${name}-enter-active`);
            });
        });
    }

    function destroy() {
        version += 1;
        scheduler.clear();
    }

    return { destroy, render };
}

export function createBackgroundTransition(element, reducedMotion) {
    const scheduler = createScheduler();
    let version = 0;

    function render(markup) {
        if (!element) return;

        const currentVersion = ++version;
        const isCurrent = () => currentVersion === version;
        const duration = reducedMotion.matches ? 0 : readMotionDuration("--duration-scene", element);

        element.classList.remove("bg-index-enter-active", "bg-index-enter-from");
        element.classList.add("bg-index-leave-active", "bg-index-leave-to");

        scheduler.after(duration, () => {
            if (!isCurrent()) return;

            const nextBackground = elementFromMarkup(markup);
            element.textContent = nextBackground?.textContent ?? "";
            element.style.color = nextBackground?.style.color ?? "";
            element.classList.remove("bg-index-leave-active", "bg-index-leave-to");
            element.classList.add("bg-index-enter-active", "bg-index-enter-from");

            scheduler.afterTwoFrames(() => {
                if (isCurrent()) element.classList.remove("bg-index-enter-from");
            });
            scheduler.after(duration, () => {
                if (isCurrent()) element.classList.remove("bg-index-enter-active");
            });
        });
    }

    function destroy() {
        version += 1;
        scheduler.clear();
    }

    return { destroy, render };
}
