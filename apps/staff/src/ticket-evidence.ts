import{ApiError}from'./api'
import{validSupplement,type SupplementRequest}from'./ticket-supplement-api'
import type{TicketDetail,TicketFile}from'./ticket-api'
export function referencedEvidence(rows:SupplementRequest[],ticket:TicketDetail,roundId:string){
 if(!Array.isArray(rows)||rows.some(r=>!validSupplement(r,{...ticket,roundId})))throw new ApiError(503,'EVIDENCE_PROJECTION_INVALID','所选轮次公开附件归属无法确认')
 const files=new Map<string,TicketFile>();for(const row of rows)if(row.state==='REPLIED'&&row.reply)for(const f of row.reply.attachments){const prior=files.get(f.fileId);if(prior&&(prior.sha256!==f.sha256||prior.size!==f.size))throw new ApiError(503,'EVIDENCE_PROJECTION_INVALID','既有附件不可变摘要不一致');files.set(f.fileId,f)}return[...files.values()]
}
