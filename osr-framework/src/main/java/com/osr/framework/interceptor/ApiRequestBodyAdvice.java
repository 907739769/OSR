package com.osr.framework.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@RestControllerAdvice
public class ApiRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final Logger log = LoggerFactory.getLogger(ApiRequestBodyAdvice.class);
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>(Arrays.asList(
        "password", "secret", "token", "code"
    ));

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    public Object afterBodyRead(Object body, MethodParameter returnType, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType,
                                HttpInputMessage inputMessage) {
        if (body instanceof String) {
            String json = (String) body;
            String masked = maskSensitiveFields(json);
            log.debug("[API Body] {}", masked);
        }
        return body;
    }

    private String maskSensitiveFields(String json) {
        for (String field : SENSITIVE_FIELDS) {
            String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
            json = json.replaceAll(pattern, "\"" + field + "\":\"***MASKED***\"");
        }
        return json;
    }
}
