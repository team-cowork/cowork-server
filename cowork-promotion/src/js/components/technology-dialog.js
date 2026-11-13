import {preloadImages} from '../core/preload.js';

export function createTechnologyDialog(root) {
	const dialog = root.querySelector('#technology-dialog');
	const logo = dialog.querySelector('[data-technology-logo]');
	const name = dialog.querySelector('#technology-name');
	const released = dialog.querySelector('#technology-released');
	const description = dialog.querySelector('#technology-description');
	const documentation = dialog.querySelector('[data-technology-documentation]');
	let didStartOnBackdrop = false;

	function handleClick(event) {
		const trigger = event.target.closest('[data-technology]');
		if (!trigger) {
			return;
		}

		name.textContent = trigger.dataset.name;
		logo.src = trigger.dataset.logo;
		released.textContent = trigger.dataset.released;
		description.textContent = trigger.dataset.description;
		documentation.href = trigger.dataset.documentation;
		dialog.showModal();
	}

	function isBackdrop(event) {
		if (event.target !== dialog) {
			return false;
		}

		const {left, right, top, bottom} = dialog.getBoundingClientRect();
		return event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom;
	}

	function handlePointerDown(event) {
		didStartOnBackdrop = isBackdrop(event);
	}

	function handleBackdropClick(event) {
		if (didStartOnBackdrop && isBackdrop(event)) {
			dialog.close();
		}

		didStartOnBackdrop = false;
	}

	return {
		mount() {
			try {
				preloadImages(Array.from(root.querySelectorAll('[data-technology]'), trigger => trigger.dataset.logo));
			} catch (error) {
				console.warn('기술 스택 로고 미리 불러오기에 실패했습니다.', error);
			}

			root.addEventListener('click', handleClick);
			dialog.addEventListener('pointerdown', handlePointerDown);
			dialog.addEventListener('click', handleBackdropClick);
		},
		unmount() {
			root.removeEventListener('click', handleClick);
			dialog.removeEventListener('pointerdown', handlePointerDown);
			dialog.removeEventListener('click', handleBackdropClick);
			dialog.close();
		},
	};
}
