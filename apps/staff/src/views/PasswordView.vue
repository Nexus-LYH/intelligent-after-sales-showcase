<script setup lang="ts">
import { computed, reactive, ref, onUnmounted } from 'vue'
import { useSession } from '../session'
import { onBeforeRouteLeave } from 'vue-router'
import { passwordValidation } from '../api'
const auth = useSession(), form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' }), validation = ref('')
const forced = computed(() => auth.phase === 'PASSWORD_CHANGE_REQUIRED')
const wipe = () => { form.currentPassword = ''; form.newPassword = ''; form.confirmPassword = '' }
async function submit() { validation.value = passwordValidation(form.currentPassword, form.newPassword, form.confirmPassword); if (validation.value) return; const input = { ...form }; wipe(); try { await auth.changePassword(input) } finally { input.currentPassword=''; input.newPassword=''; input.confirmPassword='' } }
onBeforeRouteLeave(() => !auth.busy && (!(form.currentPassword || form.newPassword || form.confirmPassword) || confirm('离开会清除未提交的密码输入，确认离开？')))
onUnmounted(wipe)
</script>
<template><section class="page-heading"><p class="eyebrow">ACCOUNT SECURITY</p><h1>{{ forced ? '首次登录，请修改密码' : '修改本人密码' }}</h1><p>{{ forced ? '当前仅允许改密和退出，完成前不能访问业务页面。' : '修改成功后全部旧会话失效，请使用新密码重新登录。' }}</p></section><section class="panel password-panel"><p v-if="forced">当前账号：{{ auth.identity?.account }}</p><form @submit.prevent="submit"><label for="currentPassword">当前密码</label><el-input id="currentPassword" v-model="form.currentPassword" type="password" show-password autocomplete="current-password" /><label for="newPassword">新密码</label><el-input id="newPassword" v-model="form.newPassword" type="password" show-password autocomplete="new-password" /><p class="muted small">12～128个字符，与当前密码不同；允许粘贴，空白按原样处理。</p><label for="confirmPassword">确认新密码</label><el-input id="confirmPassword" v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" /><p v-if="validation" class="field-error" role="alert">{{ validation }}</p><div class="actions"><el-button type="primary" native-type="submit" :loading="auth.busy">确认改密</el-button><el-button v-if="!forced" @click="$router.push('/account')">返回本人账户</el-button></div></form></section></template>

