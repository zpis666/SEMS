<template>
  <figure class="cycle-visual" aria-labelledby="cycle-title">
    <svg viewBox="0 0 520 360" role="img" aria-describedby="cycle-desc">
      <title id="cycle-title">共享快递袋循环旅程</title>
      <desc id="cycle-desc">一只快递袋沿着取用、寄件、归还和消毒四个站点持续循环。</desc>
      <defs>
        <path id="cycle-route" d="M128 104C196 35 346 42 397 118C448 194 390 288 286 302C181 316 82 257 91 174C94 146 106 124 128 104Z" />
      </defs>

      <ellipse class="cycle-visual__ground" cx="258" cy="303" rx="158" ry="15" />
      <use href="#cycle-route" class="cycle-visual__orbit" />
      <use href="#cycle-route" class="cycle-visual__route" />

      <g class="cycle-visual__station" transform="translate(122 91)">
        <circle r="30" />
        <path d="M-11-6h22v18h-22zM-6-6v-6H6v6M-5 0h10M0-4v8" />
        <text y="49">取袋</text>
      </g>
      <g class="cycle-visual__station" transform="translate(398 126)">
        <circle r="30" />
        <path d="M-15 8 0-7 15 8M0-7v24M-9 14H9" />
        <text y="49">寄件</text>
      </g>
      <g class="cycle-visual__station" transform="translate(335 286)">
        <circle r="30" />
        <path d="M-12-10h24v25h-24zM-8-3H8M-8 4H8M-5-10v-5H5v5" />
        <text y="49">归还</text>
      </g>
      <g class="cycle-visual__station" transform="translate(91 224)">
        <circle r="30" />
        <path d="M0-16C-2-9-11-2-11 6a11 11 0 0 0 22 0C11-2 2-9 0-16ZM-5 7c2 4 7 4 10 0" />
        <text y="49">消毒</text>
      </g>

      <g class="cycle-visual__bag" transform="translate(259 165)">
        <path class="cycle-visual__bag-back" d="M-51-45-7-62 51-40 7-21Z" />
        <path class="cycle-visual__bag-front" d="M-51-45-7-21v65l-44-24Z" />
        <path class="cycle-visual__bag-side" d="M-7-21 51-40v61L-7 44Z" />
        <path class="cycle-visual__bag-line" d="M-51-45-7-21 51-40M-7-21v65M-29-53l44 20v22l-13 7" />
        <path class="cycle-visual__bag-arrow" d="M8-3c12-7 26-1 27 11m0 0-1-9m1 9-9-2M28 22C16 29 2 23 1 11m0 0 1 9m-1-9 9 2" />
      </g>

      <g class="cycle-visual__leaf" transform="translate(363 55)">
        <path d="M0 18C-2 4 7-5 22-4c1 15-7 24-22 22ZM1 17 15 1" />
      </g>
      <g class="cycle-visual__sparkles">
        <path d="M183 62v12m-6-6h12M425 224v12m-6-6h12M177 294v10m-5-5h10" />
      </g>
    </svg>
    <figcaption>让每一次出发，都成为下一次循环的起点</figcaption>
  </figure>
</template>

<style scoped>
.cycle-visual {
  width: min(100%, 520px);
  margin: var(--sems-space-5) auto 0;
  color: var(--sems-text-inverse);
}
.cycle-visual svg { display: block; width: 100%; overflow: visible; }
.cycle-visual__ground { fill: color-mix(in srgb, var(--sems-bg-sidebar) 32%, transparent); }
.cycle-visual__orbit { fill: none; stroke: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent); stroke-width: 22; }
.cycle-visual__route { fill: none; stroke: color-mix(in srgb, var(--sems-text-on-brand-soft) 62%, transparent); stroke-width: 2; stroke-dasharray: 5 9; stroke-linecap: round; animation: cycle-flow 16s linear infinite; }
.cycle-visual__station circle { fill: color-mix(in srgb, var(--sems-text-inverse) 10%, transparent); stroke: color-mix(in srgb, var(--sems-text-inverse) 30%, transparent); }
.cycle-visual__station path { fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.cycle-visual__station text { fill: var(--sems-text-on-brand-soft); font-size: var(--sems-font-aux); text-anchor: middle; letter-spacing: 0.15em; }
.cycle-visual__bag-back { fill: var(--sems-color-primary); }
.cycle-visual__bag-front { fill: var(--sems-color-primary-deep); }
.cycle-visual__bag-side { fill: var(--sems-color-primary); }
.cycle-visual__bag-line { fill: none; stroke: color-mix(in srgb, var(--sems-text-inverse) 72%, transparent); stroke-width: 2; stroke-linejoin: round; }
.cycle-visual__bag-arrow { fill: none; stroke: var(--sems-text-inverse); stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
.cycle-visual__leaf, .cycle-visual__sparkles { fill: none; stroke: var(--sems-color-accent-soft); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.cycle-visual figcaption { margin-top: calc(var(--sems-space-3) * -1); color: var(--sems-text-on-brand-soft); font-size: var(--sems-font-size-sm); text-align: center; letter-spacing: 0.08em; }
@keyframes cycle-flow { to { stroke-dashoffset: -140; } }
@media (prefers-reduced-motion: reduce) { .cycle-visual__route { animation: none; } }
</style>
