import { elementFromMarkup } from "../core/dom.js";

function stateColor(state, fallback = "#111827") {
    return elementFromMarkup(state.backgroundOuterHTML)?.style.color || fallback;
}

export async function loadStates(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`${url} 로드 실패: ${response.status}`);
    }

    const states = await response.json();
    if (!Array.isArray(states) || states.length === 0) {
        throw new Error(`${url}에 표시할 상태가 없습니다.`);
    }

    return states.map((state) => ({ ...state, color: stateColor(state) }));
}
