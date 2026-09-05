import { createTodoDashboard } from "./components/todo-dashboard.js";
import { createTodoDialog } from "./components/todo-dialog.js";
import { createTodoRouter } from "./core/todo-router.js";
import { loadTodoRegistry } from "./data/load-todos.js";

const dashboardRoot = document.querySelector("[data-todo-dashboard]");
const dialogElement = document.querySelector("[data-todo-dialog]");

async function mountTodoPage() {
    if (!dashboardRoot || !dialogElement) {
        console.error("TODO 페이지에 필요한 markup을 찾을 수 없습니다.");
        return;
    }

    try {
        const registry = await loadTodoRegistry();
        let router;
        const dashboard = createTodoDashboard(dashboardRoot, registry, {
            onQueryChange: (query) => router?.replaceSearchQuery(query),
        });
        const todoDialog = createTodoDialog(dialogElement, {
            onCloseRequest: () => router?.requestClose(),
            onRetry: () => router?.retry(),
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
        const status = dashboardRoot.querySelector("[data-todo-search-status]");
        if (status) {
            status.hidden = false;
            status.textContent = "검색을 준비하지 못했습니다. 새로고침해 주세요. 목록의 문서는 계속 열 수 있습니다.";
        }
    }
}

mountTodoPage();
