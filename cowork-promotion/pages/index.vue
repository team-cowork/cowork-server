<script lang="ts" setup>
import rawMembers from '~/data/members.json'

interface Member {
    githubId: string
    name: string
    position: string
    cohort: string
}

const members = rawMembers as Member[]

const techCategories = [
    {
        label: 'Language',
        items: [
            {name: 'Kotlin', color: '#7F52FF'},
            {name: 'TypeScript', color: '#3178C6'},
            {name: 'JavaScript', color: '#F7DF1E'},
            {name: 'Go', color: '#00ADD8'},
            {name: 'Dart', color: '#0175C2'},
        ],
    },
    {
        label: 'Frontend',
        items: [
            {name: 'React', color: '#61DAFB'},
            {name: 'Vue.js', color: '#4FC08D'},
            {name: 'Rsbuild', color: '#FF3E00'},
            {name: 'Flutter', color: '#02569B'},
            {name: 'Kotlin Multiplatform', color: '#7F52FF'},
        ],
    },
    {
        label: 'Backend',
        items: [
            {name: 'Spring Boot', color: '#6DB33F'},
            {name: 'Spring Cloud Gateway', color: '#6DB33F'},
            {name: 'Eureka', color: '#6DB33F'},
            {name: 'OpenFeign', color: '#6DB33F'},
            {name: 'Vert.x', color: '#2C3E50'},
            {name: 'Node.js', color: '#339933'},
            {name: 'NestJS', color: '#E0234E'},
            {name: 'Gin', color: '#00ADD8'},
            {name: 'Chi', color: '#00ADD8'},
            {name: 'LiveKit', color: '#FF5C93'},
        ],
    },
    {
        label: 'Database',
        items: [
            {name: 'MySQL', color: '#4479A1'},
            {name: 'PostgreSQL', color: '#336791'},
            {name: 'MongoDB', color: '#47A248'},
            {name: 'Redis', color: '#DC382D'},
            {name: 'Flyway', color: '#CC0200'},
        ],
    },
    {
        label: 'Messaging',
        items: [{name: 'Apache Kafka', color: '#231F20'}],
    },
    {
        label: 'Infrastructure',
        items: [
            {name: 'Docker', color: '#2496ED'},
            {name: 'Grafana', color: '#F46800'},
            {name: 'Prometheus', color: '#E6522C'},
            {name: 'Vault', color: '#0FC75E'},
        ],
    },
]

const repos = [
    {
        name: 'cowork-server',
        description: 'MSA 기반 통합 백엔드 API 서버',
        badge: 'Spring Boot',
        url: 'https://github.com/team-cowork/cowork-server',
    },
    {
        name: 'cowork-web-client',
        description: 'MFA 기반 웹 브라우저 환경의 협업 클라이언트',
        badge: 'Web',
        url: 'https://github.com/team-cowork/cowork-web-client',
    },
    {
        name: 'cowork-desktop-app-client',
        description: '데스크탑 환경의 협업 클라이언트',
        badge: 'Desktop',
        url: 'https://github.com/team-cowork/cowork-desktop-app-client',
    },
    {
        name: 'cowork-github-app',
        description: 'GitHub 저장소와의 협업 연동 앱',
        badge: 'GitHub App',
        url: 'https://github.com/team-cowork/cowork-github-app',
    },
    {
        name: 'cowork-mobile-app-client',
        description: '모바일 환경의 협업 클라이언트',
        badge: 'Mobile',
        url: 'https://github.com/team-cowork/cowork-mobile-app-client',
    },
]

function fillMarquee(list: Member[], min = 20): Member[] {
    const result: Member[] = []
    while (result.length < min) result.push(...list)
    return result
}

const marqueeItems = fillMarquee(members)
const row1 = [...marqueeItems, ...marqueeItems]
const row2 = [...marqueeItems, ...marqueeItems]
</script>

