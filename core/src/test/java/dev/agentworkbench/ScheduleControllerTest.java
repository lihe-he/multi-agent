package dev.agentworkbench;

import dev.agentworkbench.mcp.McpAccessRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static dev.agentworkbench.Json.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证每日调度 HTTP 映射、本机认证和版本更新边界。
 */
class ScheduleControllerTest {
    private static final String TOKEN = "schedule-http-fixture-token-with-more-than-24-characters";
    private PersistentScheduleService service;
    private MockMvc mvc;

    @BeforeEach
    void open() {
        service = mock(PersistentScheduleService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ScheduleController(service))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new LocalAccessFilter(TOKEN, new McpAccessRegistry()))
            .build();
    }

    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.with(value -> { value.setRemoteAddr("127.0.0.1"); return value; });
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return local(request).header("Authorization", "Bearer " + TOKEN);
    }

    @Test
    void allScheduleRoutesRequireCoreAuthentication() throws Exception {
        mvc.perform(local(get("/api/schedules"))).andExpect(status().isUnauthorized());
        mvc.perform(local(post("/api/schedules/schedule-1/occurrences/occurrence-1/recover"))).andExpect(status().isUnauthorized());
        mvc.perform(authenticated(get("/api/schedules"))).andExpect(status().isOk());
        verify(service).list();
    }

    @Test
    void lifecycleRoutesForwardPayloadAndOptimisticVersion() throws Exception {
        Map<String, Object> schedule = obj("id", "schedule-1", "version", 1, "name", "Morning");
        when(service.list()).thenReturn(List.of(schedule));
        when(service.create(anyMap())).thenReturn(schedule);
        when(service.update(eq("schedule-1"), anyMap())).thenReturn(schedule);
        when(service.occurrences("schedule-1")).thenReturn(List.of(obj("id", "occurrence-1", "state", "TRIGGERED")));

        mvc.perform(authenticated(post("/api/schedules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Morning\",\"timezone\":\"UTC\",\"localTime\":\"09:00\",\"enabled\":true,\"task\":{}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("schedule-1"));
        mvc.perform(authenticated(patch("/api/schedules/schedule-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"enabled\":false}"))
            .andExpect(status().isOk());
        mvc.perform(authenticated(delete("/api/schedules/schedule-1"))).andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/schedules/schedule-1/occurrences")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("TRIGGERED"));
        when(service.recover(eq("schedule-1"), eq("occurrence-1"), anyMap())).thenReturn(obj("id", "occurrence-1", "state", "TRIGGERED"));
        mvc.perform(authenticated(post("/api/schedules/schedule-1/occurrences/occurrence-1/recover")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"action\":\"RECONCILE_EXISTING_TASK\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("TRIGGERED"));

        verify(service).create(argThat(value -> "Morning".equals(str(value, "name"))));
        verify(service, times(2)).update(eq("schedule-1"), argThat(value -> num(value, "expectedVersion", -1) == 1 && Boolean.FALSE.equals(value.get("enabled"))));
        verify(service).recover(eq("schedule-1"), eq("occurrence-1"), argThat(value -> "RECONCILE_EXISTING_TASK".equals(str(value, "action"))));
    }

    @Test
    void missingOccurrenceScheduleIsReturnedAsNotFound() throws Exception {
        when(service.occurrences("missing")).thenThrow(new ApiException(404, "定时计划不存在"));
        mvc.perform(authenticated(get("/api/schedules/missing/occurrences")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }
}
