import {createApp} from './core/app.js';
import {document, window} from './core/browser.js';
import {createAchievementStory} from './components/achievement-story.js';
import {refreshAchievements} from './data/live-achievements.js';

const root = document.querySelector('.achievement-story');
const app = createApp(root ? [createAchievementStory(root)] : []);

window.addEventListener('pagehide', event => {
	if (!event.persisted) {
		app.unmount();
	}
});

try {
	await app.mount();
	refreshAchievements();
} catch (error) {
	console.error('개발 성과 페이지 초기화에 실패했습니다.', error);
}