<template>
    <div class="min-h-screen bg-white font-sans text-gray-900 antialiased">
        <!-- Navigation -->
        <nav
            class="fixed top-0 left-0 right-0 z-50 bg-white/80 backdrop-blur-md border-b border-gray-100"
        >
            <div
                class="max-w-6xl mx-auto px-6 h-16 flex items-center justify-between"
            >
                <div class="flex items-center gap-2.5">
                    <CoworkLogo :size="28"/>
                    <span class="font-bold text-base tracking-tight">cowork</span>
                </div>
                <a
                    class="flex items-center gap-2 text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors duration-150"
                    href="https://github.com/team-cowork"
                    rel="noopener noreferrer"
                    target="_blank"
                >
                    <svg aria-hidden="true" fill="currentColor" height="18" viewBox="0 0 24 24" width="18">
                        <path
                            d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/>
                    </svg>
                    GitHub
                </a>
            </div>
        </nav>

        <!-- Hero -->
        <section class="pt-36 pb-28 px-6 text-center">
            <div class="max-w-3xl mx-auto">
                <div class="flex justify-center mb-10">
                    <CoworkLogo :size="160"/>
                </div>
                <h1
                    class="text-8xl font-black tracking-tighter mb-5 text-gray-900 leading-none"
                >
                    cowork
                </h1>
                <p class="text-lg text-gray-400 mb-2 font-medium">
                    광주소프트웨어마이스터고등학교
                </p>
                <p class="text-2xl font-semibold text-gray-700 mb-12">
                    학생들이 만드는 협업 관리 플랫폼
                </p>
                <div class="flex items-center justify-center gap-3 flex-wrap">
                    <a
                        class="inline-flex items-center gap-2.5 px-7 py-3.5 bg-gray-900 text-white rounded-full font-semibold text-sm hover:bg-gray-700 transition-colors duration-150 shadow-sm"
                        href="https://github.com/team-cowork"
                        rel="noopener noreferrer"
                        target="_blank"
                    >
                        <svg aria-hidden="true" fill="currentColor" height="18" viewBox="0 0 24 24" width="18">
                            <path
                                d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z"/>
                        </svg>
                        GitHub 조직 바로가기
                    </a>
                    <a
                        class="inline-flex items-center gap-2 px-7 py-3.5 bg-white text-gray-700 rounded-full font-semibold text-sm border border-gray-200 hover:border-gray-300 hover:bg-gray-50 transition-all duration-150"
                        href="#repos"
                    >
                        레포지토리 보기
                    </a>
                </div>
            </div>
        </section>

        <!-- Divider -->
        <div class="max-w-6xl mx-auto px-6">
            <div class="h-px bg-gradient-to-r from-transparent via-gray-200 to-transparent"/>
        </div>

        <!-- Repos Section -->
        <section id="repos" class="py-24 px-6 bg-gray-50/60">
            <div class="max-w-6xl mx-auto">
                <div class="text-center mb-14">
                    <h2 class="text-3xl font-bold mb-3 tracking-tight">레포지토리</h2>
                    <p class="text-gray-500">team-cowork 조직을 구성하는 프로젝트들</p>
                </div>

                <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-6 gap-5">
                    <a
                        v-for="(repo, index) in repos"
                        :key="repo.name"
                        :class="[
              'group bg-white rounded-2xl p-6 border border-gray-100 hover:border-red-200 hover:shadow-md transition-all duration-200 flex flex-col',
              'lg:col-span-2',
              index === 3 ? 'lg:col-start-2' : '',
            ]"
                        :href="repo.url"
                        rel="noopener noreferrer"
                        target="_blank"
                    >
                        <div class="flex items-start justify-between mb-5">
                            <div
                                class="w-10 h-10 rounded-xl bg-red-50 flex items-center justify-center shrink-0"
                            >
                                <svg
                                    aria-hidden="true"
                                    fill="#EF4444"
                                    height="18"
                                    viewBox="0 0 16 16"
                                    width="18"
                                >
                                    <path
                                        d="M2 2.5A2.5 2.5 0 0 1 4.5 0h8.75a.75.75 0 0 1 .75.75v12.5a.75.75 0 0 1-.75.75h-2.5a.75.75 0 0 1 0-1.5h1.75v-2h-8a1 1 0 0 0-.714 1.7.75.75 0 1 1-1.072 1.05A2.495 2.495 0 0 1 2 11.5Zm10.5-1h-8a1 1 0 0 0-1 1v6.708A2.486 2.486 0 0 1 4.5 9h8Z"/>
                                </svg>
                            </div>
                            <span
                                class="text-xs font-medium px-2.5 py-1 bg-gray-100 text-gray-500 rounded-full"
                            >
                {{ repo.badge }}
              </span>
                        </div>

                        <h3
                            class="font-mono font-semibold text-gray-900 text-sm mb-2 group-hover:text-red-500 transition-colors duration-150 leading-snug"
                        >
                            {{ repo.name }}
                        </h3>
                        <p class="text-sm text-gray-500 leading-relaxed">
                            {{ repo.description }}
                        </p>

                        <div class="mt-4 rounded-xl overflow-hidden border border-gray-50">
                            <img
                                :alt="`${repo.name} language stats`"
                                :src="`https://github-repository-language-graph-wi.vercel.app/api?username=team-cowork&repo=${repo.name}&theme=white&langs_count=100`"
                                class="w-full h-auto block"
                                loading="lazy"
                            />
                        </div>

                        <div
                            class="mt-5 pt-4 border-t border-gray-50 flex items-center gap-1.5 text-xs text-gray-300 group-hover:text-red-300 transition-colors duration-150"
                        >
                            <svg
                                aria-hidden="true"
                                fill="none"
                                height="11"
                                stroke="currentColor"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                                stroke-width="2.5"
                                viewBox="0 0 24 24"
                                width="11"
                            >
                                <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
                                <polyline points="15 3 21 3 21 9"/>
                                <line x1="10" x2="21" y1="14" y2="3"/>
                            </svg>
                            <span class="truncate">github.com/team-cowork/{{ repo.name }}</span>
                        </div>
                    </a>
                </div>
            </div>
        </section>

        <!-- Divider -->
        <div class="max-w-6xl mx-auto px-6">
            <div class="h-px bg-gradient-to-r from-transparent via-gray-200 to-transparent"/>
        </div>

        <!-- Tech Stack Section -->
        <section class="py-24 px-6">
            <div class="max-w-4xl mx-auto">
                <div class="text-center mb-14">
                    <h2 class="text-3xl font-bold mb-3 tracking-tight">기술 스택</h2>
                    <p class="text-gray-500">cowork을 만드는 기술들</p>
                </div>

                <div class="space-y-5">
                    <div
                        v-for="cat in techCategories"
                        :key="cat.label"
                        class="flex items-start gap-6"
                    >
            <span
                class="text-xs font-semibold text-gray-400 uppercase tracking-wider w-24 shrink-0 pt-2"
            >
              {{ cat.label }}
            </span>
                        <div class="flex flex-wrap gap-2">
              <span
                  v-for="tech in cat.items"
                  :key="tech.name"
                  class="inline-flex items-center gap-2 px-3.5 py-1.5 bg-gray-50 border border-gray-100 rounded-full text-sm text-gray-700 font-medium"
              >
                <span
                    :style="{ backgroundColor: tech.color }"
                    class="w-2 h-2 rounded-full shrink-0"
                />
                {{ tech.name }}
              </span>
                        </div>
                    </div>
                </div>
            </div>
        </section>

        <!-- Divider -->
        <div class="max-w-6xl mx-auto px-6">
            <div class="h-px bg-gradient-to-r from-transparent via-gray-200 to-transparent"/>
        </div>

        <!-- Team Section -->
        <section class="py-24">
            <div class="max-w-6xl mx-auto px-6 text-center mb-14">
                <h2 class="text-3xl font-bold mb-3 tracking-tight">팀원</h2>
                <p class="text-gray-500">cowork을 만드는 사람들</p>
            </div>

            <div class="marquee-track space-y-4">
                <!-- Row 1: left → -->
                <div class="overflow-hidden">
                    <div class="flex gap-3 marquee-left min-w-max px-3">
                        <MemberCard
                            v-for="(m, i) in row1"
                            :key="`r1-${i}`"
                            :member="m"
                        />
                    </div>
                </div>

                <!-- Row 2: right ← -->
                <div class="overflow-hidden">
                    <div class="flex gap-3 marquee-right min-w-max px-3">
                        <MemberCard
                            v-for="(m, i) in row2"
                            :key="`r2-${i}`"
                            :member="m"
                        />
                    </div>
                </div>
            </div>
        </section>

        <!-- Footer -->
        <footer class="border-t border-gray-100 py-10 px-6">
            <div
                class="max-w-6xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-4"
            >
                <div class="flex items-center gap-2.5">
                    <CoworkLogo :size="22"/>
                    <span class="font-bold text-sm text-gray-900">cowork</span>
                </div>
                <p class="text-sm text-gray-400">
                    © 2024 team-cowork · MIT License
                </p>
                <a
                    class="text-sm text-gray-400 hover:text-gray-600 transition-colors duration-150"
                    href="https://github.com/team-cowork"
                    rel="noopener noreferrer"
                    target="_blank"
                >
                    github.com/team-cowork
                </a>
            </div>
        </footer>
    </div>
</template>
