package com.microchip.delta_core.web;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class JobFlowTest {

    static final Path TOOLS = Path.of("src/test/resources/fake-tools").toAbsolutePath();
    static Path root;
    static Path selectors;

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        root = Files.createTempDirectory("delta-test");
        selectors = root.resolve("selectors.log");
        registry.add("delta.root", root::toString);
        registry.add("delta.out", () -> root.resolve("out").toString());
        registry.add("delta.tools.yt-dlp", () -> TOOLS.resolve("yt-dlp").toString());
        registry.add("delta.tools.gallery-dl", () -> TOOLS.resolve("gallery-dl").toString());
        registry.add("delta.tools.ffmpeg", () -> TOOLS.resolve("ffmpeg").toString());
        registry.add("delta.tools.ffprobe", () -> TOOLS.resolve("ffprobe").toString());
        registry.add("delta.tools.collage", () -> TOOLS.resolve("media-collage.py").toString());
        registry.add("delta.anon.max-file-bytes", () -> "10000");
        registry.add("delta.anon.per-hour", () -> "3");
        registry.add("delta.user.per-hour", () -> "1000");
        registry.add("delta.user.max-active", () -> "3");
        registry.add("delta.user.playlist-items", () -> "3");
        registry.add("delta.bot.token", () -> "bot-secret");
    }

    @Test
    void videoGoesFromQueueToFileWithRealTitle() throws Exception {
        String id = create(anon(), "https://example.com/ok", "video", null);
        String body = finished(id);

        assertEquals("done", JsonPath.read(body, "$.state"));
        assertEquals("Real: Title?", JsonPath.read(body, "$.title"));
        assertEquals("Channel", JsonPath.read(body, "$.uploader"));
        assertEquals("Real Title.mp4", JsonPath.read(body, "$.fileName"));
        assertEquals(100, (int) JsonPath.read(body, "$.percent"));
        assertTrue((boolean) JsonPath.read(body, "$.thumb"));
        assertFalse(body.contains("\"paths\""), "paths are for the bot only");

        MvcResult file = mvc.perform(get("/api/jobs/" + id + "/file")).andExpect(status().isOk()).andReturn();
        assertEquals("video-bytes", file.getResponse().getContentAsString());
        assertTrue(file.getResponse().getHeader("Content-Disposition").contains("Real%20Title.mp4"));
        mvc.perform(get("/api/jobs/" + id + "/thumb")).andExpect(status().isOk());

        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/" + id + "/file")).andExpect(status().isNotFound());
        assertFalse(Files.exists(root.resolve("out").resolve(id)));
        assertFalse(Files.exists(root.resolve("work").resolve(id)));
    }

    @Test
    void audioKeepsArtistAndCover() throws Exception {
        String id = create(user(), "https://example.com/ok", "audio", null);
        String body = finished(id);

        assertEquals("done", JsonPath.read(body, "$.state"));
        assertEquals("audio", JsonPath.read(body, "$.kind"));
        assertEquals("Real Title.mp3", JsonPath.read(body, "$.fileName"));
        assertTrue((boolean) JsonPath.read(body, "$.thumb"));
    }

    @Test
    void ladderStepsDownUntilAQualityFits() throws Exception {
        Files.deleteIfExists(selectors);
        String id = create(user(), "https://example.com/heavy", "video", "{\"quality\":1080}");

        assertEquals("done", JsonPath.read(finished(id), "$.state"));
        List<String> tried = Files.readAllLines(selectors).stream().filter(l -> l.contains("heavy") || !l.isBlank())
                .toList();
        assertTrue(tried.stream().anyMatch(l -> l.contains("height<=1080")));
        assertTrue(tried.stream().anyMatch(l -> l.contains("height<=360")));
    }

    @Test
    void botCheckFallsBackToTheAndroidClient() throws Exception {
        String id = create(user(), "https://example.com/bot", "video", null);

        assertEquals("done", JsonPath.read(finished(id), "$.state"));
    }

    @Test
    void failuresAreToldApart() throws Exception {
        assertEquals("unsupported", failure("https://example.com/unsupported"));
        assertEquals("unavailable", failure("https://example.com/private"));
        assertEquals("live", failure("https://example.com/live"));
    }

    @Test
    void runawayDownloadIsStoppedBySize() throws Exception {
        String id = create(anon(), "https://example.com/flood", "video", null);
        String body = finished(id);

        assertEquals("failed", JsonPath.read(body, "$.state"));
        assertEquals("too_large", JsonPath.read(body, "$.error.code"));
        assertFalse(Files.exists(root.resolve("work").resolve(id)));
    }

    @Test
    void runningJobCanBeCancelledAndShowsProgress() throws Exception {
        String id = create(user(), "https://example.com/slow", "video", null);
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            String body = mvc.perform(get("/api/jobs/" + id)).andReturn().getResponse().getContentAsString();
            return JsonPath.<Integer>read(body, "$.percent") > 0 && body.contains("\"title\":\"Slow One\"");
        });

        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isNoContent());
        assertEquals("canceled", JsonPath.read(finished(id), "$.state"));
    }

    @Test
    void postWithoutVideoGivesItsPictures() throws Exception {
        String id = create(user(), "https://example.com/photos", "video", null);
        String body = finished(id);

        assertEquals("done", JsonPath.read(body, "$.state"));
        assertEquals("photos", JsonPath.read(body, "$.kind"));
        assertEquals("Post text.zip", JsonPath.read(body, "$.fileName"));
        assertEquals(List.of("01.jpg", "02.jpg"), zipNames(id));
    }

    @Test
    void playlistComesAsOneArchiveOfChosenItems() throws Exception {
        mvc.perform(post("/api/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("playlist"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].index").value(2));

        String id = JsonPath.read(mvc.perform(user(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/list\",\"mode\":\"video\",\"items\":[3,1]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String body = finished(id);

        assertEquals("done", JsonPath.read(body, "$.state"));
        assertEquals("Course.zip", JsonPath.read(body, "$.fileName"));
        assertTrue((boolean) JsonPath.read(body, "$.archive"));
        assertEquals(List.of("01-Part_1.mp4", "03-Part_3.mp4"), zipNames(id));
    }

    @Test
    void playlistsNeedSignInAndHaveACap() throws Exception {
        mvc.perform(anon(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/list\",\"items\":[1]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("login_required"));
        mvc.perform(user(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/list\",\"items\":[1,2,3,4]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIsLimitedPerAddress() throws Exception {
        String ip = "203.0.113." + (int) (Math.random() * 250);
        for (int i = 0; i < 3; i++) {
            finished(create(anonFrom(ip), "https://example.com/ok", "video", null));
        }
        mvc.perform(anonFrom(ip).apply(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/ok\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void oneAnonymousJobAtATime() throws Exception {
        String ip = "198.51.100." + (int) (Math.random() * 250);
        String id = create(anonFrom(ip), "https://example.com/slow", "video", null);

        mvc.perform(anonFrom(ip).apply(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/ok\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("busy"));
        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isNoContent());
        finished(id);
    }

    @Test
    void botGetsPathsAndTelegramExtras() throws Exception {
        String id = JsonPath.read(mvc.perform(bot(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/photos\",\"mode\":\"video\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        await().atMost(Duration.ofSeconds(60)).until(() -> !List.of("queued", "running").contains(
                JsonPath.<String>read(mvc.perform(bot(get("/api/jobs/" + id))).andReturn().getResponse()
                        .getContentAsString(), "$.state")));
        String body = mvc.perform(bot(get("/api/jobs/" + id))).andReturn().getResponse().getContentAsString();

        assertEquals("done", JsonPath.read(body, "$.state"));
        assertEquals(2, (int) JsonPath.read(body, "$.paths.files.length()"));
        assertTrue(JsonPath.<String>read(body, "$.paths.collage").endsWith("collage.jpg"));
        assertTrue(JsonPath.<String>read(body, "$.paths.file").startsWith(root.resolve("out").toString()));
    }

    @Test
    void wrongBotTokenIsRefused() throws Exception {
        mvc.perform(post("/api/jobs").header("X-Delta-Token", "nope").header("X-Delta-User", "anna")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com/ok\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalAddressesAndGarbageAreRefused() throws Exception {
        for (String url : new String[] {"http://127.0.0.1/x", "http://30.0.0.3:9090/", "http://localhost/x",
                "file:///etc/passwd", "https://intranet/x", "not a url"}) {
            mvc.perform(user(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"%s\"}".formatted(url)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(user(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/ok\",\"options\":{\"container\":\"; rm -rf\"}}"))
                .andExpect(status().isBadRequest());
    }

    private String failure(String url) throws Exception {
        String body = finished(create(user(), url, "video", null));
        assertEquals("failed", JsonPath.read(body, "$.state"));
        return JsonPath.read(body, "$.error.code");
    }

    private String create(Who who, String url, String mode, String options) throws Exception {
        String json = "{\"url\":\"%s\",\"mode\":\"%s\"%s}".formatted(url, mode,
                options == null ? "" : ",\"options\":" + options);
        return JsonPath.read(mvc.perform(who.apply(post("/api/jobs")).contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String finished(String id) throws Exception {
        List<String> last = new ArrayList<>();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200)).until(() -> {
            last.clear();
            last.add(mvc.perform(get("/api/jobs/" + id)).andReturn().getResponse().getContentAsString());
            return !List.of("queued", "running").contains(JsonPath.<String>read(last.getFirst(), "$.state"));
        });
        return last.getFirst();
    }

    private List<String> zipNames(String id) throws Exception {
        byte[] bytes = mvc.perform(get("/api/jobs/" + id + "/file")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    interface Who {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request);
    }

    private static Who anon() {
        return anonFrom("192.0.2." + (int) (Math.random() * 250));
    }

    private static Who anonFrom(String ip) {
        return r -> r.header("X-Real-IP", ip);
    }

    private static Who user() {
        return JobFlowTest::user;
    }

    private static MockHttpServletRequestBuilder anon(MockHttpServletRequestBuilder request) {
        return request.header("X-Real-IP", "192.0.2." + (int) (Math.random() * 250));
    }

    private static MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder request) {
        return request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .jwt().jwt(j -> j.subject(UUID.randomUUID().toString())));
    }

    private static MockHttpServletRequestBuilder bot(MockHttpServletRequestBuilder request) {
        return request.header("X-Delta-Token", "bot-secret").header("X-Delta-User", "anna");
    }
}
