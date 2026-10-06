function showcaseGeometry(section) {
    const viewport = section.querySelector('.showcase-viewport');
    // eslint-disable-next-line unicorn/prefer-number-coercion -- CSS values include units such as px and ms.
    const top = Number.parseFloat(getComputedStyle(viewport).top) || 0;
    return { top, scrollable: Math.max(0, section.offsetHeight - viewport.offsetHeight) };
}

export function sectionProgress(section) {
    const rect = section.getBoundingClientRect();
    const { top, scrollable } = showcaseGeometry(section);

    return scrollable <= 0 ? 0 : Math.max(0, Math.min(1, (top - rect.top) / scrollable));
}

export function scrollToSectionState(section, count, index, reducedMotion) {
    const sectionTop = section.getBoundingClientRect().top + window.scrollY;
    const { top, scrollable } = showcaseGeometry(section);

    window.scrollTo({
        top: sectionTop - top + ((index / count) * scrollable) + 1,
        behavior: reducedMotion.matches ? 'auto' : 'smooth',
    });
}
