export function createApp(components) {
    const mountedComponents = [];
    let mounted = false;

    async function mount() {
        if (mounted) return;

        try {
            for (const component of components) {
                await component.mount();
                mountedComponents.push(component);
            }
            mounted = true;
        } catch (error) {
            unmount();
            throw error;
        }
    }

    function unmount() {
        while (mountedComponents.length > 0) {
            mountedComponents.pop().unmount();
        }
        mounted = false;
    }

    return { mount, unmount };
}
