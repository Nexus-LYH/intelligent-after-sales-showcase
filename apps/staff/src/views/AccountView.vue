<script setup lang="ts">
import { useSession } from '../session'
import { identityLabel } from '../display'
const auth = useSession()
const time = (v?: string) => v ? new Date(v).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
</script>
<template><section class="page-heading"><p class="eyebrow">MY ACCOUNT</p><h1>本人账户</h1><p>此处展示可信身份摘要，归属和权限不可自行修改。</p></section><section class="panel"><div class="section-heading"><h2>{{ auth.identity?.displayName }}</h2><el-tag>会话有效</el-tag></div><dl class="details"><dt>账号</dt><dd>{{ auth.identity?.account }}</dd><dt>身份类型</dt><dd>{{ identityLabel(auth.identity?.identityType) }}</dd><dt>所属范围</dt><dd>{{ auth.identity?.realm === 'PLATFORM' ? '独立平台账号' : auth.identity?.tenant?.name }}</dd><dt>部门</dt><dd>{{ auth.identity?.department?.name ?? '未提供' }}</dd><dt>会话截止（上海时间）</dt><dd>{{ time(auth.identity?.expiresAt) }}</dd></dl><div class="actions"><el-button type="primary" @click="$router.push('/account/password')">修改本人密码</el-button><el-button @click="auth.synchronize()">重新核验身份</el-button></div></section><p class="muted">关闭浏览器不等于退出。修改密码后所有旧会话失效，需重新登录。</p></template>
