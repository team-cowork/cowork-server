export function createTeamMarquee(root) {
    const toggle = root.querySelector("[data-marquee-toggle]");
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let paused = false;

    function render() {
        root.classList.toggle("marquee-paused", paused);
        toggle.hidden = reducedMotion.matches;
        toggle.setAttribute("aria-pressed", String(paused));
        toggle.textContent = paused ? "팀원 자동 이동 재생" : "팀원 자동 이동 일시정지";
    }

    function handleToggle() {
        paused = !paused;
        render();
    }

    function handleFocusOut(event) {
        const viewport = event.target.closest(".marquee-window");
        if (viewport && !viewport.contains(event.relatedTarget)) viewport.scrollLeft = 0;
    }

    return {
        mount() {
            root.classList.add("marquee-ready");
            toggle.addEventListener("click", handleToggle);
            root.addEventListener("focusout", handleFocusOut);
            reducedMotion.addEventListener("change", render);
            render();
        },
        unmount() {
            root.classList.remove("marquee-ready");
            toggle.hidden = true;
            toggle.removeEventListener("click", handleToggle);
            root.removeEventListener("focusout", handleFocusOut);
            reducedMotion.removeEventListener("change", render);
        },
    };
}
