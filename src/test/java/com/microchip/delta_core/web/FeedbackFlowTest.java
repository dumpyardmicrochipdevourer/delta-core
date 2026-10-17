package com.microchip.delta_core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class FeedbackFlowTest {

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry metrics;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        var root = Files.createTempDirectory("delta-feedback-test");
        registry.add("delta.root", root::toString);
        registry.add("delta.out", () -> root.resolve("out").toString());
    }

    @Test
    void anonymousWritesAdminReadsAndMarksRead() throws Exception {
        String marker = UUID.randomUUID().toString();
        mvc.perform(send(ip(), marker, "@anna")).andExpect(status().isNoContent());

        mvc.perform(get("/api/feedback").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value(marker + "\n\n-- \n@anna"))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
        double unread = metrics.get("delta_feedback_unread").gauge().value();

        String id = mvc.perform(get("/api/feedback").with(admin())).andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*?\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/feedback/" + id + "/read").with(admin())).andExpect(status().isNoContent());

        mvc.perform(get("/api/feedback").with(admin())).andExpect(jsonPath("$[0].readAt").exists());
        assertEquals(unread - 1, metrics.get("delta_feedback_unread").gauge().value());
    }

    @Test
    void contactIsOptional() throws Exception {
        String marker = UUID.randomUUID().toString();
        mvc.perform(send(ip(), marker, null)).andExpect(status().isNoContent());
        mvc.perform(get("/api/feedback").with(admin()))
                .andExpect(jsonPath("$[0].body").value(marker + "\n\n-- \n—"));
    }

    @Test
    void readingIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/feedback")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/feedback").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/feedback/1/read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/feedback/1/read").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sixthMessageWithinAnHourIs429() throws Exception {
        String ip = ip();
        for (int i = 0; i < 5; i++) {
            mvc.perform(send(ip, "hi " + i, null)).andExpect(status().isNoContent());
        }
        mvc.perform(send(ip, "hi again", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));
        mvc.perform(send(ip(), "someone else", null)).andExpect(status().isNoContent());
    }

    @Test
    void emptyAndHugeMessagesAre400() throws Exception {
        mvc.perform(send(ip(), " ", null)).andExpect(status().isBadRequest());
        mvc.perform(send(ip(), "x".repeat(9000), null)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/feedback").header("X-Real-IP", ip()).contentType(MediaType.APPLICATION_JSON)
                .content("not json")).andExpect(status().isBadRequest());
    }

    // what a token of pathos-auth with role ADMIN turns into once it passed the real filter
    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static MockHttpServletRequestBuilder send(String ip, String message, String contact) {
        String body = contact == null
                ? "{\"message\":\"%s\"}".formatted(message)
                : "{\"message\":\"%s\",\"contact\":\"%s\"}".formatted(message, contact);
        return post("/api/feedback").header("X-Real-IP", ip).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String ip() {
        return "10.0.0." + UUID.randomUUID().toString().substring(0, 6);
    }
}
