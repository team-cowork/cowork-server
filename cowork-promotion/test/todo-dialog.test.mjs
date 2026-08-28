import assert from "node:assert/strict";
import test from "node:test";

import { restoreFocusAndScroll } from "../src/js/components/todo-dialog.js";

test("restores the saved scroll position after focus returns to the opener", () => {
    const calls = [];

    restoreFocusAndScroll({
        focus: () => calls.push("focus"),
        scrollX: 12,
        scrollY: 840,
        scrollTo: (position) => calls.push(position),
    });

    assert.deepEqual(calls, [
        "focus",
        { left: 12, top: 840, behavior: "auto" },
    ]);
});
