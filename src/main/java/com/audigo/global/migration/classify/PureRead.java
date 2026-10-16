package com.audigo.global.migration.classify;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// DB 쓰기가 전혀 없는 핸들러 표시. 표시가 없으면 컷오버 중 쓰기로 보고 보류한다
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PureRead {

    // 순수 읽기로 판단한 근거 (리뷰용)
    String reason() default "";
}
