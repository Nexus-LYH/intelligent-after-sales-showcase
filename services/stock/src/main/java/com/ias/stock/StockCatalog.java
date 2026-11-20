package com.ias.stock;

import com.ias.trust.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;

/** Current tenant catalogue. Engineer selectors contain no stock quantities or other-tenant objects. */
public final class StockCatalog {
    private final JdbcTemplate db;
    public StockCatalog(JdbcTemplate db){this.db=db;}
    public Map<String,Object> warehouseCatalogue(StockAccess.Principal actor,int page,int size){
        if(actor.warehouseIds().isEmpty())return page(List.of(),0L,page,size);
        var ids=new TreeSet<>(actor.warehouseIds());var args=new ArrayList<Object>();args.add(actor.tenantId());args.addAll(ids);
        String where="tenant_id=? AND active=TRUE AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        long total=db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE "+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var items=db.query("SELECT id,name FROM stock_warehouse WHERE "+where+" ORDER BY id LIMIT ? OFFSET ?",(r,n)->Map.<String,Object>of("id",Long.toString(r.getLong(1)),"name",r.getString(2)),args.toArray());return page(items,total,page,size);
    }
    public Map<String,Object> warehouseParts(StockAccess.Principal actor,long warehouse,int page,int size){
        if(!actor.warehouseIds().contains(warehouse))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return parts(actor.tenantId(),warehouse,page,size);
    }
    public Map<String,Object> warehouses(long tenant,int page,int size) {
        long total=db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND active=TRUE",Long.class,tenant);
        var items=db.query("SELECT id,name FROM stock_warehouse WHERE tenant_id=? AND active=TRUE ORDER BY id LIMIT ? OFFSET ?",(r,n)->Map.<String,Object>of("id",r.getLong(1)+"","name",r.getString(2)),tenant,size,(page-1)*size);
        return page(items,total,page,size);
    }
    public Map<String,Object> parts(long tenant,long warehouse,int page,int size) {
        warehouse(tenant,warehouse);
        long total=db.queryForObject("SELECT COUNT(*) FROM stock_part WHERE tenant_id=? AND active=TRUE",Long.class,tenant);
        var items=db.query("SELECT id,code_normalized,name,model,unit FROM stock_part WHERE tenant_id=? AND active=TRUE ORDER BY id LIMIT ? OFFSET ?",(r,n)->Map.<String,Object>of("id",r.getLong(1)+"","code",r.getString(2),"name",r.getString(3),"model",r.getString(4),"unit",r.getString(5)),tenant,size,(page-1)*size);
        return page(items,total,page,size);
    }
    public Map<String,Object> balances(StockAccess.Principal actor,long warehouse,Long part,int page,int size) {
        if(!actor.warehouseIds().contains(warehouse))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
        warehouse(actor.tenantId(),warehouse);
        String where="tenant_id=? AND warehouse_id=?"+(part==null?"":" AND part_id=?");
        List<Object> args=new ArrayList<>(List.of(actor.tenantId(),warehouse));if(part!=null)args.add(part);
        long total=db.queryForObject("SELECT COUNT(*) FROM stock_balance WHERE "+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=db.query("SELECT part_id,on_hand,reserved,row_version FROM stock_balance WHERE "+where+" ORDER BY part_id LIMIT ? OFFSET ?",(r,n)->Map.<String,Object>of("partId",r.getLong(1)+"","onHand",r.getLong(2)+"","reserved",r.getLong(3)+"","available",Math.subtractExact(r.getLong(2),r.getLong(3))+"","rowVersion",r.getLong(4)+""),args.toArray());
        return page(items,total,page,size);
    }
    public Map<String,Object> approval(StockAccess.Principal actor,long warehouse,long part) {
        warehouse(actor.tenantId(),warehouse);
        if(db.queryForObject("SELECT COUNT(*) FROM stock_part WHERE tenant_id=? AND id=? AND active=TRUE",Long.class,actor.tenantId(),part)!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
        return Map.of("tenantId",actor.tenantId()+"","userId",actor.userId()+"","warehouseId",warehouse+"","partId",part+"","warehouseActive",true,"partActive",true,"checkedAt",Instant.now().toString());
    }
    private void warehouse(long tenant,long warehouse){if(db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND id=? AND active=TRUE",Long.class,tenant,warehouse)!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
    private static Map<String,Object> page(List<Map<String,Object>> items,long total,int page,int size){return Map.of("items",items,"total",total,"page",page,"size",size);}
}
