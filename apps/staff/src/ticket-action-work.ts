import { computed, onBeforeUnmount, shallowReactive } from 'vue'
import { actorKey, useTicketWork } from './ticket-work'
import type { ActionBody } from './ticket-action-api'
export interface PendingTicketAction { key: string; actor: string; ticketId: string; action: string; path: string; body: ActionBody; binding?: Readonly<Record<string,string>> }
const pending = shallowReactive(new Map<string, PendingTicketAction>())
const previous = shallowReactive(new Map<string, PendingTicketAction>())
const owners = new Map<string, symbol>()
const slotOf = (p: Pick<PendingTicketAction, 'actor' | 'ticketId'>) => JSON.stringify([p.actor, p.ticketId])
export function forgetUnauthorizedTicketBody(actor: string, ticketId: string) {
  const slot = JSON.stringify([actor,ticketId]), p = pending.get(slot)
  // Keep only non-secret original operation metadata for later authorized GET;
  // the immutable original body is no longer retained or eligible for replay.
  if (p) { previous.set(p.key,Object.freeze({...p,body:Object.freeze({})})); pending.delete(slot) }
  owners.delete(slot)
  for (const [key,item] of previous) if (item.actor === actor && item.ticketId === ticketId) previous.set(key,Object.freeze({...item,body:Object.freeze({})}))
}
export function sameTicketAction(a: PendingTicketAction | undefined, b: PendingTicketAction) {
  return !!a && a.key === b.key && a.actor === b.actor && a.ticketId === b.ticketId && a.action === b.action && a.path === b.path && JSON.stringify(a.body) === JSON.stringify(b.body) && JSON.stringify(a.binding) === JSON.stringify(b.binding)
}
export function useTicketActionWork(route: () => string, ticketId: () => string, context: () => string = () => '') {
  const work = useTicketWork(route), localOwners = new Map<string, symbol>()
  const current = computed(() => pending.get(JSON.stringify([actorKey(work.auth), ticketId()])))
  const archived = computed(() => Array.from(previous.values()).filter(p => p.actor === actorKey(work.auth) && p.ticketId === ticketId()))
  function remember(action: string, path: string, body: ActionBody, key: string = crypto.randomUUID(), binding?: Readonly<Record<string,string>>) {
    if (current.value) return current.value
    const snapshot = Object.fromEntries(Object.entries(body).map(([key, value]) => [key, Array.isArray(value) ? Object.freeze([...value]) : value]))
    const p: PendingTicketAction = Object.freeze({ key, actor: actorKey(work.auth), ticketId: ticketId(), action, path, body: Object.freeze(snapshot), ...(binding ? {binding:Object.freeze({...binding})} : {}) })
    pending.set(slotOf(p), p); return p
  }
  function begin(p?: PendingTicketAction) {
    const owner = Symbol(), id = p && slotOf(p)
    if (id) { owners.set(id, owner); localOwners.set(id, owner) }
    const expectedContext = context()
    const scope = work.begin(() => context() === expectedContext && (!p || sameTicketAction(pending.get(slotOf(p)), p) && owners.get(slotOf(p)) === owner))
    function finish() {
      scope.finish()
      if (id && owners.get(id) === owner) owners.delete(id)
      if (id && localOwners.get(id) === owner) localOwners.delete(id)
    }
    return { current: scope.current, finish }
  }
  function complete(p: PendingTicketAction, scope: ReturnType<typeof begin>) {
    if (!scope.current() || !sameTicketAction(pending.get(slotOf(p)), p)) return false
    scope.finish()
    if (sameTicketAction(pending.get(slotOf(p)), p)) pending.delete(slotOf(p))
    return true
  }
  function archive(p: PendingTicketAction, scope: ReturnType<typeof begin>) {
    if (!scope.current() || !sameTicketAction(pending.get(slotOf(p)),p)) return false
    previous.set(p.key,p); scope.finish()
    if (sameTicketAction(pending.get(slotOf(p)),p)) pending.delete(slotOf(p))
    return true
  }
  onBeforeUnmount(() => { for (const [id, owner] of localOwners) if (owners.get(id) === owner) owners.delete(id) })
  return { ...work, current, archived, remember, begin, complete, archive }
}
