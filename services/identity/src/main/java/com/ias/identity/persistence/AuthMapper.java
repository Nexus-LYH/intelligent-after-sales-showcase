package com.ias.identity.persistence;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface AuthMapper {
    @Select("SELECT * FROM tenant WHERE code_normalized=#{code}")
    Rows.Tenant tenantByCode(String code);
    @Select("SELECT * FROM tenant WHERE id=#{id} FOR UPDATE")
    Rows.Tenant lockTenant(Long id);
    @Select("SELECT * FROM identity_user WHERE login_namespace_id=#{namespace} AND account_normalized=#{account}")
    Rows.User userByAccount(@Param("namespace") String namespace, @Param("account") String account);
    @Select("SELECT * FROM identity_user WHERE id=#{id} FOR UPDATE")
    Rows.User lockUser(Long id);
    @Select("SELECT * FROM identity_session_context WHERE token_hash=#{hash}")
    Rows.Context contextByHash(byte[] hash);
    @Select("SELECT * FROM identity_session_context WHERE id=#{id}")
    Rows.Context contextById(Long id);
    @Select("SELECT * FROM identity_session_context WHERE id=#{id} FOR UPDATE")
    Rows.Context lockContext(Long id);
    @Select("SELECT * FROM identity_login_attempt WHERE attempt_id=#{id} FOR UPDATE")
    Rows.Attempt lockAttempt(String id);
    @Insert("""
        INSERT INTO identity_session_context
        (token_hash,kind,user_id,tenant_id,access_state,issued_user_security_version,issued_tenant_security_epoch,
        issued_at,expires_at,preauth_state,delivery_state,delivery_confirm_deadline,csrf_token,login_attempt_id,updated_at)
        VALUES (#{tokenHash},#{kind},#{userId},#{tenantId},#{accessState},#{issuedUserSecurityVersion},#{issuedTenantSecurityEpoch},
        #{issuedAt},#{expiresAt},#{preauthState},#{deliveryState},#{deliveryConfirmDeadline},#{csrfToken},#{loginAttemptId},#{updatedAt})
        """)
    @Options(useGeneratedKeys=true, keyProperty="id")
    int insertContext(Rows.Context context);
    @Update("""
        UPDATE identity_session_context SET preauth_state=#{state}, consumed_at=#{consumed},
        row_version=row_version+1,updated_at=#{now} WHERE id=#{id}
        """)
    int preauthState(@Param("id") Long id, @Param("state") String state, @Param("consumed") LocalDateTime consumed, @Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO identity_login_attempt (attempt_id,preauth_context_id,expected_realm,expected_tenant_code_normalized,
        expected_account_normalized,status,created_at,retain_until)
        VALUES (#{attemptId},#{preauthContextId},#{expectedRealm},#{expectedTenantCodeNormalized},#{expectedAccountNormalized},'PROCESSING',#{createdAt},#{retainUntil})
        """)
    int insertAttempt(Rows.Attempt attempt);
    @Update("""
        UPDATE identity_login_attempt SET status=#{state},failure_code=#{failure},completed_at=#{now},
        retain_until=GREATEST(retain_until,#{retain}),row_version=row_version+1 WHERE attempt_id=#{id} AND status='PROCESSING'
        """)
    int finishAttempt(@Param("id") String id, @Param("state") String state, @Param("failure") String failure,
                      @Param("now") LocalDateTime now, @Param("retain") LocalDateTime retain);
    @Update("""
        UPDATE identity_session_context SET delivery_state='CONFIRMED',token_hash=#{replacementHash},confirmed_at=#{now},updated_at=#{now},row_version=row_version+1
        WHERE id=#{id} AND token_hash=#{expectedHash} AND delivery_state='UNCONFIRMED' AND revoked_at IS NULL AND delivery_confirm_deadline>#{now} AND expires_at>#{now}
        """)
    int confirm(@Param("id") Long id, @Param("now") LocalDateTime now,@Param("expectedHash") byte[] expectedHash,@Param("replacementHash") byte[] replacementHash);
    @Update("""
        UPDATE identity_session_context SET revoked_at=#{now},revoke_reason_code='LOGOUT',updated_at=#{now},row_version=row_version+1
        WHERE id=#{id} AND revoked_at IS NULL
        """)
    int revoke(@Param("id") Long id, @Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO identity_security_audit
        (occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,login_attempt_id,trace_id)
        VALUES (#{now},#{action},#{actorType},#{userId},#{namespace},#{targetType},#{targetId},#{tenantId},#{result},#{reason},#{attemptId},#{trace})
        """)
    int audit(@Param("now") LocalDateTime now, @Param("action") String action, @Param("actorType") String actorType,
              @Param("userId") Long userId, @Param("namespace") String namespace, @Param("targetType") String targetType,
              @Param("targetId") Long targetId, @Param("tenantId") Long tenantId, @Param("result") String result,
              @Param("reason") String reason, @Param("attemptId") String attemptId, @Param("trace") String trace);
}
