<script lang="ts" setup>
import rawFeatures from "~/data/features.json";

const people = [
    { name: "John", img: "https://i.pravatar.cc/80?img=12" },
    { name: "Emma", img: "https://i.pravatar.cc/80?img=5" },
    { name: "Liam", img: "https://i.pravatar.cc/80?img=33" },
    { name: "Olivia", img: "https://i.pravatar.cc/80?img=47" },
];

interface Feature {
    id: string;
    index: string;
    label: string;
    color: string;
    title: string;
    description: string;
    tags: string[];
}

const features = rawFeatures as Feature[];

// 각 장면이 머무는 스크롤 구간(화면 높이 배수). 클수록 한 장면을 더 오래 본다.
const DWELL_VH = 70;

const rootRef = ref<HTMLElement | null>(null);
const activeIndex = ref(0);
const progress = ref(0);

const activeFeature = computed(() => features[activeIndex.value]);
const activeColor = computed(() => activeFeature.value?.color ?? "#111827");

// 전체 섹션 높이: 장면 수만큼 머무름 구간 + 마지막 장면을 위한 1화면
const sectionHeight = computed(
    () => `calc(${features.length} * ${DWELL_VH}vh + 100vh)`,
);

const scrollToIndex = (i: number) => {
    if (!import.meta.client || !rootRef.value) return;
    const top = rootRef.value.getBoundingClientRect().top + window.scrollY;
    const scrollable = rootRef.value.offsetHeight - window.innerHeight;
    window.scrollTo({
        top: top + (i / features.length) * scrollable + 1,
        behavior: "smooth",
    });
};

onMounted(() => {
    const handleScroll = () => {
        if (!rootRef.value) return;
        const rect = rootRef.value.getBoundingClientRect();
        const scrollable = rootRef.value.offsetHeight - window.innerHeight;
        if (scrollable <= 0) return;
        const p = Math.max(0, Math.min(1, -rect.top / scrollable));
        progress.value = p;
        activeIndex.value = Math.min(
            features.length - 1,
            Math.floor(p * features.length),
        );
    };
    window.addEventListener("scroll", handleScroll, { passive: true });
    handleScroll();
    onUnmounted(() => window.removeEventListener("scroll", handleScroll));
});
</script>

