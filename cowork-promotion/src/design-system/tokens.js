export function readDesignToken(name, element = document.documentElement) {
    return getComputedStyle(element).getPropertyValue(name).trim();
}

export function readMotionDuration(name, element) {
    const value = readDesignToken(name, element);
    // eslint-disable-next-line unicorn/prefer-number-coercion -- CSS values include units such as px and ms.
    const duration = Number.parseFloat(value);
    if (!Number.isFinite(duration) || !/m?s$/u.test(value)) {
        throw new Error(`Invalid motion token: ${name}`);
    }

    return value.endsWith('ms') ? duration : duration * 1000;
}
