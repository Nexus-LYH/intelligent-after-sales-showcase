import type { AssetKind, AssetRecord } from './asset-api'
export interface Field { key: string; label: string; required?: boolean; type?: string; max?: number }
const common: Field[] = [{ key: 'code', label: '编码', required: true, max: 32 }, { key: 'name', label: '名称', required: true, max: 100 }]
export const assetFields: Record<AssetKind, Field[]> = {
  companies: [...common, { key: 'contactName', label: '联系人', max: 50 }, { key: 'phone', label: '联系电话', max: 30 }, { key: 'address', label: '服务地址', max: 200 }],
  models: [...common, { key: 'category', label: '产品类别', max: 50 }, { key: 'description', label: '描述', max: 1000, type: 'textarea' }],
  devices: [{ key: 'name', label: '设备名称', required: true, max: 100 }, { key: 'code', label: '设备编号', required: true, max: 32 }, { key: 'serialNumber', label: '序列号', required: true, max: 100 }, { key: 'location', label: '安装位置', max: 200 }, { key: 'purchaseDate', label: '购入日期', type: 'date' }, { key: 'warrantyStart', label: '保修开始日期', type: 'date' }, { key: 'warrantyEnd', label: '保修结束日期', type: 'date' }],
}
export function formOf(kind: AssetKind, value?: AssetRecord): Record<string, string> {
  return Object.fromEntries([...assetFields[kind].map(f => [f.key, String(value?.[f.key as keyof AssetRecord] ?? '')]), ['companyId', value?.companyId ?? ''], ['modelId', value?.modelId ?? '']])
}
export function validateAsset(kind: AssetKind, form: Record<string, string>, editing = false) {
  for (const f of assetFields[kind]) {
    if (f.required && !form[f.key]?.trim()) return `请填写${f.label}`
    if (f.max && Array.from(form[f.key] ?? '').length > f.max) return `${f.label}不能超过${f.max}个字符`
  }
  if (!editing && !/^[A-Za-z0-9_-]{2,32}$/.test(form.code ?? '')) return '编码须为2～32位字母、数字、下划线或短横线'
  if (kind === 'devices') {
    if (!editing && !form.companyId) return '请选择客户企业'
    if (!editing && !form.modelId) return '请选择产品型号'
    if (!!form.warrantyStart !== !!form.warrantyEnd) return '保修起止日期须同时填写'
    if (form.purchaseDate && form.warrantyEnd && form.purchaseDate > form.warrantyEnd) return '购入日期不能晚于保修结束日期'
    if (form.warrantyStart && form.warrantyEnd && form.warrantyStart > form.warrantyEnd) return '保修结束日期不能早于开始日期'
  }
  return ''
}
export function assetBody(kind: AssetKind, form: Record<string, string>, value?: AssetRecord) {
  const body: Record<string, unknown> = Object.fromEntries(assetFields[kind].filter(f => !value || !['code', 'serialNumber'].includes(f.key)).map(f => [f.key, f.type === 'date' ? form[f.key] || null : form[f.key]]))
  if (kind === 'devices' && !value) { body.modelId = form.modelId; body.companyId = form.companyId }
  if (!value) body.code = String(body.code).toUpperCase()
  if (value) body.expectedVersion = value.rowVersion
  return body
}
