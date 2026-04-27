export default defineNuxtConfig({
  compatibilityDate: '2026-04-27',
  devtools: { enabled: false },
  modules: ['@nuxtjs/tailwindcss'],
  app: {
    head: {
      title: 'Cowork',
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        { name: 'description', content: '광주소프트웨어마이스터고등학교 학생들이 만드는 협업 관리 플랫폼' },
        { property: 'og:title', content: 'Cowork' },
        { property: 'og:description', content: '광주소프트웨어마이스터고등학교 학생들이 만드는 협업 관리 플랫폼' },
        { property: 'og:type', content: 'website' },
      ],
      link: [
        { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' },
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        {
          rel: 'preconnect',
          href: 'https://fonts.gstatic.com',
          crossorigin: '',
        },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=Inter:ital,opsz,wght@0,14..32,400;0,14..32,500;0,14..32,600;0,14..32,700;0,14..32,800;0,14..32,900&display=swap',
        },
      ],
    },
  },
  css: ['~/assets/css/main.css'],
})
