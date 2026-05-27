package io.jaylee.amr.demo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jaylee.amr.demo.domain.Session;
import io.jaylee.amr.demo.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
@Import(GlobalExceptionHandler.class)
class SessionControllerTest {

	private static final String SID = "11111111-2222-3333-4444-555555555555";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private SessionService service;

	private static Session sampleSession() {
		Instant t = Instant.parse("2026-05-17T12:00:00Z");
		Map<String, String> attrs = new LinkedHashMap<>();
		attrs.put("lang", "en");
		return new Session(SID, "user-1", t, t, attrs);
	}

	@Test
	void post_returns201_withLocationHeader_andBody() throws Exception {
		given(service.create(any(), any())).willReturn(sampleSession());
		String body = objectMapper
			.writeValueAsString(new SessionController.CreateSessionRequest("user-1", Map.of("lang", "en")));

		mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/sessions/" + SID)))
			.andExpect(jsonPath("$.sessionId").value(SID))
			.andExpect(jsonPath("$.userId").value("user-1"))
			.andExpect(jsonPath("$.attributes.lang").value("en"));
	}

	@Test
	void post_returns400_whenServiceRejectsBlankUserId() throws Exception {
		given(service.create(any(), any())).willThrow(new IllegalArgumentException("userId must not be blank"));

		mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
	}

	@Test
	void post_returns400_whenServiceRejectsNullAttributeValue() throws Exception {
		given(service.create(any(), any()))
			.willThrow(new IllegalArgumentException("attribute 'lang' must not have a null value"));

		mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":\"u\",\"attributes\":{\"lang\":null}}")).andExpect(status().isBadRequest());
	}

	@Test
	void get_returns200_andBody_whenPresent() throws Exception {
		given(service.get(SID)).willReturn(Optional.of(sampleSession()));

		mvc.perform(get("/sessions/{sid}", SID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(SID))
			.andExpect(jsonPath("$.userId").value("user-1"));
	}

	@Test
	void get_returns404_whenMissing() throws Exception {
		given(service.get(SID)).willReturn(Optional.empty());

		mvc.perform(get("/sessions/{sid}", SID))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	void patch_returns200_whenPresent_andDelegatesMerge() throws Exception {
		given(service.patch(eq(SID), any())).willReturn(Optional.of(sampleSession()));

		mvc.perform(patch("/sessions/{sid}", SID).contentType(MediaType.APPLICATION_JSON)
			.content("{\"attributes\":{\"theme\":\"dark\"}}")).andExpect(status().isOk());

		verify(service).patch(eq(SID), any());
	}

	@Test
	void patch_returns400_onValidationFailure() throws Exception {
		given(service.patch(eq(SID), any()))
			.willThrow(new IllegalArgumentException("attribute 'theme' must not have a null value"));

		mvc.perform(patch("/sessions/{sid}", SID).contentType(MediaType.APPLICATION_JSON)
			.content("{\"attributes\":{\"theme\":null}}")).andExpect(status().isBadRequest());
	}

	@Test
	void patch_returns404_whenMissing() throws Exception {
		given(service.patch(eq(SID), any())).willReturn(Optional.empty());

		mvc.perform(
				patch("/sessions/{sid}", SID).contentType(MediaType.APPLICATION_JSON).content("{\"attributes\":{}}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void delete_returns204_andIsIdempotent() throws Exception {
		given(service.delete(SID)).willReturn(false);

		mvc.perform(delete("/sessions/{sid}", SID)).andExpect(status().isNoContent()).andExpect(content().string(""));
	}

}
