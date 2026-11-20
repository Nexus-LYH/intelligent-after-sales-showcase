import type { TransferInbox } from './ticket-transfer-api'
export type InboxOrigin = Readonly<Pick<TransferInbox,'transferRequestId'|'ticketId'|'roundId'|'sourceAssignmentId'|'targetDepartmentId'|'targetEngineerUserId'|'rowVersion'>>
const origins = new Map<string,InboxOrigin>()
const slot=(actor:string,id:string)=>JSON.stringify([actor,id])
export const inboxOrigin=(actor:string,id:string)=>origins.get(slot(actor,id)) || null
export function rememberInboxOrigin(actor:string,r:TransferInbox) {
  const origin=Object.freeze({transferRequestId:r.transferRequestId,ticketId:r.ticketId,roundId:r.roundId,sourceAssignmentId:r.sourceAssignmentId,targetDepartmentId:r.targetDepartmentId,targetEngineerUserId:r.targetEngineerUserId,rowVersion:r.rowVersion})
  origins.set(slot(actor,r.transferRequestId),origin);return origin
}
