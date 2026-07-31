package com.osr.framework.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.osr.framework.interceptor.ApiInterceptor;

/**
 * 通用配置
 * 
 * @author osr
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer
{
    /**
     * 首页地址
     */
    @Value("${user.indexUrl:/index}")
    private String indexUrl;

    @Autowired
    private ApiInterceptor apiInterceptor;

    /**
     * 默认首页的设置，当输入域名是可以自动跳转到默认指定的网页
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry)
    {
        registry.addViewController("/").setViewName("forward:" + indexUrl);
    }

    /**
     * 自定义拦截规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(apiInterceptor)
            .addPathPatterns("/**")
            .excludePathPatterns("/css/**", "/js/**", "/img/**", "/fonts/**", "/favicon.ico", "/service-worker.js", "/manifest.json");
    }
}