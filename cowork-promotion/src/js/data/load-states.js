import { elementFromMarkup } from "../core/dom.js";

function stateColor(state, fallback = "#111827") {
    return elementFromMarkup(state.backgroundOuterHTML)?.style.color || fallback;
}

function embeddedStates(url) {
    const element = Array.from(
        document.querySelectorAll('script[type="application/json"][data-state-url]'),
    ).find((candidate) => candidate.dataset.stateUrl === url);

    return element ? JSON.parse(element.textContent) : null;
}

export async function loadStates(url) {
    let states = embeddedStates(url);
    if (!states) {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`${url} 로드 실패: ${response.status}`);
        }
        states = await response.json();
    }

    if (!Array.isArray(states) || states.length === 0) {
        throw new Error(`${url}에 표시할 상태가 없습니다.`);
    }

    return states.map((state) => ({ ...state, color: stateColor(state) }));
}
