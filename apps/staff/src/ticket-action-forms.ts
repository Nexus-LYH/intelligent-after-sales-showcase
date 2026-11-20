import type { TicketDetail } from './ticket-api'
import { initialPriority, validText, workHoursValid } from './ticket-flow'
import type { ActionBody } from './ticket-action-api'
export const actionLabels: Record<string, string> = { 'reopen-request':'申请同故障重开','reopen-approve':'批准同故障重开','reopen-reject':'拒绝重开申请','round-response':'客户可见人工接手回复', 'transfer-request':'发起转派','transfer-withdraw':'撤回转派','transfer-accept':'接受转派','transfer-reject':'拒绝转派', 'cancel-direct':'直接取消报修','cancel-request':'申请取消','cancel-approve':'批准取消','cancel-reject':'拒绝取消申请', 'supplement-reply':'回复当前补充请求', 'supplement-request':'请求补充资料', 'accept':'受理','reject':'驳回','assign':'直接分派','assignment-accept':'本人接单','release-invalid-assignment':'释放失效候选','records':'添加处理记录','solve':'提交解决方案','customer-confirm':'确认已解决','customer-return':'反馈未解决','close':'客服收尾关闭' }
export const actionPermissions: Record<string, string> = { 'reopen-approve':'ticket.reopen.review','reopen-reject':'ticket.reopen.review','round-response':'ticket.respond', 'transfer-request':'ticket.transfer.request','transfer-withdraw':'ticket.transfer.request', 'cancel-approve':'ticket.cancel.review','cancel-reject':'ticket.cancel.review', 'supplement-request':'ticket.supplement.request','accept':'ticket.accept','reject':'ticket.reject','assign':'ticket.assign','assignment-accept':'ticket.assignment.accept','release-invalid-assignment':'ticket.assign','records':'ticket.record','solve':'ticket.solution.submit','close':'ticket.close' }
export interface ActionForm { sourceClosureOperationId:string; sameFault:boolean; specifyTarget:boolean; categoryCode: string; priority: string; acceptanceMessage: string; priorityChangeReason: string; reason: string; departmentId: string; engineerUserId: string; note: string; body: string; workHours: string; visibility: string; cause: string; resolutionMethod: string; customerMessage: string; completedAt: string; evidenceFileIds: string[]; hoursConfirmed: boolean; decision: 'CONFIRM' | 'RETURN'; closeMessage: string; acknowledged: boolean }
export function newActionForm(ticket: TicketDetail): ActionForm { return { sourceClosureOperationId:ticket.currentReopen?.sourceClosureOperationId||ticket.closure?.operationId||'',sameFault:false,specifyTarget:false,categoryCode:'',priority:ticket.initialPriority || initialPriority[ticket.impact] || '',acceptanceMessage:'',priorityChangeReason:'',reason:'',departmentId:'',engineerUserId:'',note:'',body:'',workHours:'0',visibility:'INTERNAL',cause:'',resolutionMethod:'',customerMessage:'',completedAt:'',evidenceFileIds:[],hoursConfirmed:false,decision:'CONFIRM',closeMessage:'',acknowledged:false } }
export function actionPath(ticket: TicketDetail, action: string) {
  const root = `/ticket/tickets/${encodeURIComponent(ticket.id)}`
  if(action==='reopen-request')return `${root}/reopen-requests`
  if(action==='reopen-approve'||action==='reopen-reject')return `${root}/reopen-requests/${encodeURIComponent(ticket.currentReopen?.reopenRequestId||'')}/decision`
  if(action==='round-response')return `${root}/rounds/${encodeURIComponent(ticket.roundId||'')}/responses`
  if (action === 'transfer-request') return `${root}/transfer-requests`
  if (action === 'transfer-withdraw') return `${root}/transfer-requests/${encodeURIComponent(ticket.currentTransfer?.transferRequestId || '')}/withdraw`
  if (action === 'cancel-direct') return `${root}/cancel`
  if (action === 'cancel-request') return `${root}/cancellation-requests`
  if (action === 'cancel-approve' || action === 'cancel-reject') return `${root}/cancellation-requests/${encodeURIComponent(ticket.currentCancellation?.cancelRequestId || '')}/decision`
  if (action === 'supplement-reply') return `${root}/supplement-requests/${encodeURIComponent(ticket.currentSupplement?.requestId || '')}/reply`
  if (action === 'supplement-request') return `${root}/supplement-requests`
  const suffix: Record<string,string> = { 'accept':'accept','reject':'reject','assign':'assign','assignment-accept':`assignments/${encodeURIComponent(ticket.assignment?.assignmentId || '')}/accept`,'release-invalid-assignment':'release-invalid-assignment','records':'records','solve':'solutions','customer-confirm':"solutions/" + encodeURIComponent(ticket.currentSolution?.solutionId || '') + '/decision','customer-return':"solutions/" + encodeURIComponent(ticket.currentSolution?.solutionId || '') + '/decision','close':'close' }
  return `${root}/${suffix[action] || ''}`
}
export function actionBody(ticket: TicketDetail, action: string, form: ActionForm): ActionBody {
  const base = { expectedVersion:ticket.rowVersion,roundId:ticket.roundId || '' }
  const source={expectedVersion:ticket.rowVersion,sourceRoundId:ticket.roundId||'',sourceClosureOperationId:ticket.currentReopen?.sourceClosureOperationId||ticket.closure?.operationId||form.sourceClosureOperationId}
  if(action==='reopen-request')return {...source,reason:form.reason}
  if(action==='reopen-approve'||action==='reopen-reject')return {...source,decision:action==='reopen-approve'?'APPROVE':'REJECT',reason:form.reason,...(action==='reopen-approve'?{sameFault:form.sameFault,priority:form.priority,...(form.specifyTarget?{targetDepartmentId:form.departmentId,targetEngineerUserId:form.engineerUserId}:{})}:{})}
  if(action==='round-response')return {expectedVersion:ticket.rowVersion,body:form.body}
  if (action === 'transfer-request') return {...base,sourceAssignmentId:ticket.assignment?.assignmentId || '',targetDepartmentId:form.departmentId,targetEngineerUserId:form.engineerUserId,reason:form.reason}
  if (action === 'transfer-withdraw') return {...base,sourceAssignmentId:ticket.currentTransfer?.sourceAssignmentId || '',reason:form.reason}
  if (action === 'cancel-direct') return { ...base,...(form.reason ? {reason:form.reason} : {}) }
  if (action === 'cancel-request') return { ...base,reason:form.reason }
  if (action === 'cancel-approve' || action === 'cancel-reject') return { ...base,decision:action === 'cancel-approve' ? 'APPROVE' : 'REJECT',reason:form.reason }
  if (action === 'supplement-reply') return { ...base,body:form.body,attachmentIds:[...form.evidenceFileIds] }
  if (action === 'supplement-request') return { ...base,question:form.body,...(ticket.status === 'IN_PROGRESS' ? {assignmentId:ticket.assignment?.assignmentId || ''} : {}) }
  if (action === 'accept') return { ...base,categoryCode:form.categoryCode,priority:form.priority,acceptanceMessage:form.acceptanceMessage,...(form.priority !== (ticket.initialPriority || initialPriority[ticket.impact]) ? { priorityChangeReason:form.priorityChangeReason } : {}) }
  if (action === 'reject') return { ...base,reason:form.reason }
  if (action === 'assign') return { ...base,departmentId:form.departmentId,engineerUserId:form.engineerUserId,...(form.note ? { note:form.note } : {}) }
  if (action === 'assignment-accept') return base
  if (action === 'release-invalid-assignment') return { ...base,assignmentId:ticket.assignment?.assignmentId || '',reason:form.reason }
  if (action === 'solve') return { ...base,assignmentId:ticket.assignment?.assignmentId || '',cause:form.cause,resolutionMethod:form.resolutionMethod,customerMessage:form.customerMessage,completedAt:new Date(form.completedAt).toISOString(),confirmedWorkHours:ticket.recordedWorkHours || '',evidenceFileIds:[...form.evidenceFileIds] }
  if (isCustomerDecision(action)) return { ...base,decision:action === 'customer-return' ? 'RETURN' : 'CONFIRM',...(action === 'customer-return' ? {reason:form.reason} : {}) }
  if (action === 'close') return { ...base,solutionId:ticket.currentSolution?.solutionId || '',closeMessage:form.closeMessage,acknowledged:form.acknowledged }
  return { ...base,assignmentId:ticket.assignment?.assignmentId || '',body:form.body,workHours:form.workHours,visibility:form.visibility }
}
export function validateAction(ticket: TicketDetail, action: string, f: ActionForm) {
  if (!ticket.roundId || !ticket.rowVersion || !actionLabels[action]) return '当前轮次、版本或动作无法确认，请刷新详情'
  if(ticket.readOnly)return '历史轮次只读，不能驱动当前轮处理'
  if(action.startsWith('reopen-')){
    if(ticket.status!=='CLOSED'||!(ticket.currentReopen?.sourceClosureOperationId||ticket.closure?.operationId||f.sourceClosureOperationId))return '仅真实已关闭的当前轮可以申请或审核重开'
    if(!f.reason.trim()||!validText(f.reason,5,500,true))return '公开理由须为5～500字符'
    if(action==='reopen-request'&&ticket.currentReopen?.state==='PENDING_REVIEW')return '已有待审核申请，申请不等于已重开'
    if(action!=='reopen-request'&&(!ticket.currentReopen||ticket.currentReopen.state!=='PENDING_REVIEW'||ticket.currentReopen.sourceRoundId!==ticket.roundId||ticket.closure?.operationId&&ticket.currentReopen.sourceClosureOperationId!==ticket.closure.operationId))return '当前申请或原关闭事实已变化'
    if(action==='reopen-approve'&&(!f.sameFault||!['P1','P2','P3'].includes(f.priority)||f.specifyTarget&&(!f.departmentId||!f.engineerUserId)))return '请确认同一故障、等级及明确是否指定合格目标'
    if(!f.acknowledged)return '请预览并确认原轮次、关闭事实与本次公开决定'
  }
  if(action==='round-response'&&(!['WAITING_ASSIGNMENT','ASSIGNED','IN_PROGRESS'].includes(ticket.status)||!f.body.trim()||!validText(f.body,5,2000,true)))return '仅当前活动轮可发送5～2000字符实际人工接手回复'
  if (ticket.status === 'CANCELLED') return '工单已取消，仅可按当前权限查询历史，不能继续处理'
  if (['transfer-request','transfer-withdraw'].includes(action)) {
    if (!['ASSIGNED','IN_PROGRESS'].includes(ticket.status) || !ticket.assignment?.assignmentId || ticket.currentCancellation) return '仅当前已分派或处理中且没有取消申请时可转派'
    if (!f.reason.trim() || !validText(f.reason,5,500,true)) return '转派或撤回理由须为5～500字符'
    if (action === 'transfer-request' && (ticket.currentTransfer && !ticket.allowedActions?.includes('transfer-request') || !f.departmentId || !f.engineerUserId || f.engineerUserId === ticket.assignment.engineerUserId)) return '请选择不同于当前负责人的本次合格目标，不能叠加待确认转派'
    if (action === 'transfer-withdraw' && (!ticket.currentTransfer || ticket.currentTransfer.sourceAssignmentId !== ticket.assignment.assignmentId || ticket.currentTransfer.state !== 'PENDING_CONFIRMATION' || ticket.currentTransfer.effectiveState === 'EXPIRED')) return '当前转派已变化或到期，请刷新'
  }
  if (action.startsWith('cancel-')) {
    if (!cancelAllowed(ticket,action)) return '取消对象或当前状态已变化，请刷新后重新决定'
    if (action === 'cancel-direct' ? !!f.reason && (!f.reason.trim() || !validText(f.reason,1,500,true)) : !f.reason.trim() || !validText(f.reason,5,500,true)) return action === 'cancel-direct' ? '可选取消原因须为1～500字符' : '公开申请或审核理由须为5～500字符'
    if (!f.acknowledged) return '请明确确认本次取消对象、公开理由与影响'
  }
  if (action === 'supplement-reply') {
    if (ticket.status !== 'WAITING_INFORMATION' || ticket.currentSupplement?.state !== 'OPEN') return '仅可回复当前未完成补充请求'
    if (!f.body.trim() || !validText(f.body,1,2000,true)) return '补充回复须为1～2000字符'
    if (f.evidenceFileIds.length > 5 || new Set(f.evidenceFileIds).size !== f.evidenceFileIds.length) return '最多5个不同的当前回复附件'
  }
  if (action === 'supplement-request') {
    if (!['PENDING_ACCEPTANCE','IN_PROGRESS'].includes(ticket.status) || ticket.currentSupplement?.state === 'OPEN' || ticket.status === 'IN_PROGRESS' && !ticket.assignment?.assignmentId) return '仅待受理或当前处理中可请求补充，不能叠加未完成请求'
    if (!f.body.trim() || !validText(f.body,5,500,true)) return '客户可见补充问题须为5～500字符'
  }
  if (['assignment-accept','release-invalid-assignment','records'].includes(action) && !ticket.assignment?.assignmentId) return '当前分派无法确认，请刷新详情'
  if (action === 'accept') {
    if (!['HARDWARE','SOFTWARE','NETWORK','OTHER'].includes(f.categoryCode)) return '请选择故障类别'
    if (!['P1','P2','P3'].includes(f.priority)) return '请选择最终优先级'
    if (!f.acceptanceMessage.trim() || !validText(f.acceptanceMessage,1,500,true)) return '客户可见人工受理说明须为1～500字符'
    if (f.priority !== (ticket.initialPriority || initialPriority[ticket.impact]) && (!f.priorityChangeReason.trim() || !validText(f.priorityChangeReason,5,500,true))) return '调整优先级须填写5～500字符原因'
  }
  if (['reject','release-invalid-assignment'].includes(action) && (!f.reason.trim() || !validText(f.reason,5,500,true))) return '理由须为5～500字符'
  if (action === 'assign' && (!f.departmentId || !f.engineerUserId)) return '请先查询当前授权部门的合格候选并选择工程师'
  if (f.note && !validText(f.note,1,500,true)) return '分派说明最多500字符'
  if (action === 'records') {
    if (!f.body.trim() || !validText(f.body,1,2000,true)) return '处理记录正文须为1～2000字符'
    if (!workHoursValid(f.workHours)) return '工时须为0～24的十进制字符串，最多2位小数'
    if (!['INTERNAL','CUSTOMER_VISIBLE'].includes(f.visibility)) return '请选择记录可见性'
  }
  if (action === 'solve') {
    if (!ticket.assignment?.assignmentId || ticket.status !== 'IN_PROGRESS') return '仅当前已接单处理中方案可提交，请刷新详情'
    for (const [label,value] of [['故障原因',f.cause],['解决方式',f.resolutionMethod],['客户说明',f.customerMessage]]) if (!value!.trim() || !validText(value!,5,2000,true)) return `${label}须为5～2000字符`
    if (!f.completedAt || !Number.isFinite(Date.parse(f.completedAt))) return '请选择有效的实际完成时间'
    if (Date.parse(f.completedAt) < Date.parse(ticket.timing?.roundStartedAt || ticket.submittedAt)) return '实际完成时间不能早于本轮起点'
    if (Date.parse(f.completedAt) > Date.now()) return '实际完成时间不能晚于当前时间，服务端仍会核验'
    if (!ticket.recordedWorkHours || !/^(?:0|[1-9]\d{0,9})(?:\.\d{1,2})?$/.test(ticket.recordedWorkHours) || !f.hoursConfirmed) return '请核对并确认服务端累计工时；修改须先追加真实处理记录'
    if (f.evidenceFileIds.length > 5 || new Set(f.evidenceFileIds).size !== f.evidenceFileIds.length || f.evidenceFileIds.some(id => !ticket.attachments.some(file => file.fileId === id && file.state === 'READY'))) return '仅能显式引用本次已核验的最多5份不同READY公开引用附件'
  }
  if (isCustomerDecision(action)) {
    if (ticket.status !== 'PENDING_CUSTOMER_CONFIRMATION' || !ticket.currentSolution?.solutionId || ticket.currentSolution.decision) return '当前待确认方案已变化或已有决定，请刷新后重新选择'
    if (!['CONFIRM','RETURN'].includes(f.decision)) return '请选择确认或反馈未解决'
    if (action === 'customer-return' && (!f.reason.trim() || !validText(f.reason,5,500,true))) return '未解决理由须为5～500字符'
  }
  if (action === 'close') {
    if (ticket.status !== 'RESOLVED' || !ticket.currentSolution?.solutionId) return '仅客户已确认解决的当前方案可收尾关闭'
    if (!f.closeMessage.trim() || !validText(f.closeMessage,5,500,true)) return '客户可见收尾说明须为5～500字符'
    if (!f.acknowledged) return '请明确确认已核对资料与服务端适用检查'
  }
  return ''
}




export const isCustomerDecision = (action: string) => action === 'customer-confirm' || action === 'customer-return'
export const wireAction = (action: string) => isCustomerDecision(action) ? 'customer-decision' : ['cancel-approve','cancel-reject'].includes(action) ? 'cancel-decision' : action


export function cancelAllowed(ticket: TicketDetail, action: string) {
  const direct = ticket.status === 'PENDING_ACCEPTANCE' || ticket.status === 'WAITING_INFORMATION' && ticket.currentSupplement?.state === 'OPEN' && ticket.currentSupplement.resumeState === 'PENDING_ACCEPTANCE'
  const active = ['WAITING_ASSIGNMENT','ASSIGNED','IN_PROGRESS','PENDING_CUSTOMER_CONFIRMATION'].includes(ticket.status) || ticket.status === 'WAITING_INFORMATION' && ticket.currentSupplement?.state === 'OPEN' && ticket.currentSupplement.resumeState === 'IN_PROGRESS'
  return action === 'cancel-direct' ? direct && !ticket.currentCancellation : action === 'cancel-request' ? active && !ticket.currentCancellation : active && ticket.currentCancellation?.state === 'PENDING_REVIEW' && ticket.currentCancellation.roundId === ticket.roundId
}
