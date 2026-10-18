import {document, window} from '../core/browser.js';

const maxTimerSeconds = (99 * 60) + 59;
const springEasing = 'cubic-bezier(0.34, 1.56, 0.64, 1)';
const maxChatMessages = 3;
const dragThreshold = 4;
const typingStartDelay = 300;
const replyDelay = 1700;
const draftReadyDelay = 1950;
const draftMessages = ['좋아요, 저는 리뷰 맡을게요', '회의록은 채널에 공유해 둘게요', '오늘 안에 PR 올리겠습니다'];
const chatReplies = [
	{name: 'Emma', avatar: 'https://i.pravatar.cc/80?img=5', text: '좋아요! 같이 봐요'},
	{name: 'Liam', avatar: 'https://i.pravatar.cc/80?img=33', text: '확인했습니다 👍'},
	{name: 'John', avatar: 'https://i.pravatar.cc/80?img=12', text: '고마워요, 진행해 주세요'},
];
const maxFeedItems = 6;
const feedInterval = 4000;
const feedTypeLabels = {code: '코드', meeting: '회의', task: '태스크'};
const feedEvents = [
	{
		type: 'code',
		name: 'Olivia',
		avatar: 'https://i.pravatar.cc/80?img=47',
		text: '님이 커밋 3개를 푸시했어요',
	},
	{
		type: 'task',
		name: 'Emma',
		avatar: 'https://i.pravatar.cc/80?img=5',
		text: '님이 \'JWT 토큰 갱신\'을 맡았어요',
	},
	{
		type: 'meeting',
		name: 'Liam',
		avatar: 'https://i.pravatar.cc/80?img=33',
		text: '님이 회의록을 공유했어요',
	},
	{
		type: 'code',
		name: 'John',
		avatar: 'https://i.pravatar.cc/80?img=12',
		text: '님이 PR #43 리뷰를 요청했어요',
	},
	{
		type: 'task',
		name: 'Olivia',
		avatar: 'https://i.pravatar.cc/80?img=47',
		text: '님이 \'헬스 체크\'를 완료했어요',
	},
	{
		type: 'meeting',
		name: 'Emma',
		avatar: 'https://i.pravatar.cc/80?img=5',
		text: '님이 스프린트 회고를 예약했어요',
	},
];
const searchKindLabels = {file: '파일', message: '메시지'};
const searchResults = {
	로그인: [
		{
			kind: 'message',
			author: 'Emma',
			channel: '#general',
			time: '어제',
			text: '로그인 플로우 정리해서 공유드려요',
		},
		{
			kind: 'file',
			author: 'Liam',
			channel: '#backend',
			time: '5시간 전',
			text: '로그인-API-명세.pdf',
		},
		{
			kind: 'message',
			author: 'John',
			channel: '#frontend',
			time: '3시간 전',
			text: '로그인 버그 재현됨',
		},
	],
	배포: [
		{
			kind: 'message',
			author: 'Olivia',
			channel: '#devops',
			time: '10분 전',
			text: '오늘 배포는 18시에 진행할게요',
		},
		{
			kind: 'file',
			author: 'Emma',
			channel: '#backend',
			time: '어제',
			text: '배포-체크리스트.md',
		},
		{
			kind: 'message',
			author: 'Liam',
			channel: '#general',
			time: '2일 전',
			text: '배포 끝나면 채널에 알려 주세요',
		},
	],
	회의록: [
		{
			kind: 'file',
			author: 'John',
			channel: '#general',
			time: '어제',
			text: '스프린트-회의록.pdf',
		},
		{
			kind: 'message',
			author: 'Emma',
			channel: '#frontend',
			time: '3시간 전',
			text: '회의록 보고 디자인 반영했어요',
		},
		{
			kind: 'message',
			author: 'Liam',
			channel: '#backend',
			time: '1일 전',
			text: '회의록에 API 일정 추가했어요',
		},
	],
};
const pendingTimers = new Set();
const scrollTimers = new WeakMap();

const toggleValues = {
	call: ['joined', 'left'],
	camera: ['off', 'on'],
	mic: ['on', 'off'],
};

