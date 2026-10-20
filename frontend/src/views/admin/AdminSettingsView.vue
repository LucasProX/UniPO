<script setup lang="ts">
const planned = [
  { title: '功能开关', desc: '按学校/角色灰度开启新功能（推荐流、私信、上传等）。', state: '待接入' },
  { title: '公告与通知', desc: '站内公告、维护通知，支持定时发布。', state: '待接入' },
  { title: '限流阈值', desc: '只读展示当前规则表，后续支持按环境调整。', state: '只读占位' },
  { title: '内容安全词库', desc: '标题/正文/评论的敏感词与审核策略。', state: '待接入' },
  { title: '数据导出', desc: '用户、帖子、审计日志的 CSV 导出。', state: '待接入' }
];
</script>

<template>
  <div class="space-y-5">
    <div>
      <h2 class="text-xl font-semibold">系统设置</h2>
      <p class="mt-1 text-sm text-slate-500">这些是后台下一步要补的能力，先占位，避免入口散落在各处。</p>
    </div>

    <div class="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
      第一版 MVP 只做了「概览 / 内容审核 / 轮播 / 用户与权限 / 报表 / 监控」。下面这些还没有后端接口，点进去也不会有操作。
    </div>

    <div class="grid gap-4 md:grid-cols-2">
      <div v-for="item in planned" :key="item.title" class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">{{ item.title }}</h3>
          <span class="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500">{{ item.state }}</span>
        </div>
        <p class="mt-2 text-xs leading-relaxed text-slate-500">{{ item.desc }}</p>
      </div>
    </div>

    <section class="rounded-2xl border border-slate-200/70 bg-white p-5 text-sm text-slate-500 shadow-sm">
      <h3 class="text-sm font-semibold text-slate-900">环境</h3>
      <dl class="mt-3 grid gap-2 text-xs sm:grid-cols-2">
        <div class="flex justify-between border-b border-slate-100 py-1"><dt>API</dt><dd class="font-mono">/api/v1</dd></div>
        <div class="flex justify-between border-b border-slate-100 py-1"><dt>会话</dt><dd>httpOnly Cookie + CSRF</dd></div>
        <div class="flex justify-between border-b border-slate-100 py-1"><dt>指标</dt><dd class="font-mono">/actuator/prometheus</dd></div>
        <div class="flex justify-between border-b border-slate-100 py-1"><dt>审计</dt><dd>admin_audit_logs</dd></div>
      </dl>
    </section>
  </div>
</template>
