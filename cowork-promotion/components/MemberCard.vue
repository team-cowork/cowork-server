<script setup lang="ts">
const props = defineProps<{
  member: {
    githubId: string
    name: string
    position: string
    cohort: string
  }
}>()

const avatarUrl = computed(
  () => `https://github.com/${props.member.githubId}.png?size=96`,
)

const positionColorMap: Record<string, string> = {
  'Server': '#8B5CF6',
  'Web Client': '#EAB308',
  'Mobile App Client': '#F43F5E',
  'Desktop App Client': '#0EA5E9',
  'Cloud': '#F97316',
  'Design': '#14B8A6',
}

const positionColor = computed(() => {
  for (const [key, color] of Object.entries(positionColorMap)) {
    if (props.member.position.includes(key)) return color
  }
  return '#9CA3AF'
})
</script>

<template>
  <a
    :href="`https://github.com/${member.githubId}`"
    target="_blank"
    rel="noopener noreferrer"
    class="flex items-center gap-3 px-4 py-3 bg-white rounded-2xl border border-gray-100 shadow-sm shrink-0 select-none"
    style="min-width: 200px"
  >
    <img
      :src="avatarUrl"
      :alt="member.name"
      class="w-11 h-11 rounded-full object-cover bg-gray-100 ring-2 ring-gray-100"
      loading="lazy"
    />
    <div class="min-w-0">
      <p class="font-semibold text-gray-900 text-sm leading-tight truncate">
        {{ member.name }}
      </p>
      <div class="flex items-center gap-1.5 mt-1">
        <span
          class="w-1.5 h-1.5 rounded-full shrink-0"
          :style="{ backgroundColor: positionColor }"
        />
        <span class="text-xs text-gray-500 truncate">{{ member.position }}</span>
        <span class="text-xs px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-400 font-medium shrink-0">
          {{ member.cohort }}
        </span>
      </div>
    </div>
  </a>
</template>