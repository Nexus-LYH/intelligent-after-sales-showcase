import type { Expected, Identity } from './api'

export interface AuthSource {
  generation: number
  identity: Identity | null
  expected?: Expected | null
}

// A request owns a particular identity/attempt, not just an actor ID. The extra
// predicate binds a component lifetime, route, request slot, or pending operation.
function stamp(source: AuthSource) {
  const me = source.identity, expected = source.expected
  return JSON.stringify([
    source.generation, me?.userId, me?.realm, me?.tenant?.id, me?.account, me?.loginAttemptId,
    expected?.attemptId, expected?.realm, expected?.account, expected?.tenantCode,
  ])
}

export function captureRequestScope(source: AuthSource, extra: () => boolean = () => true) {
  let captured = stamp(source)
  return {
    current: () => extra() && captured === stamp(source),
    // Only used immediately after this request's own validated synchronous state
    // transition. Never refresh a lease after an await without checking it first.
    checkpoint: () => { captured = stamp(source) },
  }
}

export interface PendingRevocation {
  id: string; account: string; reason: string; version: string; key: string; actor: string
  actorRealm?: string; actorNamespace?: string
}

export function sameRevocation(left: PendingRevocation | null, right: PendingRevocation) {
  return !!left && left.key === right.key && left.id === right.id && left.actor === right.actor
    && left.actorRealm === right.actorRealm && left.actorNamespace === right.actorNamespace
    && left.version === right.version && left.reason === right.reason
}
