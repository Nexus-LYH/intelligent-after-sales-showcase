package com.ias.stock;

import com.ias.trust.Chain;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Operator-only, one initial catalogue per tenant; IDs are generated here, never chosen by browsers. */
public final class CatalogInitialization {
    public record Part(String code,String name,String model,String unit,long safetyStock) {
        public Part {if(code==null||!code.matches("[A-Z0-9][A-Z0-9_-]{0,63}")||safetyStock<0)throw new IllegalArgumentException("INVALID_PART");text(name,100);text(model,100);text(unit,20);}
    }
    public record Plan(UUID operationId,List<String> warehouses,List<Part> parts) {
        public Plan {Objects.requireNonNull(operationId);warehouses=List.copyOf(warehouses);parts=List.copyOf(parts);if(warehouses.isEmpty()||warehouses.size()>100||parts.isEmpty()||parts.size()>1000)throw new IllegalArgumentException("INVALID_CATALOG_SIZE");warehouses.forEach(n->text(n,100));if(new HashSet<>(warehouses).size()!=warehouses.size()||parts.stream().map(Part::code).distinct().count()!=parts.size())throw new IllegalArgumentException("DUPLICATE_CATALOG_ENTRY");}
    }
    static void text(String value,int limit){if(value==null||value.isBlank()||!value.equals(value.strip())||value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("INVALID_CATALOG_TEXT");}
    private static final SecureRandom RANDOM=new SecureRandom();
    private static long nextId(){return RANDOM.nextLong(1,Long.MAX_VALUE);}
    @SuppressWarnings("unchecked")
    public Map<String,Object> initialize(Connection db,long permittedTenantId,String operator,Plan plan)throws Exception {
        if(permittedTenantId<=0)throw new IllegalArgumentException("INVALID_OPERATOR_TENANT");text(operator,100);
        if(!db.getAutoCommit())throw new IllegalArgumentException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsString(plan).getBytes(StandardCharsets.UTF_8)));
        db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
        boolean commitAttempted=false;
        try {
            try(var q=db.prepareStatement("SELECT operation_id,request_hash,receipt_json FROM stock_catalog_initialization WHERE tenant_id=? FOR UPDATE")) {
                q.setLong(1,permittedTenantId);try(var rs=q.executeQuery()){if(rs.next()){
                    if(!rs.getString(1).equals(plan.operationId().toString())||!rs.getString(2).equals(hash))throw new IllegalStateException("CATALOG_INITIALIZATION_CONFLICT");
                    var receipt=Chain.JSON.readValue(rs.getString(3),Map.class);commitAttempted=true;db.commit();return receipt;
                }}
            }
            // The tenant PK serializes competing first attempts; a loser rolls back and retries the same plan.
            try(var q=db.prepareStatement("INSERT INTO stock_catalog_initialization VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(3))")){q.setLong(1,permittedTenantId);q.setString(2,plan.operationId().toString());q.setString(3,hash);q.setString(4,operator);q.setString(5,"{}");q.executeUpdate();}
            var warehouses=new ArrayList<Map<String,String>>();var parts=new ArrayList<Map<String,String>>();
            for(String name:plan.warehouses()){
                long id=nextId();try(var q=db.prepareStatement("INSERT INTO stock_warehouse(id,tenant_id,name,active) VALUES(?,?,?,TRUE)")){q.setLong(1,id);q.setLong(2,permittedTenantId);q.setString(3,name);q.executeUpdate();}warehouses.add(Map.of("id",Long.toString(id),"name",name));
            }
            for(Part part:plan.parts()){
                long id=nextId();try(var q=db.prepareStatement("INSERT INTO stock_part VALUES(?,?,?,?,?,?,TRUE,?)")){q.setLong(1,id);q.setLong(2,permittedTenantId);q.setString(3,part.code());q.setString(4,part.name());q.setString(5,part.model());q.setString(6,part.unit());q.setLong(7,part.safetyStock());q.executeUpdate();}parts.add(Map.of("id",Long.toString(id),"code",part.code()));
            }
            Map<String,Object> receipt=Map.of("operationId",plan.operationId().toString(),"tenantId",Long.toString(permittedTenantId),"warehouses",warehouses,"parts",parts);
            try(var q=db.prepareStatement("UPDATE stock_catalog_initialization SET receipt_json=? WHERE tenant_id=?")){q.setString(1,Chain.JSON.writeValueAsString(receipt));q.setLong(2,permittedTenantId);if(q.executeUpdate()!=1)throw new SQLException("CATALOG_RECEIPT_WRITE_FAILED");}
            commitAttempted=true;db.commit();return receipt;
        } catch(Exception failure){
            boolean uncertain=commitAttempted;try{db.rollback();}catch(SQLException rollback){uncertain=true;failure.addSuppressed(rollback);}
            if(uncertain){try{db.abort(Runnable::run);}catch(SQLException abort){failure.addSuppressed(abort);} }
            // This API is exclusively for a dedicated physical CLI connection; caller must always discard it.
            throw failure;
        }
    }
}