<template>
    <section
        ref="rootRef"
        class="relative bg-gray-950"
        :style="{ height: sectionHeight }"
    >
        <div
            class="sticky top-0 h-screen overflow-hidden flex flex-col justify-center text-white"
        >
            <!-- Background morphing index -->
            <div
                class="absolute inset-0 flex items-center pointer-events-none select-none overflow-hidden"
                aria-hidden="true"
            >
                <Transition name="bg-index" mode="out-in">
                    <span
                        :key="activeIndex"
                        class="font-black leading-none"
                        style="
                            font-size: clamp(220px, 40vw, 560px);
                            line-height: 1;
                            padding-left: 3vw;
                            opacity: 0.07;
                        "
                        :style="{ color: activeColor }"
                    >
                        {{ features[activeIndex]?.index }}
                    </span>
                </Transition>
            </div>

            <!-- Section label -->
            <div
                class="absolute top-0 left-0 right-0 z-20 max-w-6xl mx-auto px-6 w-full pt-8 flex items-center justify-between"
            >
                <span
                    class="text-xs font-semibold uppercase tracking-widest text-gray-500"
                    >Features</span
                >
                <span class="text-xs text-gray-500 font-medium tabular-nums">
                    {{ features[activeIndex]?.index }} /
                    {{ String(features.length).padStart(2, "0") }}
                </span>
            </div>

            <!-- Sticky scene: 한 장면에 머무르고, 전환은 fade in/out 만 -->
            <div class="max-w-6xl mx-auto px-6 w-full relative z-10">
                <div class="relative" style="min-height: 60vh">
                    <Transition name="feature-fade" mode="out-in">
                        <div
                            v-if="activeFeature"
                            :key="activeFeature.id"
                            class="absolute inset-0 grid grid-cols-1 lg:grid-cols-2 gap-8 md:gap-12 items-center content-center"
                        >
                        <!-- Left: copy -->
                        <div>
                            <div
                                class="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-semibold mb-6"
                                :style="{
                                    backgroundColor: `${activeFeature.color}22`,
                                    color: activeFeature.color,
                                }"
                            >
                                <span
                                    class="w-1.5 h-1.5 rounded-full"
                                    :style="{ backgroundColor: activeFeature.color }"
                                />
                                {{ activeFeature.label }}
                            </div>
                            <h2
                                class="text-4xl lg:text-6xl font-black tracking-tight leading-[1.05] mb-6"
                            >
                                {{ activeFeature.title }}
                            </h2>
                            <p
                                class="text-gray-400 text-base lg:text-lg leading-relaxed mb-7 max-w-md"
                            >
                                {{ activeFeature.description }}
                            </p>
                            <div class="flex flex-wrap gap-2">
                                <span
                                    v-for="tag in activeFeature.tags"
                                    :key="tag"
                                    class="text-xs font-semibold px-3 py-1.5 rounded-full border"
                                    :style="{
                                        color: activeFeature.color,
                                        borderColor: `${activeFeature.color}40`,
                                        backgroundColor: `${activeFeature.color}12`,
                                    }"
                                    >{{ tag }}</span
                                >
                            </div>
                        </div>

                        <!-- Right: per-feature visual (realistic UI mockup) -->
                        <div
                            class="relative aspect-[4/3] rounded-3xl border border-white/10 bg-gradient-to-br from-white/[0.07] to-white/[0.02] p-4 sm:p-5"
                        >
                            <!-- 01 Workspace -->
                            <div
                                v-if="activeFeature.id === 'workspace'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-white text-gray-900 shadow-2xl overflow-hidden flex flex-col"
                            >
                                <div
                                    class="flex items-center justify-between px-4 py-3 border-b border-gray-100"
                                >
                                    <div class="flex items-center gap-2">
                                        <span
                                            class="w-2 h-2 rounded-full"
                                            :style="{
                                                backgroundColor: activeFeature.color,
                                            }"
                                        />
                                        <span class="text-xs font-bold"
                                            >백엔드 워크스페이스</span
                                        >
                                    </div>
                                    <div class="flex items-center gap-2">
                                        <div
                                            class="flex -space-x-2 items-center"
                                        >
                                            <span
                                                v-for="p in people.slice(0, 3)"
                                                :key="p.name"
                                                class="relative"
                                            >
                                                <img
                                                    :src="p.img"
                                                    :alt="p.name"
                                                    class="w-6 h-6 rounded-full ring-2 ring-white object-cover bg-gray-100"
                                                />
                                                <span
                                                    class="absolute -bottom-0.5 -right-0.5 w-2 h-2 rounded-full bg-emerald-500 ring-2 ring-white"
                                                />
                                            </span>
                                        </div>
                                        <span
                                            class="text-[10px] font-semibold text-gray-400 whitespace-nowrap"
                                            >3명 접속 중</span
                                        >
                                    </div>
                                </div>
                                <div class="flex-1 p-4 space-y-2.5 relative">
                                    <div
                                        class="text-[11px] font-bold text-gray-700"
                                    >
                                        회의록 · 스프린트 계획
                                    </div>
                                    <div
                                        class="h-2 w-11/12 rounded bg-gray-100"
                                    />
                                    <div
                                        class="h-2 w-4/5 rounded bg-gray-100"
                                    />
                                    <div
                                        class="h-2 w-2/3 rounded bg-gray-100"
                                    />
                                    <div
                                        class="h-2 w-3/4 rounded bg-gray-100"
                                    />
                                    <!-- live named cursor -->
                                    <div
                                        class="absolute ws-cursor"
                                        style="top: 40%; left: 55%"
                                    >
                                        <svg
                                            width="18"
                                            height="18"
                                            viewBox="0 0 24 24"
                                            :fill="activeFeature.color"
                                        >
                                            <path d="M4 2l16 8-7 2-2 7z" />
                                        </svg>
                                        <span
                                            class="ml-3 -mt-1 inline-block px-1.5 py-0.5 rounded text-[9px] font-bold text-white whitespace-nowrap"
                                            :style="{
                                                backgroundColor: activeFeature.color,
                                            }"
                                            >{{ people[1]?.name }}</span
                                        >
                                    </div>
                                </div>
                            </div>

                            <!-- 02 Kanban -->
                            <div
                                v-else-if="activeFeature.id === 'kanban'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-white text-gray-900 shadow-2xl overflow-hidden p-3 grid grid-cols-3 gap-2"
                            >
                                <div
                                    v-for="(col, ci) in [
                                        {
                                            title: '할 일',
                                            cards: [
                                                'JWT 토큰 갱신',
                                                '알림 스키마 설계',
                                            ],
                                        },
                                        {
                                            title: '진행 중',
                                            cards: ['로그인 API 구현'],
                                        },
                                        {
                                            title: '완료',
                                            cards: [
                                                'DB 마이그레이션',
                                                '헬스 체크',
                                            ],
                                        },
                                    ]"
                                    :key="ci"
                                    class="flex flex-col gap-2 min-w-0"
                                >
                                    <div
                                        class="flex items-center justify-between px-1"
                                    >
                                        <span
                                            class="text-[10px] font-bold text-gray-500"
                                            >{{ col.title }}</span
                                        >
                                        <span
                                            class="text-[9px] text-gray-300 font-semibold"
                                            >{{ col.cards.length }}</span
                                        >
                                    </div>
                                    <div
                                        v-for="(card, idx) in col.cards"
                                        :key="card"
                                        class="rounded-lg bg-gray-50 border border-gray-100 p-2 kanban-card"
                                        :style="{
                                            animationDelay: `${(ci + idx) * 0.15}s`,
                                        }"
                                    >
                                        <div
                                            class="flex items-center gap-1 mb-1.5"
                                        >
                                            <span
                                                class="text-[7px] font-bold px-1 py-0.5 rounded"
                                                :style="{
                                                    color:
                                                        ci === 2
                                                            ? '#10B981'
                                                            : activeFeature.color,
                                                    backgroundColor: `${ci === 2 ? '#10B981' : activeFeature.color}1A`,
                                                }"
                                                >{{
                                                    ci === 2
                                                        ? "완료"
                                                        : "Backend"
                                                }}</span
                                            >
                                        </div>
                                        <p
                                            class="text-[9px] font-semibold leading-tight text-gray-700 mb-1.5 truncate"
                                        >
                                            {{ card }}
                                        </p>
                                        <div
                                            class="flex items-center justify-between"
                                        >
                                            <img
                                                :src="
                                                    people[
                                                        (ci + idx) %
                                                            people.length
                                                    ].img
                                                "
                                                class="w-4 h-4 rounded-full object-cover bg-gray-100"
                                                :alt="
                                                    people[
                                                        (ci + idx) %
                                                            people.length
                                                    ].name
                                                "
                                            />
                                            <svg
                                                v-if="ci === 2"
                                                width="11"
                                                height="11"
                                                viewBox="0 0 24 24"
                                                fill="#10B981"
                                            >
                                                <path
                                                    d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"
                                                />
                                            </svg>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- 03 Meeting -->
                            <div
                                v-else-if="activeFeature.id === 'meeting'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-gray-900 text-white shadow-2xl overflow-hidden flex flex-col"
                            >
                                <div
                                    class="flex items-center justify-between px-3 py-2 bg-black/30"
                                >
                                    <div class="flex items-center gap-1.5">
                                        <span
                                            class="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse"
                                        />
                                        <span class="text-[10px] font-semibold"
                                            >회의 중 · 12:04</span
                                        >
                                    </div>
                                    <span class="text-[10px] text-white/50"
                                        >4명 참여</span
                                    >
                                </div>
                                <div
                                    class="flex-1 grid grid-cols-2 grid-rows-2 gap-1.5 p-1.5"
                                >
                                    <div
                                        v-for="(p, n) in people"
                                        :key="p.name"
                                        class="relative rounded-lg bg-gray-800 flex items-center justify-center overflow-hidden meet-tile"
                                        :style="{
                                            animationDelay: `${n * 0.2}s`,
                                        }"
                                    >
                                        <img
                                            :src="p.img"
                                            :alt="p.name"
                                            class="w-9 h-9 rounded-full object-cover ring-2 ring-white/10"
                                        />
                                        <span
                                            class="absolute bottom-1 left-1 text-[8px] font-semibold bg-black/50 px-1.5 py-0.5 rounded"
                                            >{{ p.name }}</span
                                        >
                                        <span
                                            class="absolute bottom-1 right-1 w-4 h-4 rounded-full flex items-center justify-center"
                                            :style="{
                                                backgroundColor:
                                                    n === 0
                                                        ? activeFeature.color
                                                        : 'rgba(255,255,255,0.15)',
                                            }"
                                        >
                                            <svg
                                                v-if="n !== 0"
                                                width="9"
                                                height="9"
                                                viewBox="0 0 24 24"
                                                fill="white"
                                            >
                                                <path
                                                    d="M16.5 12c0-1.77-1-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12h-2zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a8.99 8.99 0 0 0 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4 9.91 6.09 12 8.18V4z"
                                                />
                                            </svg>
                                            <svg
                                                v-else
                                                width="9"
                                                height="9"
                                                viewBox="0 0 24 24"
                                                fill="white"
                                            >
                                                <path
                                                    d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11z"
                                                />
                                            </svg>
                                        </span>
                                    </div>
                                </div>
                                <div
                                    class="flex items-center justify-center gap-2 py-2 bg-black/30"
                                >
                                    <span
                                        class="w-7 h-7 rounded-full bg-white/15 flex items-center justify-center"
                                    >
                                        <svg
                                            width="13"
                                            height="13"
                                            viewBox="0 0 24 24"
                                            fill="white"
                                        >
                                            <path
                                                d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11z"
                                            />
                                        </svg>
                                    </span>
                                    <span
                                        class="w-7 h-7 rounded-full bg-white/15 flex items-center justify-center"
                                    >
                                        <svg
                                            width="13"
                                            height="13"
                                            viewBox="0 0 24 24"
                                            fill="white"
                                        >
                                            <path
                                                d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"
                                            />
                                        </svg>
                                    </span>
                                    <span
                                        class="w-7 h-7 rounded-full bg-red-500 flex items-center justify-center"
                                    >
                                        <svg
                                            width="13"
                                            height="13"
                                            viewBox="0 0 24 24"
                                            fill="white"
                                        >
                                            <path
                                                d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85a.99.99 0 0 1-1.41-.03L.29 13.08a.99.99 0 0 1 .03-1.42C3.34 8.78 7.46 7 12 7s8.66 1.78 11.69 4.66c.39.39.4 1.02.03 1.42l-2.48 2.46c-.37.39-1 .4-1.41.03a11.6 11.6 0 0 0-2.66-1.85.99.99 0 0 1-.56-.9v-3.1A15.6 15.6 0 0 0 12 9z"
                                            />
                                        </svg>
                                    </span>
                                </div>
                            </div>

                            <!-- 04 GitHub -->
                            <div
                                v-else-if="activeFeature.id === 'github'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-white text-gray-900 shadow-2xl overflow-hidden flex flex-col"
                            >
                                <div class="px-4 py-3 border-b border-gray-100">
                                    <div
                                        class="flex items-center gap-1.5 text-[10px] text-gray-400 font-mono mb-1.5"
                                    >
                                        <svg
                                            width="11"
                                            height="11"
                                            viewBox="0 0 24 24"
                                            fill="currentColor"
                                        >
                                            <path
                                                d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57v-2.235c-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22v3.3c0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"
                                            />
                                        </svg>
                                        team-cowork / cowork-server
                                    </div>
                                    <div class="flex items-center gap-2">
                                        <span
                                            class="text-[9px] font-bold text-white px-1.5 py-0.5 rounded-full flex items-center gap-1"
                                            :style="{
                                                backgroundColor: activeFeature.color,
                                            }"
                                        >
                                            <svg
                                                width="8"
                                                height="8"
                                                viewBox="0 0 16 16"
                                                fill="white"
                                            >
                                                <path
                                                    d="M5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0zm0 2.122a2.25 2.25 0 1 0-1.5 0v5.256a2.251 2.251 0 1 0 1.5 0zm5.625-.872a1.125 1.125 0 1 1 0 2.25 1.125 1.125 0 0 1 0-2.25zm.75-2.122a2.25 2.25 0 0 0-.75 4.372V8.5a2.5 2.5 0 0 1-2.5 2.5h-.5a.75.75 0 0 0 0 1.5h.5a4 4 0 0 0 4-4V6.622a2.251 2.251 0 0 0-.75-4.372z"
                                                />
                                            </svg>
                                            Pull Request
                                        </span>
                                        <span
                                            class="text-[11px] font-bold text-gray-800 truncate"
                                            >feat: 사용자 인증 API 추가</span
                                        >
                                    </div>
                                </div>
                                <div class="flex-1 px-4 py-3 space-y-2">
                                    <div
                                        class="flex items-center gap-2 text-[10px] text-gray-500"
                                    >
                                        <img
                                            :src="people[1].img"
                                            class="w-4 h-4 rounded-full object-cover"
                                            :alt="people[1].name"
                                        />
                                        <span
                                            ><b class="text-gray-700">{{
                                                people[1].name
                                            }}</b
                                            >님이
                                            <span
                                                class="font-mono text-gray-400"
                                                >feat/auth</span
                                            >
                                            →
                                            <span
                                                class="font-mono text-gray-400"
                                                >develop</span
                                            ></span
                                        >
                                    </div>
                                    <div
                                        class="flex items-center gap-1.5 text-[10px] font-semibold text-emerald-600"
                                    >
                                        <svg
                                            width="12"
                                            height="12"
                                            viewBox="0 0 24 24"
                                            fill="#10B981"
                                        >
                                            <path
                                                d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"
                                            />
                                        </svg>
                                        검사 2개 통과 · 충돌 없음
                                    </div>
                                    <div
                                        class="inline-flex items-center gap-1.5 text-[9px] font-semibold px-2 py-1 rounded-md"
                                        :style="{
                                            color: activeFeature.color,
                                            backgroundColor: `${activeFeature.color}14`,
                                        }"
                                    >
                                        <svg
                                            width="9"
                                            height="9"
                                            viewBox="0 0 24 24"
                                            fill="none"
                                            :stroke="activeFeature.color"
                                            stroke-width="2.5"
                                            stroke-linecap="round"
                                        >
                                            <path
                                                d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1"
                                            />
                                            <path
                                                d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"
                                            />
                                        </svg>
                                        TASK-12 · 로그인 API 구현 에 연결됨
                                    </div>
                                </div>
                                <div class="px-4 py-3 border-t border-gray-100">
                                    <button
                                        class="w-full py-2 rounded-lg text-[11px] font-bold text-white"
                                        :style="{
                                            backgroundColor: activeFeature.color,
                                        }"
                                    >
                                        Merge pull request
                                    </button>
                                </div>
                            </div>

                            <!-- 05 Notification -->
                            <div
                                v-else-if="activeFeature.id === 'notification'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-white text-gray-900 shadow-2xl overflow-hidden flex flex-col"
                            >
                                <div
                                    class="flex items-center justify-between px-4 py-3 border-b border-gray-100"
                                >
                                    <span class="text-xs font-bold">알림</span>
                                    <span
                                        class="text-[9px] font-bold text-white px-1.5 py-0.5 rounded-full"
                                        :style="{
                                            backgroundColor: activeFeature.color,
                                        }"
                                        >새 알림 3</span
                                    >
                                </div>
                                <div
                                    class="flex-1 p-3 space-y-2 overflow-hidden"
                                >
                                    <div
                                        v-for="(noti, n) in [
                                            {
                                                who: 0,
                                                text: '님이 회의를 시작했어요',
                                                time: '방금 전',
                                                icon: 'meet',
                                            },
                                            {
                                                who: 2,
                                                text: '님이 회원님을 멘션했어요',
                                                time: '2분 전',
                                                icon: 'at',
                                            },
                                            {
                                                who: 1,
                                                text: '님의 PR #42가 머지됐어요',
                                                time: '5분 전',
                                                icon: 'merge',
                                            },
                                        ]"
                                        :key="n"
                                        class="flex items-center gap-2.5 rounded-xl bg-gray-50 border border-gray-100 p-2.5 noti-toast"
                                        :style="{
                                            animationDelay: `${n * 0.4}s`,
                                        }"
                                    >
                                        <span class="relative shrink-0">
                                            <img
                                                :src="people[noti.who].img"
                                                class="w-8 h-8 rounded-full object-cover bg-gray-100"
                                                :alt="people[noti.who].name"
                                            />
                                            <span
                                                class="absolute -bottom-0.5 -right-0.5 w-4 h-4 rounded-full flex items-center justify-center ring-2 ring-white"
                                                :style="{
                                                    backgroundColor:
                                                        activeFeature.color,
                                                }"
                                            >
                                                <svg
                                                    v-if="noti.icon === 'meet'"
                                                    width="8"
                                                    height="8"
                                                    viewBox="0 0 24 24"
                                                    fill="white"
                                                >
                                                    <path
                                                        d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"
                                                    />
                                                </svg>
                                                <svg
                                                    v-else-if="
                                                        noti.icon === 'at'
                                                    "
                                                    width="8"
                                                    height="8"
                                                    viewBox="0 0 24 24"
                                                    fill="white"
                                                >
                                                    <path
                                                        d="M12 2a10 10 0 1 0 4 19.2l-.8-1.8A8 8 0 1 1 20 12c0 1.1-.5 2-1.5 2s-1.5-.9-1.5-2V8h-2v.7A4 4 0 1 0 16 14.5c.6.9 1.6 1.5 2.5 1.5 2.2 0 3.5-1.9 3.5-4A10 10 0 0 0 12 2zm0 13a3 3 0 1 1 0-6 3 3 0 0 1 0 6z"
                                                    />
                                                </svg>
                                                <svg
                                                    v-else
                                                    width="8"
                                                    height="8"
                                                    viewBox="0 0 16 16"
                                                    fill="white"
                                                >
                                                    <path
                                                        d="M5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0zm0 2.122a2.25 2.25 0 1 0-1.5 0v5.256a2.251 2.251 0 1 0 1.5 0zm5.625-.872a1.125 1.125 0 1 1 0 2.25 1.125 1.125 0 0 1 0-2.25zm.75-2.122a2.25 2.25 0 0 0-.75 4.372V8.5a2.5 2.5 0 0 1-2.5 2.5h-.5a.75.75 0 0 0 0 1.5h.5a4 4 0 0 0 4-4V6.622a2.251 2.251 0 0 0-.75-4.372z"
                                                    />
                                                </svg>
                                            </span>
                                        </span>
                                        <div class="flex-1 min-w-0">
                                            <p
                                                class="text-[10px] leading-snug text-gray-700 truncate"
                                            >
                                                <b class="text-gray-900">{{
                                                    people[noti.who].name
                                                }}</b
                                                >{{ noti.text }}
                                            </p>
                                            <span
                                                class="text-[9px] text-gray-400"
                                                >{{ noti.time }}</span
                                            >
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <!-- 06 Search -->
                            <div
                                v-else-if="activeFeature.id === 'search'"
                                class="absolute inset-4 sm:inset-5 rounded-2xl bg-white text-gray-900 shadow-2xl overflow-hidden flex flex-col"
                            >
                                <div class="p-3 border-b border-gray-100">
                                    <div
                                        class="flex items-center gap-2 rounded-lg bg-gray-100 px-3 py-2"
                                    >
                                        <svg
                                            width="14"
                                            height="14"
                                            viewBox="0 0 24 24"
                                            fill="none"
                                            :stroke="activeFeature.color"
                                            stroke-width="2.5"
                                            stroke-linecap="round"
                                        >
                                            <circle cx="11" cy="11" r="7" />
                                            <line
                                                x1="21"
                                                y1="21"
                                                x2="16.5"
                                                y2="16.5"
                                            />
                                        </svg>
                                        <span
                                            class="text-[11px] font-semibold text-gray-700"
                                            >로그인<span class="caret"
                                                >|</span
                                            ></span
                                        >
                                    </div>
                                    <div class="flex items-center gap-1.5 mt-2">
                                        <span
                                            v-for="(chip, ci) in [
                                                '전체',
                                                '문서',
                                                '태스크',
                                                '메시지',
                                            ]"
                                            :key="chip"
                                            class="text-[9px] font-semibold px-2 py-0.5 rounded-full"
                                            :style="
                                                ci === 0
                                                    ? {
                                                          color: '#fff',
                                                          backgroundColor:
                                                              activeFeature.color,
                                                      }
                                                    : {
                                                          color: '#6B7280',
                                                          backgroundColor:
                                                              '#F3F4F6',
                                                      }
                                            "
                                            >{{ chip }}</span
                                        >
                                    </div>
                                </div>
                                <div
                                    class="flex-1 p-3 space-y-1.5 overflow-hidden"
                                >
                                    <div
                                        v-for="(r, n) in [
                                            {
                                                type: '문서',
                                                title: '로그인 플로우 정리',
                                                sub: '회의록 · 어제',
                                            },
                                            {
                                                type: '태스크',
                                                title: '로그인 API 구현',
                                                sub: '진행 중 · TASK-12',
                                            },
                                            {
                                                type: '메시지',
                                                title: '로그인 버그 재현됨',
                                                sub: '#backend · 3시간 전',
                                            },
                                        ]"
                                        :key="n"
                                        class="flex items-center gap-2.5 rounded-lg p-2 hover:bg-gray-50 search-result"
                                        :style="{
                                            animationDelay: `${n * 0.2}s`,
                                        }"
                                    >
                                        <span
                                            class="text-[8px] font-bold px-1.5 py-0.5 rounded shrink-0"
                                            :style="{
                                                color: activeFeature.color,
                                                backgroundColor: `${activeFeature.color}1A`,
                                            }"
                                            >{{ r.type }}</span
                                        >
                                        <div class="min-w-0">
                                            <p
                                                class="text-[10px] font-semibold text-gray-800 truncate"
                                            >
                                                {{ r.title }}
                                            </p>
                                            <span
                                                class="text-[9px] text-gray-400"
                                                >{{ r.sub }}</span
                                            >
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>
                        </div>
                    </Transition>
                </div>
            </div>

            <!-- Progress bar -->
            <div
                class="absolute bottom-0 left-0 right-0 z-20 max-w-6xl mx-auto px-6 w-full pb-8 block"
            >
                <div class="flex items-center gap-3">
                    <div
                        class="flex-1 h-1 rounded-full bg-white/10 overflow-hidden"
                    >
                        <div
                            class="h-full rounded-full"
                            :style="{
                                width: `${progress * 100}%`,
                                backgroundColor: activeColor,
                            }"
                        />
                    </div>
                    <div class="flex items-center gap-2">
                        <button
                            v-for="(f, i) in features"
                            :key="f.id"
                            class="h-1.5 rounded-full transition-all duration-300 cursor-pointer"
                            :style="{
                                width: activeIndex === i ? '24px' : '8px',
                                backgroundColor:
                                    activeIndex === i
                                        ? activeColor
                                        : 'rgba(255,255,255,0.2)',
                            }"
                            @click="scrollToIndex(i)"
                        />
                    </div>
                </div>
            </div>
        </div>
    </section>
