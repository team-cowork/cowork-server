export function createTeamMarquee(root) {
    function handleFocusOut(event) {
        const viewport = event.target.closest(".marquee-window");
        if (viewport && !viewport.contains(event.relatedTarget)) viewport.scrollLeft = 0;
    }

    return {
        mount() {
            root.classList.add("marquee-ready");
            root.addEventListener("focusout", handleFocusOut);
        },
        unmount() {
            root.classList.remove("marquee-ready");
            root.removeEventListener("focusout", handleFocusOut);
        },
    };
}
