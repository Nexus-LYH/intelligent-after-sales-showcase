import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const here = path.dirname(fileURLToPath(import.meta.url));
const contractPath = path.resolve(here, '../../../开发者设计/events-v1.yaml');
const contract = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const index = new Map();
for (const entry of contract.catalogue) {
  for (const type of entry.types) {
    assert(!index.has(type), `Duplicate event type ${type}`);
    assert(entry.proposedPayloadReferences.every(k => contract.envelope.payloadAllowedKeys.includes(k)));
    assert(entry.consumers.length > 0);
    index.set(type, entry);
  }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const decimal = /^(0|[1-9][0-9]*)$/;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function validateEvent(event, authenticatedProducer) {
  const errors = [];
  const fail = key => errors.push(key); // Never echo payload/credentials on failure.
  if (!object(event)) return ['envelope.object'];
  if (Buffer.byteLength(JSON.stringify(event), 'utf8') > contract.envelope.maxBytes) fail('envelope.maxBytes');
  for (const key of contract.envelope.required) if (!Object.hasOwn(event, key)) fail(`required.${key}`);
  const allowed = [...contract.envelope.required, ...contract.envelope.optional];
  for (const key of Object.keys(event)) if (!allowed.includes(key)) fail('envelope.unknownField');
  if (typeof event.eventId !== 'string' || !uuid.test(event.eventId)) fail('eventId.uuid');
  if (event.schemaVersion !== 1) fail('schemaVersion.unsupported');
  if (typeof event.tenantId !== 'string' || !/^[1-9][0-9]*$/.test(event.tenantId)) fail('tenantId.string');
  if (typeof event.aggregateVersion !== 'string' || !decimal.test(event.aggregateVersion)) fail('aggregateVersion.string');
  for (const key of ['aggregateId', 'correlationId']) if (typeof event[key] !== 'string' || !event[key].length || event[key].length > 128) fail(`${key}.string`);
  for (const key of ['traceId', 'causationId']) if (Object.hasOwn(event, key) && (typeof event[key] !== 'string' || !event[key].length || event[key].length > 128)) fail(`${key}.string`);
  if (typeof event.occurredAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(event.occurredAt) || !Number.isFinite(Date.parse(event.occurredAt)) || new Date(event.occurredAt).toISOString() !== event.occurredAt) fail('occurredAt.utc');
  const entry = index.get(event.type);
  if (!entry) fail('type.unknown');
  if (!authenticatedProducer || event.producer !== authenticatedProducer || entry?.producer !== authenticatedProducer) fail('producer.notAuthenticatedOwner');
  if (!object(event.payload)) fail('payload.object');
  else {

    for (const [key, value] of Object.entries(event.payload)) {
      if (!contract.envelope.payloadAllowedKeys.includes(key)) fail('payload.disallowedField');
      if (typeof value !== 'string' || value.length < 1 || value.length > 128 || !/^[A-Za-z0-9_.:-]+$/.test(value)) fail('payload.referenceOnly');
      if (['quoteVersion', 'generation', 'securityEpoch'].includes(key) && (typeof value !== 'string' || !decimal.test(value))) fail('payload.versionString');
    }
  }
  return errors;
}

function selfTest() {
  const sample = {eventId:'12345678-1234-1234-1234-123456789abc', type:'ticket.submitted', schemaVersion:1, tenantId:'1', aggregateId:'ticket-1', aggregateVersion:'1', occurredAt:'2026-10-20T12:00:00.000Z', producer:'ticket', correlationId:'operation-1', payload:{ticketId:'ticket-1',roundId:'round-1'}};
  let checks = 0;
  const check = (value, producer, valid) => {assert.equal(validateEvent(value, producer).length === 0, valid); checks++;};
  check(sample, 'ticket', true);
  check(sample, 'finance', false);
  check(sample, undefined, false);
  check({...sample, tenantId:1}, 'ticket', false);
  check({...sample, aggregateVersion:'01'}, 'ticket', false);
  check({...sample, occurredAt:'2026-02-30T12:00:00.000Z'}, 'ticket', false);
  check({...sample, occurredAt:'2026-10-20T20:00:00.000+08:00'}, 'ticket', false);
  check({...sample, schemaVersion:2}, 'ticket', false);
  check({...sample, payload:{ticketId:'t'}}, 'ticket', true);
  check({...sample, payload:{...sample.payload, token:'synthetic-forbidden-value'}}, 'ticket', false);
  check({...sample, payload:{...sample.payload, stateCode:'customer prose'}}, 'ticket', false);
  check({...sample, secret:'synthetic-forbidden-value'}, 'ticket', false);
  check({...sample, type:'inventory.dispatched', producer:'ticket'}, 'ticket', false);
  for (const [type, entry] of index) {
    const payload = Object.fromEntries(entry.proposedPayloadReferences.map(k => [k, k === 'securityEpoch' ? '1' : 'reference-1']));
    check({...sample, type, producer:entry.producer, payload}, entry.producer, true);
  }
  console.log(JSON.stringify({contract:contract.version, eventTypes:index.size, structuralChecks:checks, passed:true, runtimeDeliveryVerified:false}));
}

if (process.argv.includes('--self-test')) selfTest();
else if (process.argv.includes('--check-contract')) console.log(JSON.stringify({contract:contract.version,eventTypes:index.size,structureValid:true,runtimeDeliveryVerified:false}));
else if (process.argv[2] && process.argv[3]) {
  const errors = validateEvent(JSON.parse(fs.readFileSync(process.argv[2], 'utf8')), process.argv[3]);
  console.log(JSON.stringify({valid:errors.length === 0, errors}));
  process.exitCode = errors.length ? 1 : 0;
} else {
  console.error('Usage: node validate-events.mjs --self-test | --check-contract | <event.json> <authenticated-producer>');
  process.exitCode = 2;
}
