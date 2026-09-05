export const homePageDescription = "광주소프트웨어마이스터고등학교 학생들이 만드는 협업 관리 플랫폼";
export const todoPageDescription = "cowork의 진행 중인 개발 작업과 우선순위, 점검 기록을 확인하세요.";

export function todoPageMetadata(documentModel = null) {
    return {
        title: documentModel ? `${documentModel.title} · cowork` : "개발 진행 현황 · cowork",
        description: documentModel?.summary || todoPageDescription,
        type: documentModel ? "article" : "website",
        route: documentModel?.route || "/todo",
    };
}

export function setPageMetadata(metadata) {
    document.title = metadata.title;
    document.querySelector(".skip-link")?.setAttribute("href", metadata.type === "article" ? "#todo-document-title" : "#main-content");
    const canonical = document.querySelector('link[rel="canonical"]');
    const url = new URL(metadata.route, canonical?.href || window.location.origin).href;
    canonical?.setAttribute("href", url);
    for (const [selector, content] of [
        ['meta[name="description"]', metadata.description],
        ['meta[property="og:title"]', metadata.title],
        ['meta[property="og:description"]', metadata.description],
        ['meta[property="og:type"]', metadata.type],
        ['meta[property="og:url"]', url],
    ]) {
        document.querySelector(selector)?.setAttribute("content", content);
    }
}
