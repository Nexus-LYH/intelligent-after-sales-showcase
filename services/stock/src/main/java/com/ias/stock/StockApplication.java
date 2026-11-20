package com.ias.stock;
import com.ias.trust.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class StockApplication {
    public static void main(String[] args){if(!Chain.active()||!"stock".equals(Chain.role()))throw new IllegalStateException("Stock private TLS configuration required");SpringApplication.run(StockApplication.class,args);}
    @Bean FilterRegistrationBean<EdgeFilter> stockEdges(){var f=new FilterRegistrationBean<>(new EdgeFilter("stock"));f.setOrder(-200);return f;}
    @Bean StockCatalog stockCatalog(JdbcTemplate jdbc){return new StockCatalog(jdbc);}
    @Bean @ConditionalOnMissingBean(StockAccess.class) StockAccess stockAccess(){return Chain.active()?new TrustedStockAccess():new StockAccess.Unavailable();}
    @Bean org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer stockJson(){return builder->builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION);}
}
