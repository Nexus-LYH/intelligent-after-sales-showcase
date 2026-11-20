package com.ias.identity.persistence;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RevocationMapper {
    @Select("SELECT * FROM identity_user WHERE id=#{id}")
    Rows.User user(Long id);
    @Select("SELECT capability_code FROM identity_user_capability WHERE user_id=#{id} AND enabled=1 ORDER BY capability_code")
    List<String> capabilities(Long id);
    @Select("""
        SELECT * FROM identity_revocation_operation
        WHERE actor_namespace_id=#{namespace} AND actor_user_id=#{actor} AND operation_type='USER_SESSIONS_REVOKE' AND idempotency_key=#{key}
        """)
    Rows.Operation operation(@Param("namespace") String namespace,@Param("actor") Long actor,@Param("key") String key);
    @Update("""
        UPDATE identity_user SET security_version=security_version+1,row_version=row_version+1,updated_at=#{now}
        WHERE id=#{id} AND security_version=#{expected} AND security_version<9223372036854775807
        """)
    int advance(@Param("id") Long id,@Param("expected") long expected,@Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO identity_revocation_operation
        (actor_user_id,actor_namespace_id,operation_type,idempotency_key,target_user_id,request_digest,digest_schema_version,
        expected_security_version,new_security_version,reason,scope,result_code,revoked_at,trace_id)
        VALUES (#{actorUserId},#{actorNamespaceId},'USER_SESSIONS_REVOKE',#{idempotencyKey},#{targetUserId},#{requestDigest},1,
        #{expectedSecurityVersion},#{newSecurityVersion},#{reason},'ALL_SESSIONS','REVOKED',#{revokedAt},#{traceId})
        """)
    @Options(useGeneratedKeys=true,keyProperty="operationId")
    int insert(Rows.Operation operation);
    @Insert("""
        INSERT INTO identity_security_audit
        (occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,reason,
        user_security_version_before,user_security_version_after,revocation_operation_id,trace_id)
        VALUES (#{operation.revokedAt},'USER_SESSIONS_REVOKE','USER',#{operation.actorUserId},#{operation.actorNamespaceId},'USER',
        #{operation.targetUserId},#{tenant},'SUCCESS','MANUAL_REVOCATION',#{operation.reason},#{operation.expectedSecurityVersion},
        #{operation.newSecurityVersion},#{operation.operationId},#{operation.traceId})
        """)
    int audit(@Param("operation") Rows.Operation operation,@Param("tenant") Long tenant);
}
