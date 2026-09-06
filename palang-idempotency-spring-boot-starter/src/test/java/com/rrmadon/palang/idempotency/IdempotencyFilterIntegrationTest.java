package com.rrmadon.palang.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(classes = TestApplication.class)
class IdempotencyFilterIntegrationTest {

    private static final String HEADER = "Idempotency-Key";
    private static final String BODY = "{\"amount\":50000}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TestApplication.PaymentController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(
                context.getBean(org.springframework.boot.web.servlet.FilterRegistrationBean.class).getFilter()
        ).build();
        controller.reset();
    }

    @Test
    @DisplayName("a duplicate key replays the original response without re-running the work")
    void duplicateKeyReplays() throws Exception {
        MvcResult first = mvc.perform(post("/payments")
                        .header(HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn();

        MvcResult second = mvc.perform(post("/payments")
                        .header(HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(second.getResponse().getHeader(IdempotencyFilter.REPLAY_HEADER)).isEqualTo("true");
        assertThat(first.getResponse().getHeader(IdempotencyFilter.REPLAY_HEADER)).isNull();
        assertThat(controller.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("reusing a key with a different payload is rejected as a conflict")
    void reusedKeyWithDifferentPayloadConflicts() throws Exception {
        mvc.perform(post("/payments")
                .header(HEADER, "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andReturn();

        MvcResult conflict = mvc.perform(post("/payments")
                        .header(HEADER, "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":999999}"))
                .andReturn();

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("key-reused");
        assertThat(controller.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("requests without a key are not guarded")
    void requestWithoutKeyIsNotGuarded() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();

        assertThat(controller.executions()).isEqualTo(2);
    }

    @Test
    @DisplayName("a failed response is not replayed, so the retry gets a real attempt")
    void failureIsNotCached() throws Exception {
        mvc.perform(post("/always-fails")
                .header(HEADER, "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andReturn();

        MvcResult retry = mvc.perform(post("/always-fails")
                        .header(HEADER, "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn();

        assertThat(retry.getResponse().getHeader(IdempotencyFilter.REPLAY_HEADER)).isNull();
        assertThat(controller.executions()).isEqualTo(2);
    }

    @Test
    @DisplayName("safe methods are left alone")
    void getIsNotGuarded() throws Exception {
        mvc.perform(get("/payments").header(HEADER, "key-4")).andReturn();
        assertThat(controller.executions()).isZero();
    }
}
