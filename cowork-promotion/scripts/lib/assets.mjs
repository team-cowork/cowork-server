import { createHash } from "node:crypto";

export function createAssetCollection() {
    const files = new Map();

    function add(name, extension, content) {
        const hash = createHash("sha256").update(content).digest("hex").slice(0, 16);
        const path = `assets/${name}.${hash}.${extension}`;
        files.set(path, content);
        return `/${path}`;
    }

    return { add, files };
}
