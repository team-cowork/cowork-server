import {window} from '../core/browser.js';

const maxTimerSeconds = (99 * 60) + 59;

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
