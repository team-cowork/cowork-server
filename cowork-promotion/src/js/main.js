import { createApp } from "./core/app.js";
import { createFeatureShowcase } from "./components/feature-showcase.js";
import { createPositionShowcase } from "./components/position-showcase.js";

const featureRoot = document.querySelector("#features");
const positionRoot = document.querySelector("#positions");
const components = [
    featureRoot && createFeatureShowcase(featureRoot),
    positionRoot && createPositionShowcase(positionRoot),
].filter(Boolean);
const app = createApp(components);

app.mount().catch((error) => {
    console.error("프로모션 페이지 초기화에 실패했습니다.", error);
});
window.addEventListener("pagehide", (event) => {
    if (!event.persisted) app.unmount();
});