function revealEntries(elements) {
	if (elements.length === 0) {
		return;
	}

	for (const element of elements) {
		popIn(element);
	}

	const scroller = elements.at(-1).closest('[data-mock-scroll]');
	scroller?.scrollTo({top: scroller.scrollHeight, behavior: prefersReducedMotion() ? 'auto' : 'smooth'});
}

function syncVisibility(mockup) {
	const revealed = [];
	for (const element of mockup.querySelectorAll('[data-mock-when]')) {
		const [key, values] = element.dataset.mockWhen.split(':', 2);
		const wasHidden = element.hidden;
		element.hidden = !values.split('|').includes(mockup.dataset[key]);
		if (wasHidden && !element.hidden && element.dataset.mockReveal !== undefined) {
			revealed.push(element);
		}
	}

	revealEntries(revealed);
}

function applyStates(target, mockup) {
	for (const pair of target.dataset.mockSet.split(' ')) {
		const [key, value] = pair.split(':', 2);
		mockup.dataset[key] = value;
	}

	syncVisibility(mockup);
}

function toggleState(mockup, key) {
	const [first, second] = toggleValues[key];
	mockup.dataset[key] = mockup.dataset[key] === first ? second : first;
	syncVisibility(mockup);
}

function prefersReducedMotion() {
	return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function animateItems(items, change) {
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
		animateItems([...grid.children], change);
		exit.cancel();
	} else {
		animateItems([...grid.children], change);
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
		animateItems([...list.children], change);
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

function updateBoard(mockup) {
	const cards = mockup.querySelectorAll('[data-mock-card]');
	const done = mockup.querySelector('[data-mock-column="done"]').querySelectorAll(':scope [data-mock-card]').length;
	for (const column of mockup.querySelectorAll('[data-mock-column]')) {
		column.querySelector('[data-mock-count]').textContent = column.querySelectorAll('[data-mock-card]').length;
	}

	mockup.querySelector('[data-mock-progress-label]').textContent = `${done} / ${cards.length} 완료`;
	mockup.querySelector('[data-mock-progress-bar]').style.width = `${Math.round((done / cards.length) * 100)}%`;
}

function revealInScroller(card) {
	const scroller = card.parentElement;
	const bottom = card.offsetTop + card.offsetHeight;
	if (card.offsetTop < scroller.scrollTop) {
		scroller.scrollTop = card.offsetTop;
	} else if (bottom > scroller.scrollTop + scroller.clientHeight) {
		scroller.scrollTop = bottom - scroller.clientHeight;
	}
}

function placeCard(card, mockup, column, before = null) {
	const change = () => {
		if (before) {
			before.before(card);
		} else {
			column.querySelector('[data-mock-cards]').append(card);
		}

		revealInScroller(card);
		updateBoard(mockup);
	};

	if (prefersReducedMotion()) {
		change();
		return;
	}

	const others = [...mockup.querySelectorAll('[data-mock-card]')].filter(other => other !== card);
	animateItems(others, change);
	popIn(card);
}

function advanceCard(card, mockup) {
	const columns = [...mockup.querySelectorAll('[data-mock-column]')];
	const next = columns[(columns.indexOf(card.closest('[data-mock-column]')) + 1) % columns.length];
	placeCard(card, mockup, next);
}

function dropCard(card, ghost, mockup, {clientX, clientY}) {
	const target = closestElement(document.elementFromPoint(clientX, clientY) ?? mockup, '[data-mock-column]');
	const column = target && mockup.contains(target) ? target : card.closest('[data-mock-column]');
	const before = [...column.querySelectorAll('[data-mock-card]')].find(other => {
		const rect = other.getBoundingClientRect();
		return other !== card && rect.top + (rect.height / 2) > clientY;
	});
	ghost.remove();
	card.classList.remove('mock-drag-source');
	placeCard(card, mockup, column, before ?? null);
}

function createGhost(card, mockup) {
	const cardRect = card.getBoundingClientRect();
	const mockupRect = mockup.getBoundingClientRect();
	const ghost = card.cloneNode(true);
	delete ghost.dataset.mockCard;
	ghost.classList.add('mock-dragging');
	ghost.style.left = `${cardRect.left - mockupRect.left}px`;
	ghost.style.top = `${cardRect.top - mockupRect.top}px`;
	ghost.style.width = `${cardRect.width}px`;
	mockup.append(ghost);
	return ghost;
}

function startCardGesture(event, card, mockup) {
	const startX = event.clientX;
	const startY = event.clientY;
	const canDrag = event.pointerType !== 'touch';
	let ghost;

	const handleMove = moveEvent => {
		const deltaX = moveEvent.clientX - startX;
		const deltaY = moveEvent.clientY - startY;
		if (!ghost) {
			if (!canDrag || Math.hypot(deltaX, deltaY) < dragThreshold) {
				return;
			}

			ghost = createGhost(card, mockup);
			card.classList.add('mock-drag-source');
		}

		ghost.style.transform = `translate(${deltaX}px, ${deltaY}px) rotate(2deg)`;
	};

	const handleEnd = endEvent => {
		card.removeEventListener('pointermove', handleMove);
		card.removeEventListener('pointerup', handleEnd);
		card.removeEventListener('pointercancel', handleEnd);
		if (ghost) {
			dropCard(card, ghost, mockup, endEvent);
		} else if (endEvent.type === 'pointerup') {
			advanceCard(card, mockup);
		}
	};

	card.setPointerCapture(event.pointerId);
	card.addEventListener('pointermove', handleMove);
	card.addEventListener('pointerup', handleEnd);
	card.addEventListener('pointercancel', handleEnd);
}

function showScrollbar(scroller) {
	scroller.classList.add('is-scrolling');
	window.clearTimeout(scrollTimers.get(scroller));
	scrollTimers.set(scroller, window.setTimeout(() => {
		scroller.classList.remove('is-scrolling');
	}, 700));
}

function updateUnread(mockup) {
	const unread = mockup.querySelectorAll('[data-mock-action="read-notification"]:not([data-read="true"])').length;
	const badge = mockup.querySelector('[data-mock-unread]');
	badge.textContent = `새 활동 ${unread}`;
	badge.hidden = unread === 0;
}

function readNotification(item, mockup) {
	item.dataset.read = 'true';
	updateUnread(mockup);
}

function readAll(button, mockup) {
	for (const item of mockup.querySelectorAll('[data-mock-action="read-notification"]')) {
		item.dataset.read = 'true';
	}

	updateUnread(mockup);
}

function isVisibleInFeed(mockup, item) {
	return mockup.dataset.filter === 'all' || item.dataset.type === mockup.dataset.filter;
}

function filterFeed(chip, mockup) {
	for (const other of mockup.querySelectorAll('[data-mock-action="filter-feed"]')) {
		const isActive = other === chip;
		other.classList.toggle('mock-active-chip', isActive);
		other.classList.toggle('mock-muted-chip', !isActive);
	}

	mockup.dataset.filter = chip.dataset.mockFilter;
	for (const item of mockup.querySelector('[data-mock-feed]').querySelectorAll(':scope > [data-type]')) {
		item.hidden = !isVisibleInFeed(mockup, item);
	}
}

function pushFeedEvent(mockup) {
	if (mockup.matches(':hover')) {
		return;
	}

	const turn = Number(mockup.dataset.turn ?? 0);
	const event = feedEvents[turn % feedEvents.length];
	const feed = mockup.querySelector('[data-mock-feed]');
	const item = mockup.querySelector('[data-mock-template="feed-item"]').content.firstElementChild.cloneNode(true);
	mockup.dataset.turn = String(turn + 1);
	item.dataset.type = event.type;
	item.querySelector('img').src = event.avatar;
	setField(item, 'name', event.name);
	setField(item, 'text', event.text);
	setField(item, 'type', feedTypeLabels[event.type]);
	item.hidden = !isVisibleInFeed(mockup, item);

	const change = () => {
		feed.prepend(item);
		while (feed.children.length > maxFeedItems) {
			feed.lastElementChild.remove();
		}

		updateUnread(mockup);
	};

	if (prefersReducedMotion()) {
		change();
		return;
	}

	animateItems([...feed.children], change);
	popIn(item);
}

function applyResultFilter(mockup) {
	const [key, value] = mockup.dataset.resultFilter.split(':', 2);
	const results = [...mockup.querySelectorAll('[data-mock-result]')];
	for (const result of results) {
		result.hidden = key !== 'all' && result.dataset[key] !== value;
	}

	const visible = results.filter(result => !result.hidden).length;
	mockup.querySelector('[data-mock-result-count]').textContent = `결과 ${visible}개`;
}

function filterResults(chip, mockup) {
	for (const other of mockup.querySelectorAll('[data-mock-action="filter-results"]')) {
		const isActive = other === chip;
		other.classList.toggle('mock-active-chip', isActive);
		other.classList.toggle('mock-muted-chip', !isActive);
	}

	mockup.dataset.resultFilter = chip.dataset.mockFilter;
	applyResultFilter(mockup);
}

function setHighlightedText(element, text, keyword) {
	const index = text.indexOf(keyword);
	if (index === -1) {
		element.textContent = text;
		return;
	}

	const mark = document.createElement('mark');
	mark.className = 'mock-highlight';
	mark.textContent = keyword;
	element.replaceChildren(text.slice(0, index), mark, text.slice(index + keyword.length));
}

function renderSearchResults(mockup, query) {
	const container = mockup.querySelector('[data-mock-results]');
	const template = mockup.querySelector('[data-mock-template="search-result"]').content.firstElementChild;
	const rows = searchResults[query].map((result, index) => {
		const row = template.cloneNode(true);
		row.dataset.kind = result.kind;
		row.dataset.author = result.author;
		row.style.animationDelay = `${index * 0.1}s`;
		setField(row, 'kind', searchKindLabels[result.kind]);
		setField(row, 'meta', `${result.author} · ${result.channel} · ${result.time}`);
		setHighlightedText(row.querySelector('[data-mock-field="text"]'), result.text, query);
		return row;
	});
	container.replaceChildren(...rows);
	container.scrollTop = 0;
	applyResultFilter(mockup);
}

function searchQuery(button, mockup) {
	const queryElement = mockup.querySelector('[data-mock-query]');
	const query = button.dataset.mockQuery;
	if (mockup.dataset.searching === 'true' || queryElement.textContent === query) {
		return;
	}

	if (prefersReducedMotion()) {
		queryElement.textContent = query;
		renderSearchResults(mockup, query);
		return;
	}

	mockup.dataset.searching = 'true';
	const previous = [...queryElement.textContent];
	const next = [...query];
	for (const index of previous.keys()) {
		later((index + 1) * 40, () => {
			queryElement.textContent = previous.slice(0, previous.length - index - 1).join('');
		});
	}

	const typingStart = (previous.length * 40) + 120;
	for (const index of next.keys()) {
		later(typingStart + ((index + 1) * 80), () => {
			queryElement.textContent = next.slice(0, index + 1).join('');
		});
	}

	later(typingStart + (next.length * 80) + 150, () => {
		renderSearchResults(mockup, query);
		delete mockup.dataset.searching;
	});
}

const actions = {
	'filter-results': filterResults,
	react: toggleReaction,
	set: applyStates,
	'filter-feed': filterFeed,
	'read-all': readAll,
	'read-notification': readNotification,
	'search-query': searchQuery,
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

	const handlePointerDown = event => {
		const card = closestElement(event.target, '[data-mock-card]');
		const mockup = card?.closest('[data-mockup]');
		if (mockup && event.button === 0) {
			startCardGesture(event, card, mockup);
		}
	};

	const handleScroll = event => {
		const scroller = closestElement(event.target, '[data-mock-scroll]');
		if (scroller === event.target) {
			showScrollbar(scroller);
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

	const feedTimer = window.setInterval(() => {
		for (const mockup of scene.querySelectorAll('[data-mockup="activity"]')) {
			pushFeedEvent(mockup);
		}
	}, feedInterval);

	scene.addEventListener('click', handleClick);
	scene.addEventListener('dragstart', handleDragStart);
	scene.addEventListener('pointerdown', handlePointerDown);
	scene.addEventListener('scroll', handleScroll, {capture: true});
	return () => {
		window.clearInterval(timer);
		window.clearInterval(feedTimer);
		for (const pending of pendingTimers) {
			window.clearTimeout(pending);
		}

		pendingTimers.clear();
		scene.removeEventListener('click', handleClick);
		scene.removeEventListener('dragstart', handleDragStart);
		scene.removeEventListener('pointerdown', handlePointerDown);
		scene.removeEventListener('scroll', handleScroll, {capture: true});
	};
}
