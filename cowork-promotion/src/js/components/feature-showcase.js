import {createBackgroundTransition, createSceneTransition} from '../core/transition.js';
import {readMotionDuration} from '../../design-system/tokens.js';
import {showcaseNumber} from '../core/showcase-state.js';
import {window} from '../core/browser.js';
import {createStickyShowcase} from './sticky-showcase.js';
import {bindMockupInteractions} from './mockup-interactions.js';

export function createFeatureShowcase(root) {
	const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
	const scene = root.querySelector('[data-showcase-scene]');
	const sceneTransition = createSceneTransition(
		scene,
		{
			enterDuration: readMotionDuration('--duration-scene', root),
			leaveDuration: readMotionDuration('--duration-feature-leave', root),
			name: 'feature-fade',
			reducedMotion,
		},
	);
	const backgroundTransition = createBackgroundTransition(
		root.querySelector('[data-showcase-background]'),
		reducedMotion,
	);
	const progressBar = root.querySelector('[data-showcase-progress]');
	const showcase = createStickyShowcase({
		activeDotWidth: 'var(--indicator-feature-active)',
		inactiveDotColor: 'var(--color-indicator-inverse)',
		onProgress(progress) {
			if (!progressBar) {
				return;
			}

			progressBar.style.transform = `scaleX(${progress})`;
		},
		onStateChange(state, index) {
			if (progressBar) {
				progressBar.style.backgroundColor = state.color;
			}

			backgroundTransition.render({text: showcaseNumber(index), color: state.color});
			sceneTransition.render(state.sceneInnerHTML);
		},
		reducedMotion,
		root,
		statesUrl: '/data/feature-states.json',
	});

	let unbindMockup = () => {};

	return {
		async mount() {
			unbindMockup = bindMockupInteractions(scene);
			await showcase.mount();
		},
		unmount() {
			unbindMockup();
			showcase.unmount();
			backgroundTransition.destroy();
			sceneTransition.destroy();
		},
	};
}
