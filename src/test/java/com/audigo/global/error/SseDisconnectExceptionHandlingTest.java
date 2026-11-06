package com.audigo.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.audigo.global.logging.RequestLoggingFilter;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(OutputCaptureExtension.class)
class SseDisconnectExceptionHandlingTest {

    private TestController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestLoggingFilter())
                .build();
    }

    @Test
    void servlet_container_disconnect_keeps_committed_stream_without_json_or_500_log(CapturedOutput output)
            throws Exception {
        MvcResult opened = openStream();
        MockAsyncContext context = (MockAsyncContext) opened.getRequest().getAsyncContext();

        // Exercise Spring's real container error callback, which wraps this IOException.
        for (AsyncListener listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, new IOException("socket closed")));
        }

        mockMvc.perform(asyncDispatch(opened)).andExpect(status().isOk())
                .andExpect(content().string("event:connected\ndata:ready\n\n"));
        assertThat(controller.disconnected).isTrue();
        assertThat(output.getAll()).contains("async_client_disconnected", "request_id=sse-disconnect-test",
                        "cause_type=java.io.IOException")
                .containsPattern("(?m)^.*\\bINFO\\b.*async_client_disconnected")
                .doesNotContain("unexpected_exception", "status=500", "internal_server_error");
    }

    @Test
    void broken_pipe_during_async_write_is_a_disconnect(CapturedOutput output) throws Exception {
        assertDisconnected(new SocketException("Broken pipe"), output);
    }

    @Test
    void connection_reset_during_async_write_is_a_disconnect(CapturedOutput output) throws Exception {
        assertDisconnected(new SocketException("Connection reset by peer"), output);
    }

    @Test
    void eof_during_async_write_is_a_disconnect(CapturedOutput output) throws Exception {
        assertDisconnected(new EOFException("peer closed"), output);
    }

    @Test
    void unknown_async_response_failure_stays_error_and_never_writes_json(CapturedOutput output) throws Exception {
        MvcResult opened = openStream();
        controller.emitter.completeWithError(new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write", new IOException("unknown response failure")));

        mockMvc.perform(asyncDispatch(opened)).andExpect(status().isOk())
                .andExpect(content().string("event:connected\ndata:ready\n\n"));
        assertThat(output.getAll()).contains("async_response_unusable", "request_id=sse-disconnect-test",
                        "unknown response failure")
                .containsPattern("(?m)^.*\\bERROR\\b.*async_response_unusable")
                .doesNotContain("async_client_disconnected", "unexpected_exception");
    }

    @Test
    void redis_cause_inside_unusable_response_is_not_misclassified_as_client_disconnect(CapturedOutput output)
            throws Exception {
        MvcResult opened = openStream();
        controller.emitter.completeWithError(new AsyncRequestNotUsableException("response failed",
                new RedisConnectionFailureException("Redis unavailable", new IOException("Broken pipe"))));

        mockMvc.perform(asyncDispatch(opened)).andExpect(status().isOk())
                .andExpect(content().string("event:connected\ndata:ready\n\n"));
        assertThat(output.getAll()).containsPattern("(?m)^.*\\bERROR\\b.*async_response_unusable")
                .doesNotContain("async_client_disconnected");
    }

    @Test
    void redis_failure_with_broken_pipe_is_still_an_internal_error(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/failure").param("type", "redis"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"message\":\"internal_server_error\",\"data\":null}"));
        assertThat(output.getAll()).contains("unexpected_exception", "status=500")
                .containsPattern("(?m)^.*\\bERROR\\b.*unexpected_exception")
                .doesNotContain("async_client_disconnected");
    }

    @Test
    void ordinary_io_failure_is_still_an_internal_error(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/failure").param("type", "io"))
                .andExpect(status().isInternalServerError());
        assertThat(output.getAll()).contains("unexpected_exception", "status=500")
                .doesNotContain("async_client_disconnected");
    }

    @Test
    void application_failure_is_still_an_internal_error(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/test/failure").param("type", "application"))
                .andExpect(status().isInternalServerError());
        assertThat(output.getAll()).contains("unexpected_exception", "status=500")
                .doesNotContain("async_client_disconnected");
    }

    private MvcResult openStream() throws Exception {
        MvcResult opened = mockMvc.perform(get("/api/notifications/subscribe")
                        .header(RequestLoggingFilter.REQUEST_ID_HEADER, "sse-disconnect-test"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        assertThat(opened.getResponse().isCommitted()).isTrue();
        return opened;
    }

    private void assertDisconnected(IOException cause, CapturedOutput output) throws Exception {
        MvcResult opened = openStream();
        controller.emitter.completeWithError(new AsyncRequestNotUsableException(
                "ServletOutputStream failed to write", cause));
        mockMvc.perform(asyncDispatch(opened)).andExpect(status().isOk())
                .andExpect(content().string("event:connected\ndata:ready\n\n"));
        assertThat(output.getAll()).contains("async_client_disconnected", "request_id=sse-disconnect-test")
                .containsPattern("(?m)^.*\\bINFO\\b.*async_client_disconnected")
                .doesNotContain("unexpected_exception", "status=500", "internal_server_error");
    }

    @RestController
    static class TestController {
        private SseEmitter emitter;
        private boolean disconnected;

        @GetMapping(value = "/api/notifications/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe() throws IOException {
            emitter = new SseEmitter(30_000L);
            emitter.onError(error -> disconnected = true);
            emitter.send(SseEmitter.event().name("connected").data("ready"));
            return emitter;
        }

        @GetMapping("/test/failure")
        String failure(@RequestParam String type) throws IOException {
            if ("redis".equals(type)) throw new RedisConnectionFailureException("Broken pipe");
            if ("io".equals(type)) throw new IOException("storage failure");
            throw new IllegalStateException("application failure");
        }
    }
}
