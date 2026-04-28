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
</script>

<template>
  <a
    :href="`https://github.com/${member.githubId}`"
    target="_blank"
    rel="noopener noreferrer"
    class="flex items-center gap-3 px-4 py-3 bg-white rounded-2xl border border-gray-100 shadow-sm shrink-0 select-none hover:border-gray-200 hover:shadow-md transition-all duration-150"
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
      <div class="flex items-center gap-1.5 mt-0.5">
        <span class="text-xs text-gray-500">{{ member.position }}</span>
        <span class="w-0.5 h-0.5 rounded-full bg-gray-300 inline-block" />
        <span class="text-xs font-medium text-red-500">{{ member.cohort }}</span>
      </div>
    </div>
  </a>
</template>
