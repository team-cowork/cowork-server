import {sectionProgress, scrollToSectionState} from '../core/dom.js';
import {createStore} from '../core/store.js';
import {showcaseCounter} from '../core/showcase-state.js';
import {loadStates} from '../data/load-states.js';
import {window} from '../core/browser.js';
import {preloadSceneImages} from '../core/preload.js';

function updateDots(buttons, index, {activeColor, activeWidth, inactiveColor}) {
	for (const [buttonIndex, button] of buttons.entries()) {
		const isActive = buttonIndex === index;
		button.style.setProperty('--dot-width', isActive ? activeWidth : 'var(--indicator-size)');
		button.style.setProperty('--dot-color', isActive ? activeColor : inactiveColor);
		button.setAttribute('aria-current', isActive ? 'step' : 'false');
	}
}

export function createStickyShowcase({
	activeDotWidth,
	inactiveDotColor,
	onProgress,
	onStateChange,
	reducedMotion,
	root,
	statesUrl,
}) {
	const store = createStore({activeIndex: 0, progress: 0, states: []});
	const counter = root.querySelector('[data-showcase-counter]');
	const dots = [...root.querySelectorAll('[data-showcase-dot]')];
	const cleanups = [];
	let scrollFrame = 0;
	let unsubscribe = () => {};

	function render(snapshot, previousSnapshot) {
		const state = snapshot.states[snapshot.activeIndex];
		if (!state) {
			return;
		}

		const isChanged = !previousSnapshot || snapshot.activeIndex !== previousSnapshot.activeIndex;
		if (isChanged) {
			if (counter) {
				counter.textContent = showcaseCounter(snapshot.activeIndex, snapshot.states.length);
			}

			updateDots(dots, snapshot.activeIndex, {activeColor: state.color, activeWidth: activeDotWidth, inactiveColor: inactiveDotColor});
		}

		onProgress?.(snapshot.progress, state);

		if (!(isChanged && previousSnapshot)) {
			return;
		}

		root.querySelector('.showcase-content').scrollTop = 0;
		onStateChange(state, snapshot.activeIndex);
	}

	function sync() {
		const {states} = store.getSnapshot();
		if (states.length === 0) {
			return;
		}

		const progress = sectionProgress(root);
		const activeIndex = Math.min(
			states.length - 1,
			Math.floor(progress * states.length),
		);
		store.setState({activeIndex, progress});
	}

	function scheduleSync() {
		if (scrollFrame) {
			return;
		}

		scrollFrame = window.requestAnimationFrame(() => {
			scrollFrame = 0;
			sync();
		});
	}

	async function mount() {
		const states = await loadStates(statesUrl);
		store.setState({states});
		try {
			preloadSceneImages(states);
		} catch (error) {
			console.warn('쇼케이스 이미지 미리 불러오기에 실패했습니다.', error);
		}

		unsubscribe = store.subscribe(render);

		for (const [index, button] of dots.entries()) {
			const handleClick = () =>
				scrollToSectionState(root, states.length, index, reducedMotion);
			button.addEventListener('click', handleClick);
			cleanups.push(() => button.removeEventListener('click', handleClick));
		}

		const initial = store.getSnapshot();
		render(initial, null);
		sync();
		window.addEventListener('scroll', scheduleSync, {passive: true});
		window.addEventListener('resize', scheduleSync);
		cleanups.push(
			() => window.removeEventListener('scroll', scheduleSync),
			() => window.removeEventListener('resize', scheduleSync),
		);
	}

	function unmount() {
		unsubscribe();
		for (const cleanup of cleanups.splice(0)) {
			cleanup();
		}

		if (scrollFrame) {
			window.cancelAnimationFrame(scrollFrame);
		}

		scrollFrame = 0;
	}

	return {mount, unmount};
}
