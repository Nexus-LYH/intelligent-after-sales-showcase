<script setup lang="ts">
import { computed, reactive, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSession } from '../session'
import { loginValidation, type LoginInput } from '../api'
import { customerPortal } from '../portal'
const route = useRoute(), auth = useSession(), validation = ref('')
const platform = computed(() => route.path === '/platform/login')
const form = reactive({ tenantCode: '', account: '', password: '' })
const now = ref(Date.now()), timer = setInterval(() => { now.value = Date.now() }, 1000)
async function submit() {
  const raw: LoginInput = { realm: platform.value ? 'PLATFORM' : 'TENANT', account: form.account, password: form.password, ...(platform.value ? {} : { tenantCode: form.tenantCode }) }
  validation.value = loginValidation(raw)
  if (validation.value) return
  form.password = '' // Clear this submission before awaiting; an old finally must not erase new input.
  await auth.login({ ...raw, account: raw.account.toLowerCase(), ...(platform.value ? {} : { tenantCode: raw.tenantCode!.toUpperCase() }) })
  raw.password = ''
}
onUnmounted(() => { form.password = ''; clearInterval(timer) })
</script>
<template>
  <section class="access-screen" :class="{ 'access-screen--customer': customerPortal }" aria-labelledby="access-title">
    <aside class="access-cover">
      <div class="access-cover-content">
        <span class="access-mark" aria-hidden="true">启</span>
        <h2>{{ customerPortal ? '设备报修与进度查询' : platform ? '平台账号与租户管理' : '售后服务工作台' }}</h2>
        <p v-if="!platform">{{ customerPortal ? '提交设备报修，随时查看维修进度。' : '受理报修、分派工单，跟进维修进度。' }}</p>
      </div>
      <div class="access-orbit" aria-hidden="true"><span></span></div>
    </aside>

    <section class="access-panel">
      <div class="access-portals" role="navigation" aria-label="登录入口">
        <span class="access-indicator" aria-hidden="true"></span>
        <a href="/staff/login" :aria-current="!customerPortal ? 'page' : undefined">员工入口</a>
        <a href="/customer/login" :aria-current="customerPortal ? 'page' : undefined">客户入口</a>
      </div>

      <div class="access-heading">
        <h1 id="access-title">{{ platform ? '平台管理员登录' : customerPortal ? '客户登录' : '员工登录' }}</h1>
        <p v-if="platform">使用平台账号登录</p>
      </div>

      <form class="access-form" @submit.prevent="submit">
        <div v-if="!platform" class="access-field">
          <label for="tenantCode">租户编码</label>
          <el-input id="tenantCode" v-model="form.tenantCode" autocomplete="organization" placeholder="服务商提供的租户编码" />
        </div>
        <div class="access-field">
          <label for="account">账号</label>
          <el-input id="account" v-model="form.account" autocomplete="username" placeholder="输入账号" />
        </div>
        <div class="access-field">
          <label for="password">密码</label>
          <el-input id="password" v-model="form.password" type="password" show-password autocomplete="current-password" placeholder="输入密码" />
        </div>
        <p v-if="validation" class="access-message access-error" role="alert">{{ validation }}</p>
        <p v-if="now < auth.retryUntil" class="access-message" role="status">请等待 {{ Math.ceil((auth.retryUntil-now)/1000) }} 秒再试。</p>
        <el-button class="access-submit" type="primary" native-type="submit" :loading="auth.busy" :disabled="now < auth.retryUntil">登录</el-button>
      </form>

      <div class="access-help">
        <p>忘记密码？请联系管理员。</p>
        <RouterLink v-if="!customerPortal" :to="platform ? '/login' : '/platform/login'">
          {{ platform ? '返回员工登录' : '平台管理员入口' }}
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<style scoped>
.access-screen {
  --access-accent: #94502f;
  --access-accent-dark: #71381f;
  --access-panel: #fffaf4;
  --access-deep: #302223;
  --access-mid: #673f2f;
  --access-glow: #c68258;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(0, 1fr);
  max-width: 1000px;
  min-height: 590px;
  margin: clamp(24px, 5vh, 64px) auto;
  background: var(--access-panel);
  border: 1px solid #e8dbcf;
  border-radius: 26px;
  overflow: hidden;
  box-shadow: 0 24px 65px #5133221a;
  color: #2c2525;
}
.access-screen--customer {
  --access-accent: #285c54;
  --access-accent-dark: #16433d;
  --access-panel: #fafbf4;
  --access-deep: #192d2b;
  --access-mid: #315550;
  --access-glow: #ae8060;
}
.access-cover {
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: flex-start;
  padding: clamp(28px, 4.6vw, 58px);
  min-width: 0;
  overflow: hidden;
  color: #fff3e4;
  background: radial-gradient(ellipse at 91% 85%, var(--access-glow), transparent 54%),
              linear-gradient(145deg, var(--access-deep) 8%, var(--access-mid) 90%);
  view-transition-name: access-cover;
}
.access-cover-content { z-index: 1; }
.access-mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: clamp(24px, 5vh, 57px);
  border: 1px solid #ffe4c97a;
  border-radius: 16px;
  background: #ffebd024;
  font-size: 22px;
  font-weight: 700;
}
.access-cover h2 {
  max-width: 9em;
  margin: 0 0 14px;
  font-size: clamp(28px, 3.5vw, 47px);
  font-weight: 750;
  line-height: 1.24;
  letter-spacing: -.045em;
}
.access-cover p { margin: 0; color: #f6dfca; font-size: 14px; }
.access-orbit {
  position: absolute;
  right: -22px;
  bottom: -130px;
  width: min(87%, 460px);
  aspect-ratio: 1;
  border: 1px solid #ffe5c065;
  border-radius: 50%;
  transform: rotate(-17deg) scaleX(1.12);
  box-shadow: 0 0 0 20px #ffe5c00b, 0 0 0 50px #ffe5c012, 0 0 0 84px #ffe5c009;
  pointer-events: none;
}
.access-orbit::after,
.access-orbit::before {
  content: '';
  position: absolute;
  inset: 13%;
  border: 1px solid #ffe5c059;
  border-radius: 50%;
}
.access-orbit::after {
  inset: 25%;
  border-color: #fff4e36e;
  background: radial-gradient(circle at 45% 10%, #fff4e34d, transparent 80%);
}
.access-orbit span {
  position: absolute;
  z-index: 1;
  top: 14%;
  left: 14%;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ffe9c7;
  box-shadow: 0 0 20px #fff5cf, 0 0 0 5px #ffe9c72b;
}
.access-panel {
  min-width: 0;
  padding: clamp(28px, 4.4vw, 56px);
  align-self: center;
  view-transition-name: access-panel;
}
.access-portals {
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  margin-bottom: 28px;
  border: 1px solid #e7dccf;
  border-radius: 14px;
  background: #eee8df;
}
.access-portals a {
  z-index: 1;
  padding: 8px 4px;
  border-radius: 10px;
  text-align: center;
  color: #66544a;
  font-size: 13px;
  font-weight: 650;
}
.access-portals a:hover, .access-portals a[aria-current] { color: var(--access-accent-dark); }
.access-portals a:focus-visible, .access-help a:focus-visible { outline: 2px solid var(--access-accent); outline-offset: 2px; }
.access-indicator {
  position: absolute;
  inset: 4px auto 4px 4px;
  width: calc(50% - 6px);
  border-radius: 10px;
  background: #fffdfa;
  box-shadow: 0 2px 7px #402c1e16;
  view-transition-name: access-indicator;
}
.access-screen--customer .access-indicator { left: calc(50% + 2px); }
.access-heading { margin-bottom: 24px; }
.access-heading h1 { margin: 0; font-size: clamp(27px, 3vw, 35px); line-height: 1.2; letter-spacing: -.035em; }
.access-heading p { margin: 9px 0 0; color: #68584f; font-size: 14px; }
.access-form { display: grid; gap: 15px; }
.access-field label { margin: 0 0 7px; color: #413733; font-size: 13px; }
.access-field :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 1px 13px;
  border-radius: 12px;
  background: #ffffffa8;
  box-shadow: 0 0 0 1px #e0d5ca inset;
  transition: box-shadow .18s ease, background-color .18s ease;
}
.access-field :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px var(--access-glow) inset; }
.access-field :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--access-accent) inset, 0 0 0 3px color-mix(in srgb, var(--access-accent) 18%, transparent);
}
.access-field :deep(.el-input__inner::placeholder) { color: #796c64; }
.access-submit {
  --el-button-bg-color: var(--access-accent);
  --el-button-border-color: var(--access-accent);
  --el-button-hover-bg-color: var(--access-accent-dark);
  --el-button-hover-border-color: var(--access-accent-dark);
  --el-button-active-bg-color: var(--access-accent-dark);
  --el-button-active-border-color: var(--access-accent-dark);
  min-height: 46px;
  margin-top: 4px;
  border-radius: 12px;
  font-weight: 700;
  transition: transform .18s ease, background-color .18s ease;
}
.access-submit:active:not(:disabled) { transform: scale(.98); }
.access-help { margin-top: 19px; display: flex; flex-wrap: wrap; gap: 6px 16px; align-items: baseline; justify-content: space-between; font-size: 12px; }
.access-help p { margin: 0; color: #6e5b50; }
.access-help a { color: var(--access-accent-dark); font-weight: 650; }
.access-message { margin: 0; color: #675449; font-size: 13px; }
.access-error { color: #98382d; }

@media (max-width: 730px) {
  .access-screen { grid-template-columns: minmax(0, 1fr); max-width: 500px; min-height: 0; border-radius: 20px; }
  .access-cover { padding: 24px; }
  .access-mark { width: 36px; height: 36px; margin-bottom: 12px; border-radius: 11px; font-size: 17px; }
  .access-cover h2 { max-width: none; font-size: 23px; margin-bottom: 5px; }
  .access-cover p { font-size: 13px; }
  .access-orbit { bottom: -130px; right: -50px; width: 244px; }
  .access-panel { padding: 24px; }
  .access-portals { margin-bottom: 22px; }
}
@media (prefers-reduced-motion: reduce) {
  .access-screen :deep(*) { transition: none; }
}
</style>

<style>
@view-transition { navigation: auto; }
::view-transition-group(access-indicator) { animation-duration: 340ms; animation-timing-function: cubic-bezier(.2,.8,.2,1); }
::view-transition-group(access-cover),
::view-transition-group(access-panel) { animation-duration: 290ms; }
@media (prefers-reduced-motion: reduce) {
  ::view-transition-group(*) { animation-duration: 0.01ms; }
}
</style>
