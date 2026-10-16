package com.audigo.global.migration.classify;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

// 컷오버 중 보류 여부를 정하기 위한 요청 분류. @PureRead가 없으면 WRITE로 본다
@Component
public class WriteRequestClassifier {

    private static final List<String> BYPASS_PATHS = List.of("/health", "/error");
    private static final List<String> BYPASS_PREFIXES = List.of("/internal/", "/actuator/");

    private final Map<Method, RequestKind> kindByMethod = new ConcurrentHashMap<>();

    public RequestKind classify(HttpServletRequest request, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || isBypassPath(pathOf(request))) {
            return RequestKind.BYPASS;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // 매핑된 컨트롤러가 없으면 서비스까지 도달하지 않는다
            return RequestKind.BYPASS;
        }
        return classify(handlerMethod);
    }

    public RequestKind classify(HandlerMethod handlerMethod) {
        Class<?> beanType = handlerMethod.getBeanType();
        return kindByMethod.computeIfAbsent(
                handlerMethod.getMethod(),
                method -> isPureRead(method, beanType) ? RequestKind.PURE_READ : RequestKind.WRITE
        );
    }

    private boolean isPureRead(Method method, Class<?> beanType) {
        return AnnotatedElementUtils.hasAnnotation(method, PureRead.class)
                || AnnotatedElementUtils.hasAnnotation(beanType, PureRead.class);
    }

    private boolean isBypassPath(String path) {
        if (BYPASS_PATHS.contains(path)) {
            return true;
        }
        return path.equals("/internal") || path.equals("/actuator")
                || BYPASS_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
