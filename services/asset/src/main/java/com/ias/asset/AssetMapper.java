package com.ias.asset;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AssetMapper {
 @Select("SELECT id FROM asset_company WHERE tenant_id=#{tenant} AND id=#{id} FOR UPDATE")
 Long lockCompany(@Param("tenant")long tenant,@Param("id")long id);
 @Select("SELECT id FROM asset_model WHERE tenant_id=#{tenant} AND id=#{id} FOR UPDATE")
 Long lockModel(@Param("tenant")long tenant,@Param("id")long id);
}
