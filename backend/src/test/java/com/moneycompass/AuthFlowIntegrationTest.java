package com.moneycompass;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 acceptance, proven rather than described:
 * register returns a token, login returns a token, and a protected endpoint
 * is 401 without one and 200 with it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        // 48 characters, comfortably past the 32 byte HS256 floor.
        "moneycompass.security.jwt-secret=test-secret-key-for-integration-tests-only-32b",
        "spring.config.import="
})
class AuthFlowIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void registerThenLoginThenAccessProtectedEndpoint() throws Exception {
        MockMvc mvc = mockMvc();
        String email = uniqueEmail();
        String password = "correct-horse-battery";

        // register -> 201 with a token
        MvcResult registered = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","profileType":"STUDENT"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        assertThat(registered.getResponse().getContentAsString()).doesNotContain(password);

        // login -> 200 with a token
        MvcResult loggedIn = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = com.jayway.jsonpath.JsonPath.read(
                loggedIn.getResponse().getContentAsString(), "$.token");

        // protected endpoint without a token -> 401
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());

        // protected endpoint with the token -> 200
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.profileType").value("STUDENT"));
    }

    @Test
    void duplicateEmailIsRejectedWithProblemDetail() throws Exception {
        MockMvc mvc = mockMvc();
        String email = uniqueEmail();
        String body = """
                {"email":"%s","password":"correct-horse-battery","profileType":"PROFESSIONAL"}
                """.formatted(email);

        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        MockMvc mvc = mockMvc();
        String email = uniqueEmail();

        mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"correct-horse-battery","profileType":"RETIREE"}
                        """.formatted(email)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"wrong-horse-battery"}
                        """.formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shortPasswordFailsValidation() throws Exception {
        mockMvc().perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"short","profileType":"STUDENT"}
                        """.formatted(uniqueEmail())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.password").isNotEmpty());
    }
}
