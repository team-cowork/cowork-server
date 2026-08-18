export function createStore(initialState) {
    let state = Object.freeze({ ...initialState });
    const subscribers = new Set();

    function getSnapshot() {
        return state;
    }

    function setState(update) {
        const partial = typeof update === "function" ? update(state) : update;
        const nextState = { ...state, ...partial };
        const changed = Object.keys(nextState).some(
            (key) => !Object.is(nextState[key], state[key]),
        );
        if (!changed) return state;

        const previousState = state;
        state = Object.freeze(nextState);
        subscribers.forEach((subscriber) => subscriber(state, previousState));
        return state;
    }

    function subscribe(subscriber) {
        subscribers.add(subscriber);
        return () => subscribers.delete(subscriber);
    }

    return { getSnapshot, setState, subscribe };
}
