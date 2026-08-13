import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './locales'
import { router } from './router'
import './styles/tokens.css'
import './styles/main.css'

createApp(App).use(i18n).use(router).mount('#app')
