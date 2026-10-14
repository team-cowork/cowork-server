// Waits for each task to finish before starting the next one.
export async function runInSequence(items, task, index = 0) {
	if (index >= items.length) {
		return;
	}

	await task(items[index]);
	await runInSequence(items, task, index + 1);
}
