package com.example.lotterysystem.common.config;

import com.example.lotterysystem.common.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

@Configuration
public class AppConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;

    private final List<String> excludes = Arrays.asList(
            "/**/*.html",
            "/css/**",
            "/js/**",
            "/pic/**",
            "/*.jpg",
            "/*.png",
            "/favicon.ico",
            "/**/login",
            "/register",
            "/verification-code/send",
            "/winning-records/show"
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(excludes);
    }
}
