export function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

export function expectArray(value, label) {
    if (!Array.isArray(value) || value.length === 0) {
        throw new Error(`${label} must be a non-empty array.`);
    }
    return value;
}

export function expectString(value, label) {
    if (typeof value !== "string" || value.trim() === "") {
        throw new Error(`${label} must be a non-empty string.`);
    }
    return value.trim();
}

export function expectColor(value, label) {
    const color = expectString(value, label);
    if (!/^#[0-9a-f]{6}$/i.test(color)) {
        throw new Error(`${label} must be a six-digit hex color.`);
    }
    return color.toLowerCase();
}
