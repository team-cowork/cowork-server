import {window} from '../core/browser.js';

const maxTimerSeconds = (99 * 60) + 59;
const springEasing = 'cubic-bezier(0.34, 1.56, 0.64, 1)';

const toggleValues = {
	call: ['joined', 'left'],
	camera: ['off', 'on'],
	mic: ['on', 'off'],
	pr: ['open', 'merged'],
};

function syncVisibility(mockup) {
	for (const element of mockup.querySelectorAll('[data-mock-when]')) {
		const [key, value] = element.dataset.mockWhen.split(':', 2);
		element.hidden = mockup.dataset[key] !== value;
	}
}

function toggleState(mockup, key) {
	const [first, second] = toggleValues[key];
	mockup.dataset[key] = mockup.dataset[key] === first ? second : first;
	syncVisibility(mockup);
}

function prefersReducedMotion() {
	return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function animateLayout(container, change) {
	const items = [...container.children];
	const before = new Map(items.map(item => [item, item.getBoundingClientRect()]));
	change();
	for (const item of items) {
		const from = before.get(item);
		const to = item.getBoundingClientRect();
		if (item.hidden || from.width === 0 || to.width === 0) {
			continue;
		}

		const transform = `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width}, ${from.height / to.height})`;
		item.animate(
			[{transform, transformOrigin: 'top left'}, {transform: 'none', transformOrigin: 'top left'}],
			{duration: 520, easing: springEasing},
		);
	}
}

async function toggleCall(button, mockup) {
	if (mockup.dataset.animating === 'true') {
		return;
	}

	const grid = mockup.querySelector('.mock-meeting-grid');
	const selfTile = mockup.querySelector('.mock-self-tile');
	const change = () => toggleState(mockup, 'call');
	if (prefersReducedMotion()) {
		change();
		return;
	}

	mockup.dataset.animating = 'true';
	if (mockup.dataset.call === 'joined') {
		const exit = selfTile.animate(
			[{transform: 'scale(1)', opacity: 1}, {transform: 'scale(1.08)', opacity: 1, offset: 0.35}, {transform: 'scale(0.3)', opacity: 0}],
			{duration: 340, easing: 'ease-in', fill: 'forwards'},
		);
		await exit.finished;
		animateLayout(grid, change);
		exit.cancel();
	} else {
		animateLayout(grid, change);
		await selfTile.animate(
			[{transform: 'scale(0.3)', opacity: 0}, {transform: 'scale(1.08)', opacity: 1, offset: 0.7}, {transform: 'scale(1)', opacity: 1}],
			{duration: 460, easing: 'ease-out'},
		).finished;
	}

	delete mockup.dataset.animating;
}

function advanceCard(card, mockup) {
	const columns = [...mockup.querySelectorAll('[data-mock-column]')];
	const current = card.closest('[data-mock-column]');
	const next = columns[(columns.indexOf(current) + 1) % columns.length];
	next.append(card);
	for (const column of columns) {
		column.querySelector('[data-mock-count]').textContent = column.querySelectorAll('[data-mock-action="advance-card"]').length;
	}
}

function readNotification(item, mockup) {
	if (item.dataset.read === 'true') {
		return;
	}

	item.dataset.read = 'true';
	const unread = mockup.querySelectorAll('[data-mock-action="read-notification"]:not([data-read="true"])').length;
	mockup.querySelector('[data-mock-unread]').textContent = unread > 0 ? `새 알림 ${unread}` : '모두 읽음';
}

function filterResults(chip, mockup) {
	for (const other of mockup.querySelectorAll('[data-mock-action="filter-results"]')) {
		const isActive = other === chip;
		other.classList.toggle('mock-active-chip', isActive);
		other.classList.toggle('mock-muted-chip', !isActive);
	}

	const [key, value] = chip.dataset.mockFilter.split(':', 2);
	for (const result of mockup.querySelectorAll('[data-mock-result]')) {
		result.hidden = key !== 'all' && result.dataset[key] !== value;
	}
}

const actions = {
	'advance-card': advanceCard,
	'filter-results': filterResults,
	'read-notification': readNotification,
	'toggle-call': toggleCall,
	toggle: (target, mockup) => toggleState(mockup, target.dataset.mockKey),
};

function nextTime(text) {
	const [minutes, seconds] = text.split(':', 2).map(Number);
	const total = (minutes * 60) + seconds + 1;
	const next = total > maxTimerSeconds ? 0 : total;
	return [Math.floor(next / 60), next % 60].map(value => String(value).padStart(2, '0')).join(':');
}

function closestElement(node, selector) {
	const element = node.nodeType === 1 ? node : node.parentElement;
	return element?.closest(selector);
}

export function bindMockupInteractions(scene) {
	if (!scene) {
		return () => {};
	}

	const handleClick = event => {
		const target = closestElement(event.target, '[data-mock-action]');
		const mockup = target?.closest('[data-mockup]');
		if (mockup) {
			actions[target.dataset.mockAction]?.(target, mockup);
		}
	};

	const handleDragStart = event => {
		if (closestElement(event.target, '.feature-mockup')) {
			event.preventDefault();
		}
	};

	const timer = window.setInterval(() => {
		for (const element of scene.querySelectorAll('[data-mock-timer]')) {
			element.textContent = nextTime(element.textContent);
		}
	}, 1000);

	scene.addEventListener('click', handleClick);
	scene.addEventListener('dragstart', handleDragStart);
	return () => {
		window.clearInterval(timer);
		scene.removeEventListener('click', handleClick);
		scene.removeEventListener('dragstart', handleDragStart);
	};
}
