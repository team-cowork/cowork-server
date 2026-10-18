import {window} from '../core/browser.js';

const maxTimerSeconds = (99 * 60) + 59;
const springEasing = 'cubic-bezier(0.34, 1.56, 0.64, 1)';
const maxChatMessages = 3;
const typingStartDelay = 300;
const replyDelay = 1700;
const draftReadyDelay = 1950;
const draftMessages = ['좋아요, 저는 리뷰 맡을게요', '회의록은 채널에 공유해 둘게요', '오늘 안에 PR 올리겠습니다'];
const chatReplies = [
	{name: 'Emma', avatar: 'https://i.pravatar.cc/80?img=5', text: '좋아요! 같이 봐요'},
	{name: 'Liam', avatar: 'https://i.pravatar.cc/80?img=33', text: '확인했습니다 👍'},
	{name: 'John', avatar: 'https://i.pravatar.cc/80?img=12', text: '고마워요, 진행해 주세요'},
];
const pendingTimers = new Set();

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

function later(delay, callback) {
	const timer = window.setTimeout(() => {
		pendingTimers.delete(timer);
		callback();
	}, delay);
	pendingTimers.add(timer);
}

function popIn(element) {
	if (prefersReducedMotion()) {
		return;
	}

	element.animate(
		[{opacity: 0, transform: 'translateY(8px) scale(0.96)'}, {opacity: 1, transform: 'none'}],
		{duration: 420, easing: springEasing},
	);
}

function setField(row, field, value) {
	const element = [...row.querySelectorAll('[data-mock-field]')].find(candidate => candidate.dataset.mockField === field);
	element.textContent = value;
}

function appendMessage(mockup, fill) {
	const list = mockup.querySelector('[data-mock-messages]');
	const row = mockup.querySelector('[data-mock-template="message"]').content.firstElementChild.cloneNode(true);
	fill(row);
	const change = () => {
		list.append(row);
		while (list.children.length > maxChatMessages) {
			list.firstElementChild.remove();
		}
	};

	if (prefersReducedMotion()) {
		change();
	} else {
		animateLayout(list, change);
	}

	popIn(row);
}

function typeDraft(draft, text) {
	if (prefersReducedMotion()) {
		draft.textContent = text;
		return;
	}

	const characters = [...text];
	const interval = (draftReadyDelay - typingStartDelay) / characters.length;
	draft.textContent = '';
	for (const index of characters.keys()) {
		later(typingStartDelay + ((index + 1) * interval), () => {
			draft.textContent = characters.slice(0, index + 1).join('');
		});
	}
}

function sendMessage(button, mockup) {
	if (mockup.dataset.replying === 'true') {
		return;
	}

	const draft = mockup.querySelector('[data-mock-draft]');
	const typing = mockup.querySelector('[data-mock-typing]');
	const turn = Number(mockup.dataset.turn ?? 0);
	const reply = chatReplies[turn % chatReplies.length];
	const text = draft.textContent;
	appendMessage(mockup, row => setField(row, 'text', text));
	mockup.dataset.turn = String(turn + 1);
	mockup.dataset.replying = 'true';
	typeDraft(draft, draftMessages[(turn + 1) % draftMessages.length]);

	later(500, () => {
		typing.querySelector('[data-mock-typing-label]').textContent = `${reply.name} 님이 입력 중`;
		typing.hidden = false;
	});
	later(replyDelay, () => {
		typing.hidden = true;
		appendMessage(mockup, row => {
			setField(row, 'name', reply.name);
			setField(row, 'text', reply.text);
			row.querySelector('img').src = reply.avatar;
		});
	});
	later(draftReadyDelay, () => {
		delete mockup.dataset.replying;
	});
}

function toggleReaction(row) {
	const reaction = row.querySelector('[data-mock-reaction]');
	reaction.hidden = !reaction.hidden;
	if (!reaction.hidden) {
		popIn(reaction);
	}
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
	react: toggleReaction,
	'read-notification': readNotification,
	'send-message': sendMessage,
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
		for (const pending of pendingTimers) {
			window.clearTimeout(pending);
		}

		pendingTimers.clear();
		scene.removeEventListener('click', handleClick);
		scene.removeEventListener('dragstart', handleDragStart);
	};
}
