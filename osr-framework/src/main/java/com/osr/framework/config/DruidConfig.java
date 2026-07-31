package com.osr.framework.config;

import java.io.IOException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.osr.framework.config.properties.DruidProperties;

/**
 * druid 配置多数据源
 * 
 * @author osr
 */
@Configuration
@EnableTransactionManagement
public class DruidConfig
{
    @Bean
    @ConfigurationProperties("spring.datasource.druid.master")
    public DataSource masterDataSource(DruidProperties druidProperties)
    {
        DruidDataSource dataSource = new DruidDataSource();
        return druidProperties.dataSource(dataSource);
    }

    /**
     * 事务管理器
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dynamicDataSource)
    {
        return new DataSourceTransactionManager(dynamicDataSource);
    }

    /**
     * 去除监控页面底部的广告
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Bean
    @ConditionalOnProperty(name = "spring.datasource.druid.statViewServlet.enabled", havingValue = "true")
    public FilterRegistrationBean removeDruidFilterRegistrationBean()
    {
        String pattern = "/druid/*";
        String commonJsPattern = pattern.replaceAll("\\*", "js/common.js");
        final String filePath = "support/http/resources/js/common.js";
        Filter filter = new Filter()
        {
            @Override
            public void init(jakarta.servlet.FilterConfig filterConfig) throws ServletException { }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException
            {
                chain.doFilter(request, response);
                response.resetBuffer();
                try
                {
                    byte[] bytes = new ClassPathResource(filePath).getInputStream().readAllBytes();
                    String text = new String(bytes, "UTF-8");
                    text = text.replaceAll("<a.*?banner\"></a><br/>", "");
                    text = text.replaceAll("powered.*?shrek.wang</a>", "");
                    response.getWriter().write(text);
                }
                catch (IOException e)
                {
                    response.getWriter().write("");
                }
            }

            @Override
            public void destroy() { }
        };
        FilterRegistrationBean registrationBean = new FilterRegistrationBean();
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns(commonJsPattern);
        return registrationBean;
    }
}
