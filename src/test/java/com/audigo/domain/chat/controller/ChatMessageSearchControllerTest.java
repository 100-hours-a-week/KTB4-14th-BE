package com.audigo.domain.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.audigo.domain.chat.config.ChatMessageSearchMode;
import com.audigo.domain.chat.dto.ChatMessageSearchResponse;
import com.audigo.domain.chat.service.ChatMessageSearchService;
import com.audigo.global.security.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatMessageSearchControllerTest {
    @Test
    void reports_the_actual_mode_only_in_the_lab_profile() {
        for (boolean lab : new boolean[]{false, true}) {
            var environment = new MockEnvironment();
            if (lab) {
                environment.setActiveProfiles("search-lab");
                environment.setProperty("audigo.chat.message-search-mode", "instr");
            }
            var user = mock(CurrentUser.class);
            var service = mock(ChatMessageSearchService.class);
            when(user.id()).thenReturn(1L);
            var expected = new ChatMessageSearchResponse(List.of(), null, null, false);
            when(service.search(1L, "54", "검색", null, null)).thenReturn(expected);
            var controller = new ChatMessageSearchController(user, service, new ChatMessageSearchMode(environment));
            var response = new MockHttpServletResponse();

            assertThat(controller.search("54", "검색", null, null, response).data()).isEqualTo(expected);
            assertThat(response.getHeader("X-Audigo-Message-Search-Mode")).isEqualTo(lab ? "instr" : null);
        }
    }
}
