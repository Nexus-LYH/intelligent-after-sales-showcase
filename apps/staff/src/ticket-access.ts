import type { InjectionKey } from 'vue'
import { ApiError } from './api'
// Called only after the child has checked its own identity/lifecycle/request scope.
export const ticketReadDenied: InjectionKey<() => void> = Symbol('ticket-read-denied')
export const isObjectReadDenied = (e: unknown) => e instanceof ApiError && (e.status === 403 || e.status === 404)
