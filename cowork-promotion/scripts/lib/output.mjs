import { mkdir, rm, writeFile } from "node:fs/promises";

async function writeOutputFile(outputDirectory, relativePath, content) {
    const fileUrl = new URL(relativePath, outputDirectory);
    await mkdir(new URL("./", fileUrl), { recursive: true });
    await writeFile(fileUrl, content);
}

export async function replaceOutputDirectory(outputDirectory, files) {
    await rm(outputDirectory, { recursive: true, force: true });
    await Promise.all(
        Array.from(files, ([path, content]) =>
            writeOutputFile(outputDirectory, path, content),
        ),
    );
}
