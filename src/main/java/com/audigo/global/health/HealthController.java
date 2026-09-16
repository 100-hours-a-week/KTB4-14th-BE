package com.audigo.global.health;

import com.audigo.global.response.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.of("ok", Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
