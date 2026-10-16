package com.audigo.global.migration.tracking;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 요청에 묶인 작업 티켓의 안전망. 인터셉터의 afterCompletion이 호출되지 않는 경로에서도 티켓을 닫는다
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkTicketCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                HttpWorkTickets.closeOnAsyncCompletion(request);
            } else {
                HttpWorkTickets.closeBound(request);
            }
        }
    }

    // 재개된 요청은 ASYNC 디스패치에서 티켓을 새로 받으므로 그때도 정리한다
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
