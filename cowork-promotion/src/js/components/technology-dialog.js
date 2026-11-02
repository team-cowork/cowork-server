export function createTechnologyDialog(root) {
	const dialog = root.querySelector('#technology-dialog');
	const name = dialog.querySelector('#technology-name');
	const released = dialog.querySelector('#technology-released');
	const description = dialog.querySelector('#technology-description');
	const documentation = dialog.querySelector('[data-technology-documentation]');

	function handleClick(event) {
		const trigger = event.target.closest('[data-technology]');
		if (!trigger) {
			return;
		}

		name.textContent = trigger.dataset.name;
		released.textContent = trigger.dataset.released;
		description.textContent = trigger.dataset.description;
		documentation.href = trigger.dataset.documentation;
		dialog.showModal();
	}

	function handleBackdropClick(event) {
		if (event.target !== dialog) {
			return;
		}

		const {left, right, top, bottom} = dialog.getBoundingClientRect();
		if (event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom) {
			dialog.close();
		}
	}

	return {
		mount() {
			root.addEventListener('click', handleClick);
			dialog.addEventListener('click', handleBackdropClick);
		},
		unmount() {
			root.removeEventListener('click', handleClick);
			dialog.removeEventListener('click', handleBackdropClick);
			dialog.close();
		},
	};
}
