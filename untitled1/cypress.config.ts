import { defineConfig } from 'cypress'

export default defineConfig({
  e2e: {
    // 여기서 E2E 테스트를 구성하세요
    specPattern: "cypress/e2e/**/*.{cy,spec}.{js,ts}"
  },
})