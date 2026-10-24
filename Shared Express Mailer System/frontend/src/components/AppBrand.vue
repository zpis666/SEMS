<script setup>
defineProps({
  compact: { type: Boolean, default: false },
  inverse: { type: Boolean, default: false },
  small: { type: Boolean, default: false },
})
</script>

<template>
  <div class="app-brand" :class="{ 'is-compact': compact, 'is-inverse': inverse, 'is-small': small }">
    <svg class="app-brand__mark" viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <rect class="app-brand__tile" x="1" y="1" width="46" height="46" rx="14" />
      <path d="M15 19.5 24 14l9 5.5v11L24 36l-9-5.5v-11Z" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round" />
      <path d="m15.5 19.5 8.5 5 8.5-5M24 25v10.5" stroke="currentColor" stroke-width="2.2" stroke-linejoin="round" />
      <path d="m30.5 11 4.8 2.8v5M34.3 18.8l-2.3-1.7m-18.5 18-4.8-2.8v-5M8.7 27.3l2.3 1.7" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
    <span v-if="!compact" class="app-brand__copy">
      <strong class="app-brand__name">共享快递袋<span class="app-brand__en">SEMS</span></strong>
      <span class="app-brand__slogan">让包装，循环新生</span>
    </span>
  </div>
</template>

<style scoped>
.app-brand { display: inline-flex; align-items: center; gap: var(--sems-space-3); color: var(--sems-color-primary); }
.app-brand__mark { width: 46px; height: 46px; flex: none; }
.app-brand__tile { fill: var(--sems-color-primary-bg); stroke: color-mix(in srgb, var(--sems-color-primary) 12%, transparent); }
.app-brand__copy { display: flex; flex-direction: column; gap: var(--sems-space-1); white-space: nowrap; }
.app-brand__name { display: flex; align-items: baseline; gap: var(--sems-space-2); color: var(--sems-text-primary); font-size: var(--sems-font-subtitle); font-weight: var(--sems-font-weight-strong); letter-spacing: 0.04em; }
.app-brand__en { font-size: var(--sems-font-aux); color: var(--sems-color-primary); font-weight: var(--sems-font-weight-bold); letter-spacing: 0.12em; }
.app-brand__slogan { color: var(--sems-text-secondary); font-size: var(--sems-font-aux); letter-spacing: 0.12em; }
.app-brand.is-small .app-brand__mark { width: 36px; height: 36px; }
.app-brand.is-small .app-brand__name { font-size: var(--sems-font-body); }
.app-brand.is-small .app-brand__en, .app-brand.is-small .app-brand__slogan { display: none; }
.app-brand.is-inverse { color: var(--sems-text-inverse); }
.app-brand.is-inverse .app-brand__tile { fill: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent); stroke: color-mix(in srgb, var(--sems-text-inverse) 20%, transparent); }
.app-brand.is-inverse .app-brand__name, .app-brand.is-inverse .app-brand__en { color: var(--sems-text-inverse); }
.app-brand.is-inverse .app-brand__slogan { color: var(--sems-text-on-brand-soft); }
</style>
