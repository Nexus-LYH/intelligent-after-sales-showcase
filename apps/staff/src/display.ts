export function identityLabel(value?: string) { return value === 'EMPLOYEE' ? '租户员工' : value === 'PLATFORM' ? '平台账号' : value === 'CUSTOMER' ? '客户账号' : '未提供' }
export function bindingLabel(value: string | number) { return value === 'NOT_APPLICABLE' ? '无需企业绑定' : value === 'PENDING' ? '待绑定' : value === 'BOUND' ? '已绑定' : '未提供' }
export function shanghaiTime(value: string) { return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }
