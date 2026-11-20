import type {Identity} from '../../api'
import type {TicketDetail} from '../../ticket-api'
import type {ApprovalOwner} from './approval-client'
export function approvalEmployee(identity:Identity|null){return !!identity&&identity.realm==='TENANT'&&identity.identityType==='EMPLOYEE'&&!!identity.tenant&&identity.accessState==='NORMAL'}
export function approvalOwner(identity:Identity|null,generation:number):ApprovalOwner|null{return approvalEmployee(identity)?{tenantId:identity!.tenant!.id,actorId:identity!.userId,generation,namespace:`${identity!.realm}:EMPLOYEE`}:null}
export function currentPartsAssignment(ticket:TicketDetail|null,identity:Identity|null){return approvalEmployee(identity)&&!!ticket&&!ticket.isHistorical&&!ticket.readOnly&&ticket.status==='IN_PROGRESS'&&ticket.qualificationStatus==='VALID'&&ticket.assignment?.state==='IN_PROGRESS'&&ticket.assignment.engineerUserId===identity!.userId?ticket.assignment:null}
