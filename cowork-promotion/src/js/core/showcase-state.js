export function showcaseNumber(index) {
    return String(index + 1).padStart(2, "0");
}

export function showcaseCounter(index, count) {
    return `${showcaseNumber(index)} / ${String(count).padStart(2, "0")}`;
}
