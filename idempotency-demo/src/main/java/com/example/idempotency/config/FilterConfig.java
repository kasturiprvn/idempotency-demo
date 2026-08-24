package com.example.idempotency.config;

import com.example.idempotency.filter.IdempotencyFilter;
import com.example.idempotency.store.IdempotencyRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyRepository repository) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new IdempotencyFilter(repository));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
