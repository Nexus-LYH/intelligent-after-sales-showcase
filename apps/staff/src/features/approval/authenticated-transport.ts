import type{useSession}from'../../session'
import{approvalTransport}from'./approval-integration'
/** Compatibility entry: a single current-session approval transport implementation. */
export const authenticatedApprovalTransport=(auth:ReturnType<typeof useSession>,active:()=>boolean)=>approvalTransport(auth,active)
