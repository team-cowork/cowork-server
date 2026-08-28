import { createTodoDashboard } from "./components/todo-dashboard.js";
import { createTodoDialog } from "./components/todo-dialog.js";
import { createTodoRouter } from "./core/todo-router.js";
import { loadTodoRegistry } from "./data/load-todos.js";

const dashboardRoot = document.querySelector("[data-todo-dashboard]");
const dialogElement = document.querySelector("[data-todo-dialog]");

if (!dashboardRoot || !dialogElement) {
    console.error("TODO 페이지에 필요한 markup을 찾을 수 없습니다.");
} else {
    try {
        const registry = loadTodoRegistry();
        let router;
        const dashboard = createTodoDashboard(dashboardRoot, registry, {
            onQueryChange: (query) => router?.replaceSearchQuery(query),
        });
        const todoDialog = createTodoDialog(dialogElement, {
            onCloseRequest: () => router?.requestClose(),
        });
        router = createTodoRouter({
            root: dashboardRoot,
            registry,
            dashboard,
            dialog: todoDialog,
        });

        dashboard.mount(new URL(window.location.href).searchParams.get("q") || "");
        todoDialog.mount();
        router.mount();

        window.addEventListener("pagehide", (event) => {
            if (event.persisted) return;
            router.unmount();
            todoDialog.unmount();
            dashboard.unmount();
        });
    } catch (error) {
        console.error("TODO 페이지 초기화에 실패했습니다.", error);
    }
}
