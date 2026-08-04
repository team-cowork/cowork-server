export function sectionProgress(section) {
    const rect = section.getBoundingClientRect();
    const scrollable = section.offsetHeight - window.innerHeight;

    if (scrollable <= 0) return 0;
    return Math.max(0, Math.min(1, -rect.top / scrollable));
}

export function scrollToSectionState(section, count, index, reducedMotion) {
    const sectionTop = section.getBoundingClientRect().top + window.scrollY;
    const scrollable = section.offsetHeight - window.innerHeight;

    window.scrollTo({
        top: sectionTop + (index / count) * scrollable + 1,
        behavior: reducedMotion.matches ? "auto" : "smooth",
    });
}

export function elementFromMarkup(markup) {
    const template = document.createElement("template");
    template.innerHTML = markup.trim();
    return template.content.firstElementChild;
}
