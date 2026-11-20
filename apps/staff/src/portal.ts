// Both independent builds use the same authentication implementation and cookie origin.
export const customerPortal = import.meta.env.BASE_URL === '/customer/'
export const portalName = customerPortal ? '客户门户' : '员工管理端'
export const portalHome = customerPortal ? '/devices' : '/account'
export function acceptsIdentity(type: string) { return customerPortal ? type === 'CUSTOMER' : type === 'EMPLOYEE' || type === 'PLATFORM' }
