import {window} from '../core/browser.js';
import {sectionProgress, scrollToSectionState} from '../core/dom.js';
import {showcaseCounter} from '../core/showcase-state.js';

export function createAchievementStory(root) {
	const scenes = [...root.querySelectorAll('[data-scene]')];
	const dots = [...root.querySelectorAll('[data-achievement-dot]')];
	const counter = root.querySelector('[data-achievement-counter]');
	const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
	let activeIndex = 0;
	let scrollFrame = 0;

	function sync() {
		const progress = reducedMotion.matches ? 0 : sectionProgress(root);
		activeIndex = Math.min(scenes.length - 1, Math.floor(progress * scenes.length));
		root.style.setProperty('--story-progress', progress);
		root.style.setProperty('--scene-progress', Math.min(1, (progress * scenes.length) - activeIndex));
		root.style.setProperty('--ai-reveal', reducedMotion.matches ? 100 : Math.min(100, ((progress * scenes.length) - activeIndex) * 180));
		root.dataset.activeScene = activeIndex;
		for (const [index, scene] of scenes.entries()) {
			const isActive = index === activeIndex;
			scene.classList.toggle('is-active', isActive);
			scene.inert = !reducedMotion.matches && !isActive;
			scene.setAttribute('aria-hidden', String(!reducedMotion.matches && !isActive));
		}

		for (const [index, dot] of dots.entries()) {
			dot.setAttribute('aria-current', index === activeIndex ? 'step' : 'false');
		}

		if (counter) {
			counter.textContent = showcaseCounter(activeIndex, scenes.length);
		}
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

	function goTo(index) {
		const target = Math.max(0, Math.min(scenes.length - 1, index));
		if (reducedMotion.matches) {
			scenes[target].scrollIntoView({block: 'start', behavior: 'instant'});
		} else {
			scrollToSectionState(root, scenes.length, target, reducedMotion);
		}
	}

	function handleClick(event) {
		const dot = event.target.closest('[data-achievement-dot]');
		if (dot) {
			goTo(Number(dot.dataset.achievementDot));
		}
	}

	function handleKey(event) {
		if (event.target !== root && !event.target.closest('[data-achievement-dot]')) {
			return;
		}

		const offset = {ArrowLeft: -1, ArrowRight: 1}[event.key];
		if (!offset) {
			return;
		}

		event.preventDefault();
		goTo(activeIndex + offset);
	}

	function configureMotion() {
		root.classList.toggle('is-enhanced', !reducedMotion.matches);
		sync();
	}

	return {
		mount() {
			root.style.setProperty('--scene-count', scenes.length);
			root.addEventListener('click', handleClick);
			root.addEventListener('keydown', handleKey);
			window.addEventListener('scroll', scheduleSync, {passive: true});
			window.addEventListener('resize', scheduleSync);
			reducedMotion.addEventListener('change', configureMotion);
			configureMotion();
		},
		unmount() {
			root.removeEventListener('click', handleClick);
			root.removeEventListener('keydown', handleKey);
			window.removeEventListener('scroll', scheduleSync);
			window.removeEventListener('resize', scheduleSync);
			reducedMotion.removeEventListener('change', configureMotion);
			window.cancelAnimationFrame(scrollFrame);
			scrollFrame = 0;
		},
	};
}
