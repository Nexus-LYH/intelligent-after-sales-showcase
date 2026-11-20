package com.ias.identity.auth;

import com.ias.identity.persistence.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AuthService {
    public static final String AUTH_COOKIE="IAS-SESSION-DEV", PREAUTH_COOKIE="IAS-PREAUTH-DEV";
    private final AuthMapper mapper;
    private final AuthCrypto crypto;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final com.ias.identity.persistence.RevocationMapper revocations;
    private final AccountMapper accounts;
    @org.springframework.beans.factory.annotation.Autowired private EmployeeScopeReferences scopeReferences;
    private final String dummyVerifier;

    public AuthService(AuthMapper mapper, AuthCrypto crypto, PasswordEncoder encoder, TransactionTemplate tx, Clock clock,
                       com.ias.identity.persistence.RevocationMapper revocations,AccountMapper accounts) {
        this.mapper=mapper; this.crypto=crypto; this.encoder=encoder; this.tx=tx; this.clock=clock;
        this.dummyVerifier=encoder.encode(crypto.randomToken());
        this.revocations=revocations;
        this.accounts=accounts;
    }
    public LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }
    public static void budget(long deadline) {
        if (System.nanoTime()>=deadline) throw new AuthFailure(503,"OPERATION_RESULT_UNKNOWN","VERIFY_ATTEMPT");
    }
    public static class Reply {
        public final Map<String,Object> body;
        public final String cookieToken;
        public final boolean auth;
        public final LocalDateTime credentialDeadline;
        public Reply(Map<String,Object> body, String token, boolean auth) { this(body,token,auth,null); }
        public Reply(Map<String,Object> body, String token, boolean auth,LocalDateTime credentialDeadline) {
            this.body=body; this.cookieToken=token; this.auth=auth; this.credentialDeadline=credentialDeadline;
        }
        @Override public String toString() { return "AuthReply[redacted]"; }
    }
    private record Verified(Rows.Context context, Rows.User user, Rows.Tenant tenant,String replacementToken) {
        Verified(Rows.Context context,Rows.User user,Rows.Tenant tenant) { this(context,user,tenant,null); }
        @Override public String toString() { return "VerifiedIdentity[redacted]"; }
    }
    /** Last delivery guard, called immediately before setting any authentication Cookie. */
    public void checkDelivery(Reply reply,long deadline) {
        budget(deadline);
        if (reply.cookieToken!=null && reply.credentialDeadline!=null && !now().isBefore(reply.credentialDeadline))
            throw new AuthFailure(503,"OPERATION_RESULT_UNKNOWN","VERIFY_ATTEMPT");
    }

    public Reply csrf(String authToken, String preauthToken, long deadline) {
        if (authToken!=null) {
            Verified verified=verify(authToken,deadline,true);
            return new Reply(csrfBody(verified.context),verified.replacementToken,true,verified.context.deliveryConfirmDeadline);
        }
        return tx.execute(status -> {
            budget(deadline);
            Rows.Context prior=lookup(preauthToken);
            if (prior!=null) {
                prior=mapper.lockContext(prior.id);
                if ("PREAUTH".equals(prior.kind) && prior.revokedAt==null && now().isBefore(prior.expiresAt)) {
                    if ("PROCESSING".equals(prior.preauthState)) throw new AuthFailure(409,"LOGIN_IN_PROGRESS","VERIFY_ATTEMPT");
                    if ("READY".equals(prior.preauthState)) return new Reply(csrfBody(prior),null,false);
                }
            }
            String token=crypto.randomToken();
            Rows.Context context=new Rows.Context();
            context.tokenHash=crypto.hashToken(token); context.csrfToken=crypto.randomToken();
            context.kind="PREAUTH"; context.preauthState="READY"; context.issuedAt=now();
            context.expiresAt=context.issuedAt.plusMinutes(10); context.updatedAt=context.issuedAt;
            mapper.insertContext(context);
            budget(deadline);
            return new Reply(csrfBody(context),token,false);
        });
    }
    private Map<String,Object> csrfBody(Rows.Context context) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("token",context.csrfToken); body.put("headerName","X-CSRF-TOKEN");
        body.put("expiresAt",utc(context.expiresAt)); body.put("contextKind",context.kind);
        if ("AUTH".equals(context.kind)) body.put("loginAttemptId",context.loginAttemptId);
        return body;
    }
    public Reply login(String authToken, String preauthToken, String csrf, String attemptId,
                       String realm, String tenantCode, String account, String password, String trace, long deadline) {
        if (authToken!=null) {
            verify(authToken,deadline,false);
            throw new AuthFailure(409,"LOGIN_REQUIRES_LOGOUT","LOGOUT_CURRENT");
        }
        String normalizedAccount=AuthCrypto.account(account);
        AuthCrypto.loginPassword(password);
        String normalizedAttempt=AuthCrypto.attemptId(attemptId);
        String normalizedTenant;
        if ("TENANT".equals(realm)) normalizedTenant=AuthCrypto.tenantCode(tenantCode);
        else if ("PLATFORM".equals(realm) && tenantCode==null) normalizedTenant=null;
        else throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");

        Rows.Context preauth=claim(preauthToken,csrf,normalizedAttempt,realm,normalizedTenant,normalizedAccount,deadline);
        Rows.Tenant locatedTenant=normalizedTenant==null ? null : mapper.tenantByCode(normalizedTenant);
        String namespace="PLATFORM".equals(realm) ? "PLATFORM" : locatedTenant==null ? null : "TENANT:"+locatedTenant.id;
        Rows.User candidate=namespace==null ? null : mapper.userByAccount(namespace,normalizedAccount);
        // Expensive password verification deliberately occurs without database locks.
        boolean matches;
        try { matches=encoder.matches(password,candidate==null ? dummyVerifier : candidate.passwordVerifier); }
        catch (IllegalArgumentException e) { matches=false; }
        if (!matches || candidate==null) {
            terminalFailure(preauth.id,normalizedAttempt,trace,deadline);
            throw new AuthFailure(401,"AUTHENTICATION_FAILED","NEW_PREAUTH");
        }
        long validatedVersion=candidate.securityVersion;
        String token=crypto.randomToken();
        Reply reply=tx.execute(status -> {
            Rows.Tenant tenant=candidate.tenantId==null ? null : mapper.lockTenant(candidate.tenantId);
            Rows.User user=mapper.lockUser(candidate.id);
            Rows.Context context=mapper.lockContext(preauth.id);
            Rows.Attempt attempt=mapper.lockAttempt(normalizedAttempt);
            checkClaim(context,attempt,normalizedAttempt,deadline);
            if (!userEnabled(user,tenant) || user.securityVersion!=validatedVersion) {
                failed(context,attempt,trace);
                return null;
            }
            LocalDateTime time=now();
            if (!time.isBefore(context.expiresAt)) throw AuthFailure.expired();
            Rows.Context auth=new Rows.Context();
            auth.kind="AUTH"; auth.tokenHash=crypto.hashToken(token); auth.csrfToken=crypto.randomToken();
            auth.userId=user.id; auth.tenantId=user.tenantId; auth.issuedUserSecurityVersion=user.securityVersion;
            auth.issuedTenantSecurityEpoch=tenant==null ? null : tenant.securityEpoch;
            auth.accessState=user.mustChangePassword==1 ? "PASSWORD_CHANGE_REQUIRED" : "NORMAL";
            auth.issuedAt=time; auth.updatedAt=time;
            auth.expiresAt="NORMAL".equals(auth.accessState) ? time.plusHours(8) : time.plusMinutes(15);
            auth.deliveryState="UNCONFIRMED";
            auth.deliveryConfirmDeadline=Collections.min(List.of(time.plusMinutes(10),context.expiresAt,auth.expiresAt));
            auth.loginAttemptId=normalizedAttempt;
            mapper.insertContext(auth);
            mapper.finishAttempt(normalizedAttempt,"ISSUED",null,time,auth.expiresAt);
            mapper.preauthState(context.id,"CONSUMED",time,time);
            audit("LOGIN_ISSUED",user,auth,"SUCCESS",null,trace);
            budget(deadline);
            if (!now().isBefore(context.expiresAt)) throw AuthFailure.expired();
            Map<String,Object> body=identity(new Verified(auth,user,tenant));
            body.put("nextAction",user.mustChangePassword==1 ? "CHANGE_PASSWORD" : "NONE");
            return new Reply(body,token,true,auth.deliveryConfirmDeadline);
        });
        if (reply==null) throw new AuthFailure(401,"AUTHENTICATION_FAILED","NEW_PREAUTH");
        budget(deadline);
        // A delayed COMMIT may have consumed the delivery window. Keep real records, never send a late credential.
        if (!now().isBefore(preauth.expiresAt)) throw new AuthFailure(503,"OPERATION_RESULT_UNKNOWN","VERIFY_ATTEMPT");
        return reply;
    }
    private Rows.Context claim(String token, String csrf, String id, String realm, String tenant, String account, long deadline) {
        Rows.Context located=lookup(token);
        if (located==null || !"PREAUTH".equals(located.kind)) throw AuthFailure.expired();
        try {
            return tx.execute(status -> {
                Rows.Context context=mapper.lockContext(located.id);
                budget(deadline);
                if (context.revokedAt!=null || !now().isBefore(context.expiresAt)) throw AuthFailure.expired();
                if (!crypto.csrfEquals(context.csrfToken,csrf)) throw new AuthFailure(403,"CSRF_INVALID","REUSE_PREAUTH");
                if (!"READY".equals(context.preauthState)) throw AuthFailure.repeated();
                Rows.Attempt attempt=new Rows.Attempt();
                attempt.attemptId=id; attempt.preauthContextId=context.id;
                attempt.expectedRealm=realm; attempt.expectedTenantCodeNormalized=tenant; attempt.expectedAccountNormalized=account;
                attempt.createdAt=now(); attempt.retainUntil=context.expiresAt;
                mapper.insertAttempt(attempt);
                mapper.preauthState(context.id,"PROCESSING",null,now());
                budget(deadline);
                return context;
            });
        } catch (DuplicateKeyException e) { throw AuthFailure.repeated(); }
    }
    private void checkClaim(Rows.Context context, Rows.Attempt attempt, String id, long deadline) {
        budget(deadline);
        if (context==null || context.revokedAt!=null || !now().isBefore(context.expiresAt)) throw AuthFailure.expired();
        if (!"PROCESSING".equals(context.preauthState) || attempt==null || !"PROCESSING".equals(attempt.status)
                || !context.id.equals(attempt.preauthContextId) || !id.equals(attempt.attemptId)) throw AuthFailure.repeated();
    }
    private void terminalFailure(Long contextId, String id, String trace, long deadline) {
        tx.executeWithoutResult(status -> {
            Rows.Context context=mapper.lockContext(contextId);
            Rows.Attempt attempt=mapper.lockAttempt(id);
            checkClaim(context,attempt,id,deadline);
            failed(context,attempt,trace);
        });
    }
    private void failed(Rows.Context context, Rows.Attempt attempt, String trace) {
        LocalDateTime time=now();
        mapper.finishAttempt(attempt.attemptId,"FAILED","AUTHENTICATION_FAILED",time,context.expiresAt);
        mapper.preauthState(context.id,"FAILED",null,time);
        mapper.audit(time,"LOGIN_FAILED","ANONYMOUS",null,null,"SESSION",context.id,null,"FAILURE","AUTHENTICATION_FAILED",attempt.attemptId,trace);
    }
    private boolean userEnabled(Rows.User user, Rows.Tenant tenant) {
        return user!=null && user.status==1 && (!"CUSTOMER".equals(user.identityType) || user.bindingState==2)
                && (user.tenantId==null || (tenant!=null && tenant.status==1 && user.tenantId.equals(tenant.id)));
    }
    private Rows.Context lookup(String token) {
        byte[] hash=crypto.hashToken(token);
        return hash==null ? null : mapper.contextByHash(hash);
    }
    public Reply me(String token, long deadline) {
        Verified verified=verify(token,deadline,true);
        return new Reply(identity(verified),verified.replacementToken,true,verified.context.deliveryConfirmDeadline);
    }
    private Verified verify(String token, long deadline,boolean confirm) {
        Rows.Context located=lookup(token);
        if (located==null || !"AUTH".equals(located.kind)) throw AuthFailure.invalid();
        Verified result=tx.execute(status -> {
            Verified verified=lockAndValidate(located,deadline);
            Rows.Context context=verified.context;
            if (confirm && "UNCONFIRMED".equals(context.deliveryState)) {
                LocalDateTime time=now();
                String replacement=crypto.randomToken();
                byte[] replacementHash=crypto.hashToken(replacement);
                if (mapper.confirm(context.id,time,located.tokenHash,replacementHash)!=1) throw AuthFailure.invalid();
                context.confirmedAt=time; context.deliveryState="CONFIRMED";
                context.tokenHash=replacementHash;
                verified=new Verified(context,verified.user,verified.tenant,replacement);
            }
            budget(deadline);
            return verified;
        });
        // No compensating transaction: the pre-confirmation credential was already retired atomically.
        // A timeout, exception, crash or lost response cannot deliver the new 256-bit credential.
        budget(deadline);
        if (result.replacementToken!=null && !now().isBefore(result.context.deliveryConfirmDeadline)) throw AuthFailure.invalid();
        if (!now().isBefore(result.context.expiresAt)) throw AuthFailure.invalid();
        return result;
    }
    private Verified lockAndValidate(Rows.Context located, long deadline) {
        Rows.Tenant tenant=located.tenantId==null ? null : mapper.lockTenant(located.tenantId);
        Rows.User user=mapper.lockUser(located.userId);
        Rows.Context context=mapper.lockContext(located.id);
        budget(deadline);
        LocalDateTime time=now();
        if (context==null || !java.security.MessageDigest.isEqual(context.tokenHash,located.tokenHash)
                || !"AUTH".equals(context.kind) || !Objects.equals(user==null ? null : user.tenantId,context.tenantId)
                || !userEnabled(user,tenant) || context.revokedAt!=null || !time.isBefore(context.expiresAt)
                || !Objects.equals(context.issuedUserSecurityVersion,user.securityVersion)
                || (tenant!=null && !Objects.equals(context.issuedTenantSecurityEpoch,tenant.securityEpoch))) throw AuthFailure.invalid();
        if (user.mustChangePassword==1 && !"PASSWORD_CHANGE_REQUIRED".equals(context.accessState)) throw AuthFailure.invalid();
        if ("UNCONFIRMED".equals(context.deliveryState)) {
            Rows.Attempt attempt=mapper.lockAttempt(context.loginAttemptId);
            Rows.Context original=attempt==null ? null : mapper.contextById(attempt.preauthContextId);
            if (!time.isBefore(context.deliveryConfirmDeadline) || original==null || !time.isBefore(original.expiresAt)) throw AuthFailure.invalid();
        }
        return new Verified(context,user,tenant);
    }
    public void logout(String token, String csrf, String trace, long deadline) {
        Rows.Context located=lookup(token);
        if (located==null || !"AUTH".equals(located.kind)) return;
        tx.executeWithoutResult(status -> {
            Verified verified;
            try { verified=lockAndValidate(located,deadline); }
            catch (AuthFailure e) { if (e.status==401) return; throw e; }
            if (!crypto.csrfEquals(verified.context.csrfToken,csrf)) throw new AuthFailure(403,"CSRF_INVALID",null);
            if (mapper.revoke(verified.context.id,now())==1) audit("LOGOUT",verified.user,verified.context,"SUCCESS",null,trace);
            budget(deadline);
        });
        budget(deadline);
    }
    public Map<String,Object> revokeUser(String token,String csrf,long targetId,long expected,String reason,String key,String trace,long deadline) {
        // Syntax is checked before starting writes. Never normalize business content or idempotency keys.
        if (targetId<1 || expected<1 || reason==null || AuthCrypto.codePoints(reason)<5 || AuthCrypto.codePoints(reason)>200
                || !validReason(reason) || key==null || !key.matches("[!-~]{1,128}"))
            throw new AuthFailure(400,"VALIDATION_ERROR",null);
        Rows.Context located=lookup(token);
        if (located==null || !"AUTH".equals(located.kind)) throw AuthFailure.invalid();
        byte[] digest=revocationDigest(targetId,expected,reason);
        Map<String,Object> result=tx.execute(status -> {
            // Same tenant serializes tenant security facts; platform user locks have a global ID order.
            if (located.tenantId!=null) mapper.lockTenant(located.tenantId);
            Rows.User target=revocations.user(targetId);
            if (target==null || !Objects.equals(target.tenantId,located.tenantId)) {
                Verified actor=lockAndValidate(located,deadline);
                authorizeRevocation(actor,csrf);
                throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            }
            mapper.lockUser(Math.min(located.userId,targetId));
            if (located.userId!=targetId) mapper.lockUser(Math.max(located.userId,targetId));
            Verified actor=lockAndValidate(located,deadline);
            authorizeRevocation(actor,csrf);
            target=revocations.user(targetId);
            if (target==null || !actor.user.loginNamespaceId.equals(target.loginNamespaceId))
                throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            Rows.Operation prior=revocations.operation(actor.user.loginNamespaceId,actor.user.id,key);
            // User locks serialize all requests by the same actor, including same key/different targets.
            if (prior!=null) {
                if (prior.digestSchemaVersion!=1 || !java.security.MessageDigest.isEqual(prior.requestDigest,digest))
                    throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT",null);
                return operationBody(prior);
            }
            if (target.securityVersion!=expected) throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);
            if (expected==Long.MAX_VALUE) throw new AuthFailure(409,"SECURITY_VERSION_EXHAUSTED",null);
            Rows.Operation operation=new Rows.Operation();
            operation.actorUserId=actor.user.id; operation.actorNamespaceId=actor.user.loginNamespaceId;
            operation.targetUserId=targetId; operation.idempotencyKey=key; operation.requestDigest=digest;
            operation.expectedSecurityVersion=expected; operation.newSecurityVersion=expected+1;
            operation.reason=reason; operation.traceId=trace; operation.revokedAt=now();
            if (revocations.advance(targetId,expected,operation.revokedAt)!=1) throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);
            revocations.insert(operation);
            revocations.audit(operation,target.tenantId);
            budget(deadline);
            if (!now().isBefore(actor.context.expiresAt)) throw AuthFailure.invalid();
            return operationBody(operation);
        });
        budget(deadline); // Self-revocation may return this committed response, never re-authenticate it here.
        return result;
    }
    private void authorizeRevocation(Verified actor,String csrf) {
        if (!crypto.csrfEquals(actor.context.csrfToken,csrf)) throw new AuthFailure(403,"CSRF_INVALID",null);
        authorizeManagement(actor);
    }
    private void authorizeManagement(Verified actor) {
        if (!"CONFIRMED".equals(actor.context.deliveryState)) throw new AuthFailure(403,"SESSION_CONFIRMATION_REQUIRED",null);
        if (!"NORMAL".equals(actor.context.accessState) || actor.user.mustChangePassword!=0)
            throw new AuthFailure(403,"ACCESS_DENIED",null);
        if (!permissions(actor.user).contains(actor.user.tenantId==null ? "identity.platform.session.revoke":"identity.session.revoke"))
            throw new AuthFailure(403,"ACCESS_DENIED",null);
    }
    public Map<String,Object> changePassword(String token,String csrf,String current,String replacement,String confirmation,String trace,long deadline) {
        Verified first=verify(token,deadline,false);
        if (!crypto.csrfEquals(first.context.csrfToken,csrf)) throw new AuthFailure(403,"CSRF_INVALID",null);
        if (!"CONFIRMED".equals(first.context.deliveryState)) throw new AuthFailure(403,"SESSION_CONFIRMATION_REQUIRED",null);
        AuthCrypto.loginPassword(current); AuthCrypto.newPassword(replacement);
        if (!replacement.equals(confirmation)) throw new AuthFailure(400,"VALIDATION_ERROR",null);
        boolean matches;
        try { matches=encoder.matches(current,first.user.passwordVerifier); }
        catch (IllegalArgumentException e) { matches=false; }
        if (!matches) throw new AuthFailure(403,"CURRENT_PASSWORD_INVALID",null);
        if (current.equals(replacement)) throw new AuthFailure(400,"PASSWORD_UNCHANGED",null);
        String verifier=encoder.encode(replacement); // Both expensive operations are outside locks.
        tx.executeWithoutResult(status -> {
            Verified actor=lockAndValidate(first.context,deadline);
            if (!"CONFIRMED".equals(actor.context.deliveryState)) throw new AuthFailure(403,"SESSION_CONFIRMATION_REQUIRED",null);
            if (!crypto.csrfEquals(actor.context.csrfToken,csrf)) throw new AuthFailure(403,"CSRF_INVALID",null);
            if (actor.user.securityVersion!=first.user.securityVersion || actor.user.securityVersion==Long.MAX_VALUE)
                throw new AuthFailure(409,"ACCOUNT_STATE_CONFLICT",null);
            LocalDateTime time=now();
            if (accounts.password(actor.user.id,actor.user.securityVersion,verifier,time)!=1) throw new AuthFailure(409,"ACCOUNT_STATE_CONFLICT",null);
            accounts.passwordAudit(actor.user,actor.user.securityVersion+1,time,trace);
            budget(deadline);
            if (!now().isBefore(actor.context.expiresAt)) throw AuthFailure.invalid();
        });
        budget(deadline);
        return Map.of("nextAction","LOGIN");
    }
    public Map<String,Object> users(String token,String keyword,Integer status,int page,int size,Long id,long deadline) {
        Rows.Context located=lookup(token);
        if (located==null || !"AUTH".equals(located.kind)) throw AuthFailure.invalid();
        String pattern=keyword==null || keyword.isEmpty() ? null:keyword.replace("!","!!").replace("%","!%").replace("_","!_");
        Map<String,Object> result=tx.execute(transaction -> {
            Verified actor=lockAndValidate(located,deadline);
            authorizeManagement(actor);
            if (id!=null) {
                Rows.User user=accounts.detail(actor.user.loginNamespaceId,id);
                if (user==null) throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
                return accountBody(user,actor.tenant);
            }
            long total=accounts.count(actor.user.loginNamespaceId,status,pattern);
            List<Map<String,Object>> items=accounts.users(actor.user.loginNamespaceId,status,pattern,size,(page-1L)*size).stream().map(u->accountBody(u,actor.tenant)).toList();
            budget(deadline);
            return Map.of("items",items,"total",total,"page",page,"size",size);
        });
        budget(deadline);
        return result;
    }
    public Map<String,Object> employeeAccess(String token,long id,long deadline) {
        Rows.Context located=lookup(token);if(located==null||!"AUTH".equals(located.kind))throw AuthFailure.invalid();
        Map<String,Object> result=tx.execute(transaction->{
            Verified actor=lockAndValidate(located,deadline);authorizeManagement(actor);
            if(actor.user.tenantId==null||!"EMPLOYEE".equals(actor.user.identityType))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            Rows.User target=accounts.detail(actor.user.loginNamespaceId,id);
            if(target==null||!"EMPLOYEE".equals(target.identityType)||!Objects.equals(actor.user.tenantId,target.tenantId))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            long tenant=actor.user.tenantId;var out=new LinkedHashMap<String,Object>();out.put("userId",Long.toString(id));out.put("securityVersion",Long.toString(target.securityVersion));
            Long department=accounts.department(tenant,id);out.put("departmentId",department==null?null:department.toString());out.put("capabilities",accounts.activeCapabilities(id));
            out.put("companyIds",accounts.companyScope(tenant,id).stream().map(Object::toString).toList());out.put("assignmentDepartmentIds",accounts.assignmentScope(tenant,id).stream().map(Object::toString).toList());out.put("approvalDepartmentIds",accounts.approvalScope(tenant,id).stream().map(Object::toString).toList());out.put("reopenDepartmentIds",accounts.reopenScope(tenant,id).stream().map(Object::toString).toList());out.put("warehouseIds",accounts.warehouseScope(tenant,id).stream().map(Object::toString).toList());
            Rows.User latest=accounts.detail(actor.user.loginNamespaceId,id);if(latest==null||!Objects.equals(target.securityVersion,latest.securityVersion))throw new AuthFailure(503,"ACCESS_SNAPSHOT_CHANGED",null);budget(deadline);return out;
        });budget(deadline);return result;
    }
    public Map<String,Object> setEmployeeStatus(String token,String csrf,long targetId,long expected,int desired,String reason,String operation,String trace,long deadline){
        if(targetId<1||expected<1||!Set.of(0,1).contains(desired)||reason==null||AuthCrypto.codePoints(reason)<5||AuthCrypto.codePoints(reason)>200||!validReason(reason))throw new AuthFailure(400,"VALIDATION_ERROR",null);
        try{if(operation==null||!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new AuthFailure(400,"VALIDATION_ERROR",null);}
        Rows.Context located=lookup(token);if(located==null||!"AUTH".equals(located.kind))throw AuthFailure.invalid();
        String digest;try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(com.ias.trust.Chain.JSON.writeValueAsBytes(List.of("EMPLOYEE_STATUS_V1",targetId,expected,desired,reason))));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Map<String,Object> result=tx.execute(state->{
            if(located.tenantId!=null)mapper.lockTenant(located.tenantId);mapper.lockUser(Math.min(located.userId,targetId));if(located.userId!=targetId)mapper.lockUser(Math.max(located.userId,targetId));
            Verified actor=lockAndValidate(located,deadline);if(!crypto.csrfEquals(actor.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
            if(!"CONFIRMED".equals(actor.context.deliveryState)||!"NORMAL".equals(actor.context.accessState)||actor.user.tenantId==null||!"EMPLOYEE".equals(actor.user.identityType)||!permissions(actor.user).contains("identity.employee.manage"))throw new AuthFailure(403,"ACCESS_DENIED",null);
            Rows.User target=revocations.user(targetId);if(target==null||!Objects.equals(target.tenantId,actor.user.tenantId)||!"EMPLOYEE".equals(target.identityType))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            if(accounts.employeeCreateOperation(operation)!=null||accounts.scopeOperation(operation)!=null)throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT",null);var prior=accounts.statusOperation(operation);if(prior!=null){if(!Objects.equals(prior.get("requestDigest"),digest)||((Number)prior.get("tenantId")).longValue()!=actor.user.tenantId||((Number)prior.get("actorUserId")).longValue()!=actor.user.id||((Number)prior.get("targetUserId")).longValue()!=targetId)throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT",null);return Map.of("operationId",operation,"userId",Long.toString(targetId),"status",((Number)prior.get("newStatus")).intValue(),"securityVersion",prior.get("newSecurityVersion").toString());}
            if(targetId==actor.user.id||permissions(target).contains("identity.employee.manage"))throw new AuthFailure(403,"ADMIN_TARGET_PROTECTED",null);
            if(target.securityVersion!=expected)throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);if(target.status==desired)throw new AuthFailure(409,"STATUS_UNCHANGED",null);if(expected==Long.MAX_VALUE)throw new AuthFailure(409,"SECURITY_VERSION_EXHAUSTED",null);
            var at=now();if(accounts.changeEmployeeStatus(actor.user.tenantId,targetId,desired,expected,at)!=1)throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);
            accounts.insertStatusOperation(operation,actor.user.tenantId,actor.user.id,targetId,digest,expected,expected+1,target.status,desired,reason,at,trace);accounts.statusAudit(at,actor.user.id,actor.user.loginNamespaceId,targetId,actor.user.tenantId,reason,expected,expected+1,trace);budget(deadline);if(!now().isBefore(actor.context.expiresAt))throw AuthFailure.invalid();
            return Map.of("operationId",operation,"userId",Long.toString(targetId),"status",desired,"securityVersion",Long.toString(expected+1));
        });budget(deadline);return result;
    }
    public Map<String,Object> employeeStatusOperation(String token,long targetId,String operation,long deadline){
        try{if(operation==null||!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new AuthFailure(400,"VALIDATION_ERROR",null);}
        Rows.Context located=lookup(token);if(located==null||!"AUTH".equals(located.kind))throw AuthFailure.invalid();
        var result=tx.execute(state->{Verified actor=lockAndValidate(located,deadline);if(!"CONFIRMED".equals(actor.context.deliveryState)||!"NORMAL".equals(actor.context.accessState)||actor.user.tenantId==null||!"EMPLOYEE".equals(actor.user.identityType)||!permissions(actor.user).contains("identity.employee.manage"))throw new AuthFailure(403,"ACCESS_DENIED",null);
            Rows.User target=revocations.user(targetId);if(target==null||!Objects.equals(target.tenantId,actor.user.tenantId)||!"EMPLOYEE".equals(target.identityType))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);var prior=accounts.statusOperation(operation);
            if(prior==null||((Number)prior.get("tenantId")).longValue()!=actor.user.tenantId||((Number)prior.get("actorUserId")).longValue()!=actor.user.id||((Number)prior.get("targetUserId")).longValue()!=targetId)throw new AuthFailure(404,"OPERATION_NOT_OBSERVED",null);
            budget(deadline);return Map.<String,Object>of("operationId",operation,"userId",Long.toString(targetId),"status",((Number)prior.get("newStatus")).intValue(),"securityVersion",prior.get("newSecurityVersion").toString());});budget(deadline);return result;
    }
    private void authorizeEmployeeManager(Verified actor){
        if(!"CONFIRMED".equals(actor.context.deliveryState)||!"NORMAL".equals(actor.context.accessState)||actor.user.tenantId==null||!"EMPLOYEE".equals(actor.user.identityType)||!permissions(actor.user).contains("identity.employee.manage"))throw new AuthFailure(403,"ACCESS_DENIED",null);
    }
    public Map<String,Object> createEmployee(String token,String csrf,String account,String displayName,String initialPassword,String operation,String trace,long deadline){
        String normalized=AuthCrypto.account(account);AuthCrypto.newPassword(initialPassword);
        if(displayName==null||displayName.isBlank()||!displayName.equals(displayName.strip())||AuthCrypto.codePoints(displayName)>50||!validReason(displayName))throw new AuthFailure(400,"VALIDATION_ERROR",null);
        try{if(operation==null||!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new AuthFailure(400,"VALIDATION_ERROR",null);}
        Verified first=verify(token,deadline,false);authorizeEmployeeManager(first);if(!crypto.csrfEquals(first.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
        String verifier=encoder.encode(initialPassword);Rows.Context located=first.context;
        try{Map<String,Object> result=tx.execute(state->{
            mapper.lockTenant(located.tenantId);mapper.lockUser(located.userId);Verified actor=lockAndValidate(located,deadline);authorizeEmployeeManager(actor);if(!crypto.csrfEquals(actor.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
            if(accounts.statusOperation(operation)!=null||accounts.scopeOperation(operation)!=null)throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT",null);if(accounts.employeeCreateOperation(operation)!=null)throw new AuthFailure(409,"OPERATION_ALREADY_OBSERVED",null);
            var user=new Rows.User();user.tenantId=actor.user.tenantId;user.accountNormalized=normalized;user.displayName=displayName;user.passwordVerifier=verifier;user.updatedAt=now();
            accounts.createEmployee(user);accounts.createEmployeeConfiguration(user.id,user.tenantId,user.updatedAt);accounts.insertEmployeeCreateOperation(operation,user.tenantId,actor.user.id,user.id,normalized,displayName,user.updatedAt,trace);accounts.employeeCreateAudit(user.updatedAt,actor.user.id,actor.user.loginNamespaceId,user.id,user.tenantId,trace);budget(deadline);if(!now().isBefore(actor.context.expiresAt))throw AuthFailure.invalid();
            return Map.<String,Object>of("operationId",operation,"userId",user.id.toString(),"account",normalized,"displayName",displayName,"mustChangePassword",true);
        });budget(deadline);return result;}catch(DuplicateKeyException e){throw new AuthFailure(409,"EMPLOYEE_ACCOUNT_OR_OPERATION_EXISTS",null);}
    }
    public Map<String,Object> employeeCreateOperation(String token,String operation,long deadline){
        try{if(operation==null||!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new AuthFailure(400,"VALIDATION_ERROR",null);}
        Rows.Context located=lookup(token);if(located==null||!"AUTH".equals(located.kind))throw AuthFailure.invalid();var result=tx.execute(state->{Verified actor=lockAndValidate(located,deadline);authorizeEmployeeManager(actor);var prior=accounts.employeeCreateOperation(operation);
            if(prior==null||((Number)prior.get("tenantId")).longValue()!=actor.user.tenantId||((Number)prior.get("actorUserId")).longValue()!=actor.user.id)throw new AuthFailure(404,"OPERATION_NOT_OBSERVED",null);
            budget(deadline);return Map.<String,Object>of("operationId",operation,"userId",prior.get("targetUserId").toString(),"account",prior.get("account"),"displayName",prior.get("displayName"),"mustChangePassword",true);});budget(deadline);return result;
    }
    private Map<String,Object> scopeSnapshot(long tenant,long id){var out=new LinkedHashMap<String,Object>();Long department=accounts.department(tenant,id);out.put("departmentId",department==null?null:department.toString());out.put("capabilities",accounts.activeCapabilities(id));out.put("companyIds",accounts.companyScope(tenant,id).stream().map(Object::toString).toList());out.put("assignmentDepartmentIds",accounts.assignmentScope(tenant,id).stream().map(Object::toString).toList());out.put("approvalDepartmentIds",accounts.approvalScope(tenant,id).stream().map(Object::toString).toList());out.put("reopenDepartmentIds",accounts.reopenScope(tenant,id).stream().map(Object::toString).toList());out.put("warehouseIds",accounts.warehouseScope(tenant,id).stream().map(Object::toString).toList());return out;}
    private Map<String,Object> scopeReceipt(Verified actor,long targetId,String operation,String digest){var prior=accounts.scopeOperation(operation);if(prior==null)return null;if(((Number)prior.get("tenantId")).longValue()!=actor.user.tenantId||((Number)prior.get("actorUserId")).longValue()!=actor.user.id||((Number)prior.get("targetUserId")).longValue()!=targetId||digest!=null&&!Objects.equals(digest,prior.get("requestDigest")))throw new AuthFailure(digest==null?404:409,digest==null?"OPERATION_NOT_OBSERVED":"IDEMPOTENCY_CONFLICT",null);return Map.of("operationId",operation,"userId",Long.toString(targetId),"securityVersion",prior.get("newSecurityVersion").toString());}
    public Map<String,Object> employeeScopeOperation(String token,long targetId,String operation,long deadline){
        try{if(operation==null||!UUID.fromString(operation).toString().equals(operation))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new AuthFailure(400,"VALIDATION_ERROR",null);}
        Rows.Context located=lookup(token);if(located==null||!"AUTH".equals(located.kind))throw AuthFailure.invalid();var result=tx.execute(state->{Verified actor=lockAndValidate(located,deadline);authorizeEmployeeManager(actor);Rows.User target=revocations.user(targetId);if(target==null||!Objects.equals(target.tenantId,actor.user.tenantId)||!"EMPLOYEE".equals(target.identityType))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);var receipt=scopeReceipt(actor,targetId,operation,null);if(receipt==null)throw new AuthFailure(404,"OPERATION_NOT_OBSERVED",null);budget(deadline);return receipt;});budget(deadline);return result;
    }
    public Map<String,Object> setEmployeeScope(String token,String csrf,long targetId,EmployeeScopeCommand command,String trace,long deadline,long wireDeadline){
        if(targetId<1||command==null)throw new AuthFailure(400,"VALIDATION_ERROR",null);Verified first=verify(token,deadline,false);authorizeEmployeeManager(first);if(!crypto.csrfEquals(first.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
        Rows.User candidate=accounts.detail(first.user.loginNamespaceId,targetId);if(candidate==null||!"EMPLOYEE".equals(candidate.identityType)||!Objects.equals(candidate.tenantId,first.user.tenantId))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
        String digest;try{digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(com.ias.trust.Chain.JSON.writeValueAsBytes(List.of("EMPLOYEE_SCOPE_V1",targetId,command))));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Map<String,Object> existing=tx.execute(state->{Verified actor=lockAndValidate(first.context,deadline);authorizeEmployeeManager(actor);if(!crypto.csrfEquals(actor.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);return scopeReceipt(actor,targetId,command.operationId(),digest);});if(existing!=null)return existing;
        scopeReferences.verify(first.user.tenantId,command.companyIds(),command.warehouseIds(),trace,wireDeadline);
        Map<String,Object> result=tx.execute(state->{mapper.lockTenant(first.user.tenantId);mapper.lockUser(Math.min(first.user.id,targetId));if(first.user.id!=targetId)mapper.lockUser(Math.max(first.user.id,targetId));Verified actor=lockAndValidate(first.context,deadline);authorizeEmployeeManager(actor);if(!crypto.csrfEquals(actor.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
            Rows.User target=revocations.user(targetId);if(target==null||!Objects.equals(target.tenantId,actor.user.tenantId)||!"EMPLOYEE".equals(target.identityType))throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            if(accounts.statusOperation(command.operationId())!=null||accounts.employeeCreateOperation(command.operationId())!=null)throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT",null);var prior=scopeReceipt(actor,targetId,command.operationId(),digest);if(prior!=null)return prior;
            if(targetId==actor.user.id||!Collections.disjoint(permissions(target),Set.of("identity.employee.manage","identity.session.revoke")))throw new AuthFailure(403,"ADMIN_TARGET_PROTECTED",null);if(target.securityVersion!=command.expectedSecurityVersion())throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);if(target.securityVersion==Long.MAX_VALUE)throw new AuthFailure(409,"SECURITY_VERSION_EXHAUSTED",null);
            var departments=new HashSet<Long>();if(command.departmentId()!=null)departments.add(command.departmentId());for(var list:List.of(command.assignmentDepartmentIds(),command.approvalDepartmentIds(),command.reopenDepartmentIds()))for(String id:list)departments.add(Long.parseLong(id));for(long id:departments)if(accounts.activeDepartment(actor.user.tenantId,id)!=1)throw new AuthFailure(404,"TARGET_NOT_ACCESSIBLE",null);
            String before=com.ias.trust.Chain.JSON.writeValueAsString(scopeSnapshot(actor.user.tenantId,targetId));var at=now();long tenant=actor.user.tenantId;
            accounts.clearEmployeeCapabilities(targetId,at);for(String cap:command.capabilities())accounts.enableEmployeeCapability(targetId,cap,at);
            accounts.clearCompanyScope(tenant,targetId);for(String id:command.companyIds())accounts.addCompanyScope(tenant,targetId,Long.parseLong(id));accounts.clearAssignmentScope(tenant,targetId);for(String id:command.assignmentDepartmentIds())accounts.addAssignmentScope(tenant,targetId,Long.parseLong(id));accounts.clearApprovalScope(tenant,targetId);for(String id:command.approvalDepartmentIds())accounts.addApprovalScope(tenant,targetId,Long.parseLong(id));accounts.clearReopenScope(tenant,targetId);for(String id:command.reopenDepartmentIds())accounts.addReopenScope(tenant,targetId,Long.parseLong(id));accounts.clearWarehouseScope(tenant,targetId);for(String id:command.warehouseIds())accounts.addWarehouseScope(tenant,targetId,Long.parseLong(id));
            if(accounts.changeEmployeeScope(tenant,targetId,command.departmentId(),command.expectedSecurityVersion(),at)!=1)throw new AuthFailure(409,"SECURITY_VERSION_CONFLICT",null);String after=com.ias.trust.Chain.JSON.writeValueAsString(scopeSnapshot(tenant,targetId));accounts.insertScopeOperation(command.operationId(),tenant,actor.user.id,targetId,digest,before,after,command.expectedSecurityVersion(),command.expectedSecurityVersion()+1,command.reason(),at,trace);accounts.scopeAudit(at,actor.user.id,actor.user.loginNamespaceId,targetId,tenant,command.reason(),command.expectedSecurityVersion(),command.expectedSecurityVersion()+1,trace);budget(deadline);if(!now().isBefore(actor.context.expiresAt))throw AuthFailure.invalid();return Map.<String,Object>of("operationId",command.operationId(),"userId",Long.toString(targetId),"securityVersion",Long.toString(command.expectedSecurityVersion()+1));});budget(deadline);return result;
    }
    private Map<String,Object> accountBody(Rows.User user,Rows.Tenant tenant) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("id",user.id.toString()); body.put("account",user.accountNormalized);body.put("displayName",user.displayName);
        body.put("identityType",user.identityType);body.put("status",user.status);body.put("mustChangePassword",user.mustChangePassword==1);
        body.put("bindingState",switch(user.bindingState){case 1 -> "PENDING";case 2 -> "BOUND";default -> "NOT_APPLICABLE";});
        body.put("securityVersion",Long.toString(user.securityVersion));body.put("updatedAt",utc(user.updatedAt));body.put("department",null);
        body.put("tenant",tenant==null ? null:Map.of("id",tenant.id.toString(),"code",tenant.codeNormalized,"name",tenant.name));
        return body;
    }
    private List<String> permissions(Rows.User user) {
        // Identity type is an additional eligibility gate, never a source of positive permissions.
        Set<String> allowed="PLATFORM".equals(user.realm) && "PLATFORM".equals(user.identityType) ? Set.of("identity.platform.session.revoke")
            : "TENANT".equals(user.realm) && "EMPLOYEE".equals(user.identityType) ? Set.of("identity.session.revoke","identity.employee.manage","asset.read","asset.manage","identity.customer.provision","ticket.read","ticket.accept","ticket.reject","ticket.assign","ticket.engineer.read","ticket.assignment.accept","ticket.record","ticket.solution.submit","ticket.close","ticket.supplement.request","ticket.cancel.review","ticket.transfer.request","ticket.reopen.review","ticket.respond","approval.parts.read","approval.parts.submit","approval.parts.review","stock.read","stock.manage","finance.quote.read","finance.quote.write","finance.quote.confirm","approval.fees.read","approval.fees.submit","approval.fees.review","approval.fees.finance","finance.money.read","finance.money.assess","finance.money.adjust","finance.money.collect","finance.money.reverse","finance.money.refund"):"TENANT".equals(user.realm)&&"CUSTOMER".equals(user.identityType)?Set.of("finance.quote.read","finance.quote.confirm"):Set.of();
        return allowed.isEmpty()?List.of():revocations.capabilities(user.id).stream().filter(allowed::contains).toList();
    }
    public Map<String,Object> introspect(String token,String operation,String csrf,long deadline){
        Verified v=verify(token,deadline,false);
        if(!"CONFIRMED".equals(v.context.deliveryState))throw new AuthFailure(403,"SESSION_CONFIRMATION_REQUIRED",null);
        if(!"NORMAL".equals(v.context.accessState)||v.user.mustChangePassword!=0)throw new AuthFailure(403,"ACCESS_DENIED",null);
        Set<String> supported=Set.of("ASSET_READ","ASSET_WRITE","PROVISION","PROVISION_READ","TICKET_READ","TICKET_CREATE","TICKET_DOWNLOAD","TICKET_WORK","TICKET_CUSTOMER_DECIDE","TICKET_CUSTOMER_SUPPLEMENT","TICKET_CUSTOMER_CANCEL","TICKET_CUSTOMER_REOPEN");
        if((!supported.contains(operation)&&!DomainPolicy.PURPOSES.contains(operation))||v.tenant==null)throw new AuthFailure(403,"ACCESS_DENIED",null);
        List<String> abilities=permissions(v.user);
        boolean read="ASSET_READ".equals(operation),provision=operation.startsWith("PROVISION");
        if(DomainPolicy.PURPOSES.contains(operation)){if(!DomainPolicy.allowed(v.user.identityType,abilities,operation))throw new AuthFailure(403,"ACCESS_DENIED",null);}
        else if(operation.startsWith("TICKET_")){if("CUSTOMER".equals(v.user.identityType)){if(operation.equals("TICKET_WORK"))throw new AuthFailure(403,"ACCESS_DENIED",null);}else if(!"EMPLOYEE".equals(v.user.identityType)||(operation.equals("TICKET_CREATE")||(operation.equals("TICKET_CUSTOMER_DECIDE")||operation.equals("TICKET_CUSTOMER_SUPPLEMENT")||(operation.equals("TICKET_CUSTOMER_CANCEL")||operation.equals("TICKET_CUSTOMER_REOPEN"))))||!(abilities.contains("ticket.read")||abilities.contains("ticket.engineer.read")))throw new AuthFailure(403,"ACCESS_DENIED",null);}
        else if("CUSTOMER".equals(v.user.identityType)){if(!read&&!operation.startsWith("NOTIFICATION_"))throw new AuthFailure(403,"ACCESS_DENIED",null);}
        else if(!abilities.contains(read?"asset.read":"asset.manage")||(provision&&!abilities.contains("identity.customer.provision")))throw new AuthFailure(403,"ACCESS_DENIED",null);
        if((DomainPolicy.write(operation)||operation.equals("ASSET_WRITE")||operation.equals("PROVISION")||operation.equals("TICKET_CREATE")||operation.equals("TICKET_DOWNLOAD")||operation.equals("TICKET_WORK")||(operation.equals("TICKET_CUSTOMER_DECIDE")||operation.equals("TICKET_CUSTOMER_SUPPLEMENT")||(operation.equals("TICKET_CUSTOMER_CANCEL")||operation.equals("TICKET_CUSTOMER_REOPEN"))))&&!crypto.csrfEquals(v.context.csrfToken,csrf))throw new AuthFailure(403,"CSRF_INVALID",null);
        Map<String,Object> result=new LinkedHashMap<>();result.put("userId",v.user.id.toString());result.put("tenantId",v.tenant.id.toString());result.put("identityType",v.user.identityType);result.put("permissions",abilities);result.put("securityVersion",Long.toString(v.user.securityVersion));result.put("securityEpoch",Long.toString(v.tenant.securityEpoch));result.put("accessState",v.context.accessState);result.put("attempt",v.context.loginAttemptId);result.put("checkedAt",Instant.now().toString());result.put("expiresAt",utc(v.context.expiresAt));return result;
    }
    private static boolean validReason(String reason) {
        for (int i=0;i<reason.length();i++) {
            char c=reason.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i>=reason.length() || !Character.isLowSurrogate(reason.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }
    private static byte[] revocationDigest(long target,long expected,String reason) {
        // V1: UTF-8, length-delimited reason after fixed operation and decimal integer fields.
        String canonical="1\nUSER_SESSIONS_REVOKE\n"+target+"\n"+expected+"\n"+reason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+":"+reason;
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Map<String,Object> operationBody(Rows.Operation operation) {
        return Map.of("operationId",operation.operationId.toString(),"targetUserId",operation.targetUserId.toString(),
            "revokedAt",utc(operation.revokedAt),"newSecurityVersion",Long.toString(operation.newSecurityVersion),"scope","ALL_SESSIONS");
    }
    private void audit(String action, Rows.User user, Rows.Context context, String result, String reason, String trace) {
        mapper.audit(now(),action,"USER",user.id,user.loginNamespaceId,"SESSION",context.id,user.tenantId,result,reason,context.loginAttemptId,trace);
    }
    private static String utc(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
    private Map<String,Object> identity(Verified verified) {
        Rows.Context context=verified.context; Rows.User user=verified.user; Rows.Tenant tenant=verified.tenant;
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("userId",user.id.toString()); body.put("account",user.accountNormalized); body.put("displayName",user.displayName);
        body.put("realm",user.realm); body.put("identityType",user.identityType);
        body.put("tenant",tenant==null ? null : Map.of("id",tenant.id.toString(),"code",tenant.codeNormalized,"name",tenant.name));
        body.put("department",null); // Department/RBAC sources are not implemented in this slice; never invent them.
        body.put("permissions","NORMAL".equals(context.accessState) ? permissions(user):List.of());
        body.put("accessState",context.accessState); body.put("loginAttemptId",context.loginAttemptId); body.put("expiresAt",utc(context.expiresAt));
        return body;
    }
}






