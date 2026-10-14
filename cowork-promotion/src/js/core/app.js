export function createApp(components) {
	const mountedComponents = [];
	let isMounted = false;

	async function mountFrom(index) {
		if (index >= components.length) {
			return;
		}

		await components[index].mount();
		mountedComponents.push(components[index]);
		await mountFrom(index + 1);
	}

	async function mount() {
		if (isMounted) {
			return;
		}

		try {
			await mountFrom(0);

			isMounted = true;
		} catch (error) {
			unmount();
			throw error;
		}
	}

	function unmount() {
		while (mountedComponents.length > 0) {
			mountedComponents.pop().unmount();
		}

		isMounted = false;
	}

	return {mount, unmount};
}
