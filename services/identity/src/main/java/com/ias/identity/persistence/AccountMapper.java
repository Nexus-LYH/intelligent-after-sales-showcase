package com.ias.identity.persistence;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AccountMapper {
    String FILTER="login_namespace_id=#{namespace} AND (#{status} IS NULL OR status=#{status}) AND (#{keyword} IS NULL OR account_normalized LIKE CONCAT('%',#{keyword},'%') ESCAPE '!' OR display_name LIKE CONCAT('%',#{keyword},'%') ESCAPE '!')";
    String FIELDS="id,tenant_id,realm,login_namespace_id,account_normalized,identity_type,display_name,status,must_change_password,binding_state,security_version,updated_at";
    @Select("SELECT COUNT(*) FROM identity_user WHERE "+FILTER)
    long count(@Param("namespace") String namespace,@Param("status") Integer status,@Param("keyword") String keyword);
    @Select("SELECT "+FIELDS+" FROM identity_user WHERE "+FILTER+" ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<Rows.User> users(@Param("namespace") String namespace,@Param("status") Integer status,@Param("keyword") String keyword,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT "+FIELDS+" FROM identity_user WHERE login_namespace_id=#{namespace} AND id=#{id}")
    Rows.User detail(@Param("namespace") String namespace,@Param("id") long id);
    @Select("SELECT capability_code FROM identity_user_capability WHERE user_id=#{id} AND enabled=1 ORDER BY capability_code")
    List<String> activeCapabilities(@Param("id") long id);
    @Select("SELECT company_id FROM identity_employee_company_scope WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1 ORDER BY company_id")
    List<Long> companyScope(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT department_id FROM identity_employee_department_scope WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1 ORDER BY department_id")
    List<Long> assignmentScope(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT department_id FROM identity_approval_department_scope WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1 ORDER BY department_id")
    List<Long> approvalScope(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT department_id FROM identity_reopen_department_scope WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1 ORDER BY department_id")
    List<Long> reopenScope(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT warehouse_id FROM identity_warehouse_scope WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1 ORDER BY warehouse_id")
    List<Long> warehouseScope(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT department_id FROM identity_user WHERE tenant_id=#{tenant} AND id=#{id}")
    Long department(@Param("tenant") long tenant,@Param("id") long id);
    @Select("SELECT tenant_id AS tenantId,actor_user_id AS actorUserId,target_user_id AS targetUserId,request_digest AS requestDigest,new_security_version AS newSecurityVersion,new_status AS newStatus FROM identity_employee_status_operation WHERE operation_id=#{operation}")
    java.util.Map<String,Object> statusOperation(@Param("operation") String operation);
    @Update("UPDATE identity_user SET status=#{status},security_version=security_version+1,row_version=row_version+1,updated_at=#{now} WHERE tenant_id=#{tenant} AND id=#{id} AND security_version=#{expected} AND security_version<9223372036854775807 AND row_version<9223372036854775807")
    int changeEmployeeStatus(@Param("tenant") long tenant,@Param("id") long id,@Param("status") int status,@Param("expected") long expected,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO identity_employee_status_operation(operation_id,tenant_id,actor_user_id,target_user_id,request_digest,expected_security_version,new_security_version,old_status,new_status,reason,occurred_at,trace_id) VALUES(#{operation},#{tenant},#{actor},#{target},#{digest},#{expected},#{next},#{before},#{after},#{reason},#{now},#{trace})")
    int insertStatusOperation(@Param("operation") String operation,@Param("tenant") long tenant,@Param("actor") long actor,@Param("target") long target,@Param("digest") String digest,@Param("expected") long expected,@Param("next") long next,@Param("before") int before,@Param("after") int after,@Param("reason") String reason,@Param("now") LocalDateTime now,@Param("trace") String trace);
    @Insert("INSERT INTO identity_security_audit(occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,reason,user_security_version_before,user_security_version_after,trace_id) VALUES(#{now},'EMPLOYEE_STATUS_CHANGED','USER',#{actor},#{namespace},'USER',#{target},#{tenant},'SUCCESS','MANUAL_EMPLOYEE_STATUS',#{reason},#{before},#{after},#{trace})")
    int statusAudit(@Param("now") LocalDateTime now,@Param("actor") long actor,@Param("namespace") String namespace,@Param("target") long target,@Param("tenant") long tenant,@Param("reason") String reason,@Param("before") long before,@Param("after") long after,@Param("trace") String trace);
    @Insert("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES('TENANT',#{tenantId},#{accountNormalized},'EMPLOYEE',#{displayName},#{passwordVerifier},1,1,0,#{updatedAt},#{updatedAt})")
    @Options(useGeneratedKeys=true,keyProperty="id") int createEmployee(Rows.User user);
    @Insert("INSERT INTO identity_employee_configuration(user_id,tenant_id,created_at,updated_at) VALUES(#{id},#{tenant},#{now},#{now})")
    int createEmployeeConfiguration(@Param("id") long id,@Param("tenant") long tenant,@Param("now") LocalDateTime now);
    @Select("SELECT tenant_id AS tenantId,actor_user_id AS actorUserId,target_user_id AS targetUserId,account_normalized AS account,display_name AS displayName FROM identity_employee_create_operation WHERE operation_id=#{operation}")
    java.util.Map<String,Object> employeeCreateOperation(@Param("operation") String operation);
    @Insert("INSERT INTO identity_employee_create_operation(operation_id,tenant_id,actor_user_id,target_user_id,account_normalized,display_name,occurred_at,trace_id) VALUES(#{operation},#{tenant},#{actor},#{target},#{account},#{name},#{now},#{trace})")
    int insertEmployeeCreateOperation(@Param("operation") String operation,@Param("tenant") long tenant,@Param("actor") long actor,@Param("target") long target,@Param("account") String account,@Param("name") String name,@Param("now") LocalDateTime now,@Param("trace") String trace);
    @Insert("INSERT INTO identity_security_audit(occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,trace_id) VALUES(#{now},'EMPLOYEE_CREATED','USER',#{actor},#{namespace},'USER',#{target},#{tenant},'SUCCESS','MANUAL_EMPLOYEE_CREATE',#{trace})")
    int employeeCreateAudit(@Param("now") LocalDateTime now,@Param("actor") long actor,@Param("namespace") String namespace,@Param("target") long target,@Param("tenant") long tenant,@Param("trace") String trace);
    @Select("SELECT COUNT(*) FROM identity_department WHERE tenant_id=#{tenant} AND id=#{id} AND enabled=1") long activeDepartment(@Param("tenant") long tenant,@Param("id") long id);
    @Update("UPDATE identity_user_capability SET enabled=0,row_version=row_version+1,updated_at=#{now} WHERE user_id=#{id} AND enabled=1 AND capability_code NOT IN ('identity.employee.manage','identity.session.revoke','identity.platform.session.revoke')") int clearEmployeeCapabilities(@Param("id") long id,@Param("now") LocalDateTime now);
    @Insert("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(#{id},#{capability},1,#{now},#{now}) ON DUPLICATE KEY UPDATE enabled=1,row_version=row_version+1,updated_at=#{now}") int enableEmployeeCapability(@Param("id") long id,@Param("capability") String capability,@Param("now") LocalDateTime now);
    @Update("UPDATE identity_employee_company_scope SET enabled=0 WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1") int clearCompanyScope(@Param("tenant") long tenant,@Param("id") long id);
    @Insert("INSERT INTO identity_employee_company_scope(tenant_id,user_id,company_id,enabled) VALUES(#{tenant},#{id},#{value},1) ON DUPLICATE KEY UPDATE enabled=1") int addCompanyScope(@Param("tenant") long tenant,@Param("id") long id,@Param("value") long value);
    @Update("UPDATE identity_employee_department_scope SET enabled=0 WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1") int clearAssignmentScope(@Param("tenant") long tenant,@Param("id") long id);
    @Insert("INSERT INTO identity_employee_department_scope(tenant_id,user_id,department_id,enabled) VALUES(#{tenant},#{id},#{value},1) ON DUPLICATE KEY UPDATE enabled=1") int addAssignmentScope(@Param("tenant") long tenant,@Param("id") long id,@Param("value") long value);
    @Update("UPDATE identity_approval_department_scope SET enabled=0 WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1") int clearApprovalScope(@Param("tenant") long tenant,@Param("id") long id);
    @Insert("INSERT INTO identity_approval_department_scope(tenant_id,user_id,department_id,enabled) VALUES(#{tenant},#{id},#{value},1) ON DUPLICATE KEY UPDATE enabled=1") int addApprovalScope(@Param("tenant") long tenant,@Param("id") long id,@Param("value") long value);
    @Update("UPDATE identity_reopen_department_scope SET enabled=0 WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1") int clearReopenScope(@Param("tenant") long tenant,@Param("id") long id);
    @Insert("INSERT INTO identity_reopen_department_scope(tenant_id,user_id,department_id,enabled) VALUES(#{tenant},#{id},#{value},1) ON DUPLICATE KEY UPDATE enabled=1") int addReopenScope(@Param("tenant") long tenant,@Param("id") long id,@Param("value") long value);
    @Update("UPDATE identity_warehouse_scope SET enabled=0 WHERE tenant_id=#{tenant} AND user_id=#{id} AND enabled=1") int clearWarehouseScope(@Param("tenant") long tenant,@Param("id") long id);
    @Insert("INSERT INTO identity_warehouse_scope(tenant_id,user_id,warehouse_id,enabled) VALUES(#{tenant},#{id},#{value},1) ON DUPLICATE KEY UPDATE enabled=1") int addWarehouseScope(@Param("tenant") long tenant,@Param("id") long id,@Param("value") long value);
    @Update("UPDATE identity_user SET department_id=#{department},security_version=security_version+1,row_version=row_version+1,updated_at=#{now} WHERE tenant_id=#{tenant} AND id=#{id} AND security_version=#{expected} AND security_version<9223372036854775807 AND row_version<9223372036854775807") int changeEmployeeScope(@Param("tenant") long tenant,@Param("id") long id,@Param("department") Long department,@Param("expected") long expected,@Param("now") LocalDateTime now);
    @Select("SELECT tenant_id AS tenantId,actor_user_id AS actorUserId,target_user_id AS targetUserId,request_digest AS requestDigest,new_security_version AS newSecurityVersion FROM identity_employee_scope_operation WHERE operation_id=#{operation}") java.util.Map<String,Object> scopeOperation(@Param("operation") String operation);
    @Insert("INSERT INTO identity_employee_scope_operation(operation_id,tenant_id,actor_user_id,target_user_id,request_digest,before_json,after_json,expected_security_version,new_security_version,reason,occurred_at,trace_id) VALUES(#{operation},#{tenant},#{actor},#{target},#{digest},#{before},#{after},#{expected},#{next},#{reason},#{now},#{trace})") int insertScopeOperation(@Param("operation") String operation,@Param("tenant") long tenant,@Param("actor") long actor,@Param("target") long target,@Param("digest") String digest,@Param("before") String before,@Param("after") String after,@Param("expected") long expected,@Param("next") long next,@Param("reason") String reason,@Param("now") LocalDateTime now,@Param("trace") String trace);
    @Insert("INSERT INTO identity_security_audit(occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,reason,user_security_version_before,user_security_version_after,trace_id) VALUES(#{now},'EMPLOYEE_SCOPE_CHANGED','USER',#{actor},#{namespace},'USER',#{target},#{tenant},'SUCCESS','MANUAL_EMPLOYEE_SCOPE',#{reason},#{before},#{after},#{trace})") int scopeAudit(@Param("now") LocalDateTime now,@Param("actor") long actor,@Param("namespace") String namespace,@Param("target") long target,@Param("tenant") long tenant,@Param("reason") String reason,@Param("before") long before,@Param("after") long after,@Param("trace") String trace);
    @Update("""
        UPDATE identity_user SET password_verifier=#{verifier},must_change_password=0,security_version=security_version+1,
        row_version=row_version+1,updated_at=#{now} WHERE id=#{id} AND security_version=#{expected} AND security_version<9223372036854775807
        """)
    int password(@Param("id") long id,@Param("expected") long expected,@Param("verifier") String verifier,@Param("now") LocalDateTime now);
    @Insert("""
        INSERT INTO identity_security_audit(occurred_at,action,actor_type,actor_user_id,actor_namespace_id,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,trace_id)
        VALUES(#{now},'PASSWORD_CHANGE','USER',#{user.id},#{user.loginNamespaceId},'USER',#{user.id},#{user.tenantId},'SUCCESS','SELF_SERVICE',#{user.securityVersion},#{after},#{trace})
        """)
    int passwordAudit(@Param("user") Rows.User user,@Param("after") long after,@Param("now") LocalDateTime now,@Param("trace") String trace);
}
