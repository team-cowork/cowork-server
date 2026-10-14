import {createApp} from './core/app.js';
import {createFeatureShowcase} from './components/feature-showcase.js';
import {createPositionShowcase} from './components/position-showcase.js';
import {createTeamMarquee} from './components/team-marquee.js';
import {document, window} from './core/browser.js';

const featureRoot = document.querySelector('#features');
const positionRoot = document.querySelector('#positions');
const teamRoot = document.querySelector('[data-team-marquee]');
const components = [
	featureRoot && createFeatureShowcase(featureRoot),
	positionRoot && createPositionShowcase(positionRoot),
	teamRoot && createTeamMarquee(teamRoot),
].filter(Boolean);
const app = createApp(components);

window.addEventListener('pagehide', event => {
	if (!event.persisted) {
		app.unmount();
	}
});

try {
	await app.mount();
} catch (error) {
	console.error('프로모션 페이지 초기화에 실패했습니다.', error);
}