</template>

<style scoped>
/* 장면 전환: fade in/out 만 (슬라이드 없음) */
.feature-fade-enter-active {
    transition: opacity 0.5s ease;
}
.feature-fade-leave-active {
    transition: opacity 0.3s ease;
}
.feature-fade-enter-from,
.feature-fade-leave-to {
    opacity: 0;
}

.bg-index-enter-active,
.bg-index-leave-active {
    transition:
        opacity 0.5s ease,
        transform 0.5s ease;
}
.bg-index-enter-from {
    opacity: 0;
    transform: translateX(40px);
}
.bg-index-leave-to {
    opacity: 0;
    transform: translateX(-40px);
}

.ws-cursor {
    animation: ws-cursor-move 4s ease-in-out infinite;
}
@keyframes ws-cursor-move {
    0%,
    100% {
        transform: translate(0, 0);
    }
    50% {
        transform: translate(-40px, 30px);
    }
}

.kanban-card {
    animation: kanban-slide 2.5s ease-in-out infinite alternate;
}
@keyframes kanban-slide {
    0%,
    70% {
        transform: translateX(0);
    }
    100% {
        transform: translateX(4px);
    }
}

.meet-tile {
    animation: meet-pulse 2.4s ease-in-out infinite;
}
@keyframes meet-pulse {
    0%,
    100% {
        opacity: 0.85;
    }
    50% {
        opacity: 1;
    }
}

.noti-toast {
    animation: noti-rise 3.2s ease-in-out infinite;
}
@keyframes noti-rise {
    0% {
        opacity: 0;
        transform: translateY(20px);
    }
    25%,
    100% {
        opacity: 1;
        transform: translateY(0);
    }
}

.caret {
    animation: blink 1s step-end infinite;
}
@keyframes blink {
    50% {
        opacity: 0;
    }
}
.search-result {
    animation: search-focus 3s ease-in-out infinite;
}
@keyframes search-focus {
    0%,
    30% {
        opacity: 0;
        filter: blur(4px);
    }
    50%,
    100% {
        opacity: 1;
        filter: blur(0);
    }
}

@media (prefers-reduced-motion: reduce) {
    .ws-cursor,
    .kanban-card,
    .meet-tile,
    .noti-toast,
    .search-result,
    .caret {
        animation: none;
    }
}
</style>
