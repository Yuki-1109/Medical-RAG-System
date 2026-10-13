<template>
  <main class="auth-page">
    <aside class="auth-brand">
      <div class="brand-mark" aria-hidden="true">M</div>
      <p class="eyebrow">医疗文献与医学图片知识库</p>
      <h1>医疗 RAG<br />研究资料工作台</h1>
      <p>在自己的电脑上管理研究资料，逐步建立可溯源的知识库。</p>
      <span class="local-label">本地部署 · 私人资料独立保存</span>
    </aside>
    <section class="auth-panel"><slot /></section>
  </main>
</template>
