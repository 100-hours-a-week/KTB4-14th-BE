package com.audigo.global.migration.tracking;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;

// HTTP 요청에 작업 티켓을 묶고 요청이 끝나면 닫는다
public final class HttpWorkTickets {

    private static final String TICKET_ATTR = HttpWorkTickets.class.getName() + ".ticket";
    private static final String ASYNC_LISTENER_ATTR = HttpWorkTickets.class.getName() + ".asyncListener";

    private HttpWorkTickets() {
    }

    public static void bind(HttpServletRequest request, WorkTicket ticket) {
        WorkTicket previous = (WorkTicket) request.getAttribute(TICKET_ATTR);
        if (previous != null && previous != ticket) {
            throw new IllegalStateException("요청에 이미 작업 티켓이 묶여 있다: " + previous);
        }
        request.setAttribute(TICKET_ATTR, ticket);
    }

    public static WorkTicket bound(HttpServletRequest request) {
        return (WorkTicket) request.getAttribute(TICKET_ATTR);
    }

    // 묶인 티켓을 닫고 요청에서 제거한다. 여러 번 호출해도 된다
    public static void closeBound(HttpServletRequest request) {
        WorkTicket ticket = (WorkTicket) request.getAttribute(TICKET_ATTR);
        if (ticket == null) {
            return;
        }
        request.removeAttribute(TICKET_ATTR);
        ticket.close();
    }

    // 비동기 처리로 전환된 요청은 응답이 끝나는 시점(complete·error·timeout)에 닫는다
    public static void closeOnAsyncCompletion(HttpServletRequest request) {
        if (bound(request) == null || request.getAttribute(ASYNC_LISTENER_ATTR) != null) {
            return;
        }
        request.setAttribute(ASYNC_LISTENER_ATTR, Boolean.TRUE);
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                closeBound(request);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                closeBound(request);
            }

            @Override
            public void onError(AsyncEvent event) {
                closeBound(request);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 비동기 재시작 시 리스너가 해제되므로 다시 등록한다
                event.getAsyncContext().addListener(this);
            }
        });
    }
}
