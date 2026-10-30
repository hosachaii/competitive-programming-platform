package com.cpplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end auth flow against the real Spring context (security filters, JWT, JPA)
 * on an in-memory H2 database: register -> use access token on a protected endpoint
 * -> reject when unauthenticated -> refresh -> log in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    private static final String EMAIL = "int@test.local";
    private static final String PASSWORD = "password123";

    @Test
    void registerLoginProtectedRefreshAndUnauthenticated() throws Exception {
        // --- register ---
        MvcResult reg = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterBody(EMAIL, "integ", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();
        JsonNode tokens = om.readTree(reg.getResponse().getContentAsString());
        String access = tokens.get("accessToken").asText();
        String refresh = tokens.get("refreshToken").asText();

        // --- access a protected endpoint with the access token ---
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.username").value("integ"));

        // --- same endpoint without a token is rejected (401, not 403) ---
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        // --- refresh yields a fresh, working access token ---
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshBody(refresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String newAccess = om.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asText();
        assertNotNull(newAccess);
        assertFalse(newAccess.isBlank());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        // --- login with the registered credentials ---
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginBody(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterBody("wrongpw@test.local", "wrongpw", PASSWORD))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginBody("wrongpw@test.local", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    void oversizedSourceCodeIsRejected() throws Exception {
        // register + submit an over-64KB payload -> 400 validation error
        MvcResult reg = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterBody("big@test.local", "bigsrc", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        String access = om.readTree(reg.getResponse().getContentAsString()).get("accessToken").asText();
        String huge = "x".repeat(70 * 1024);
        mvc.perform(post("/api/submissions")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new SubmitBody(1L, "CPP", huge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    // minimal request bodies for serialization
    record RegisterBody(String email, String username, String password) {}
    record LoginBody(String email, String password) {}
    record RefreshBody(String refreshToken) {}
    record SubmitBody(Long problemId, String language, String sourceCode) {}
}
