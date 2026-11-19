package uk.gov.hmcts.cp.controllers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.EXAMPLE_DEFENDANT;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.EXAMPLE_URN;
import static uk.gov.hmcts.cp.fixtures.GroupCaseRequests.validRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.exceptions.ProgressionCommandRejectedException;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;
import uk.gov.hmcts.cp.openapi.model.ExtractCasesRequest;
import uk.gov.hmcts.cp.openapi.model.ExtractLeadCase;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResultingIntent;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResultingIntentsResponse;
import uk.gov.hmcts.cp.openapi.model.GroupCaseSearchResponse;
import uk.gov.hmcts.cp.openapi.model.ResultingIntent;
import uk.gov.hmcts.cp.services.GroupCaseExtractService;
import uk.gov.hmcts.cp.services.GroupCaseService;
import uk.gov.hmcts.cp.monitoring.PublishMetricsService;

@TestPropertySource(properties = "server.servlet.context-path=")
@WebMvcTest(controllers = GroupCaseController.class)
@Import(GlobalExceptionHandler.class)
class GroupCaseControllerTest {

    private static final String PATH = "/groupcases";
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COURT_CENTRE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private GroupCaseService groupCaseService;

    @MockitoBean
    private GroupCaseExtractService groupCaseExtractService;

    @MockitoBean
    private PublishMetricsService publishMetricsService;

    private MockHttpServletRequestBuilder createRequest(final Object body) {
        return post(PATH)
                .header("CJSCPPUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
    }

    @Test
    void create_valid_request_should_return_200_with_no_body() throws Exception {
        mockMvc.perform(createRequest(validRequest().build()))
                .andExpect(status().isOk())
                .andExpect(content().string(emptyString()));

        verify(groupCaseService).create(any(CreateGroupCaseRequest.class));
    }

    @Test
    void create_missing_court_centre_id_should_return_400() throws Exception {
        mockMvc.perform(createRequest(validRequest().courtCentreId(null).build()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("courtCentreId")));

        verify(groupCaseService, never()).create(any());
    }

    @Test
    void create_location_not_seven_characters_should_return_400() throws Exception {
        mockMvc.perform(createRequest(validRequest().courtHearingLocation("B01LY0").build()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("courtHearingLocation")));
    }

    @Test
    void create_case_count_zero_should_return_400() throws Exception {
        mockMvc.perform(createRequest(validRequest().caseCount(0).build()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("caseCount")));
    }

    @Test
    void create_missing_lead_case_urn_should_return_400() throws Exception {
        final CreateGroupCaseRequest request = validRequest().build();
        request.getLeadCase().setCaseUrn(null);

        mockMvc.perform(createRequest(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("leadCase.caseUrn")));
    }

    @Test
    void create_malformed_json_should_return_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void create_without_cjscppuid_header_should_return_400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(validRequest().build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CJSCPPUID")));

        verify(groupCaseService, never()).create(any());
    }

    @Test
    void create_existing_group_should_return_409() throws Exception {
        final CreateGroupCaseRequest request = validRequest().build();
        doThrow(new GroupCaseAlreadyExistsException(request.getGroupId()))
                .when(groupCaseService).create(any(CreateGroupCaseRequest.class));

        mockMvc.perform(createRequest(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString(request.getGroupId().toString())))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void create_unknown_parent_should_return_404() throws Exception {
        final UUID parentGroupId = UUID.randomUUID();
        doThrow(new GroupCaseNotFoundException(parentGroupId))
                .when(groupCaseService).create(any(CreateGroupCaseRequest.class));

        mockMvc.perform(createRequest(validRequest().parentGroupId(parentGroupId).build()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(containsString(parentGroupId.toString())));
    }

    @Test
    void create_unexpected_failure_should_return_500_without_internal_details() throws Exception {
        doThrow(new IllegalStateException("database exploded"))
                .when(groupCaseService).create(any(CreateGroupCaseRequest.class));

        mockMvc.perform(createRequest(validRequest().build()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void error_responses_should_not_echo_personal_data() throws Exception {
        final CreateGroupCaseRequest request = validRequest().caseCount(0).build();

        mockMvc.perform(createRequest(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(EXAMPLE_URN))))
                .andExpect(content().string(not(containsString(EXAMPLE_DEFENDANT))));
    }

    @Test
    void getGroupCase_known_group_should_return_200_with_body() throws Exception {
        final GroupCaseResponse response = GroupCaseResponse.builder().groupId(GROUP_ID).build();
        when(groupCaseService.getGroupCase(any(), eq(GROUP_ID), isNull())).thenReturn(response);

        mockMvc.perform(get(PATH + "/" + GROUP_ID).header("CJSCPPUID", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(GROUP_ID.toString()));
    }

    @Test
    void getGroupCase_should_forward_referredCaseId_query_param() throws Exception {
        final UUID referredCaseId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(groupCaseService.getGroupCase(any(), eq(GROUP_ID), eq(referredCaseId)))
                .thenReturn(GroupCaseResponse.builder().groupId(GROUP_ID).build());

        mockMvc.perform(get(PATH + "/" + GROUP_ID)
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("referredCaseId", referredCaseId.toString()))
                .andExpect(status().isOk());

        verify(groupCaseService).getGroupCase(any(), eq(GROUP_ID), eq(referredCaseId));
    }

    @Test
    void getGroupCase_unknown_group_should_return_404() throws Exception {
        doThrow(new GroupCaseNotFoundException(GROUP_ID))
                .when(groupCaseService).getGroupCase(any(), eq(GROUP_ID), isNull());

        mockMvc.perform(get(PATH + "/" + GROUP_ID).header("CJSCPPUID", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(containsString(GROUP_ID.toString())));
    }

    @Test
    void getGroupCase_without_cjscppuid_header_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/" + GROUP_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CJSCPPUID")));

        verify(groupCaseService, never()).getGroupCase(any(), any(), any());
    }

    @Test
    void search_valid_request_should_return_200_with_body() throws Exception {
        final GroupCaseSearchResponse response = GroupCaseSearchResponse.builder()
                .groupCases(List.of())
                .page(0)
                .pageSize(20)
                .totalElements(0L)
                .totalPages(0)
                .build();
        when(groupCaseService.search(eq(COURT_CENTRE_ID), isNull(),
                eq(LocalDate.of(2026, 11, 2)), isNull(), eq(1), eq(20))).thenReturn(response);

        mockMvc.perform(get(PATH + "/search")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("courtCentreId", COURT_CENTRE_ID.toString())
                        .param("startDate", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    void search_should_forward_courtroom_endDate_and_page_params() throws Exception {
        final UUID courtRoomId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        when(groupCaseService.search(eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(LocalDate.of(2026, 11, 2)), eq(LocalDate.of(2026, 11, 6)), eq(2), eq(10)))
                .thenReturn(GroupCaseSearchResponse.builder().groupCases(List.of()).build());

        mockMvc.perform(get(PATH + "/search")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("courtCentreId", COURT_CENTRE_ID.toString())
                        .param("courtRoomId", courtRoomId.toString())
                        .param("startDate", "2026-11-02")
                        .param("endDate", "2026-11-06")
                        .param("pageNumber", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk());

        verify(groupCaseService).search(eq(COURT_CENTRE_ID), eq(courtRoomId),
                eq(LocalDate.of(2026, 11, 2)), eq(LocalDate.of(2026, 11, 6)), eq(2), eq(10));
    }

    @Test
    void search_missing_courtCentreId_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/search")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("startDate", "2026-11-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("courtCentreId")));

        verify(groupCaseService, never()).search(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void search_without_cjscppuid_header_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/search")
                        .param("courtCentreId", COURT_CENTRE_ID.toString())
                        .param("startDate", "2026-11-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CJSCPPUID")));

        verify(groupCaseService, never()).search(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void search_pageNumber_zero_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/search")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("courtCentreId", COURT_CENTRE_ID.toString())
                        .param("startDate", "2026-11-02")
                        .param("pageNumber", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("greater than or equal to 1")));

        verify(groupCaseService, never()).search(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void resultingIntents_valid_request_should_return_200_with_body() throws Exception {
        final GroupCaseResultingIntentsResponse response = GroupCaseResultingIntentsResponse.builder()
                .groupCases(List.of(GroupCaseResultingIntent.builder()
                        .groupId(GROUP_ID)
                        .resultingIntent(ResultingIntent.GRANT)
                        .build()))
                .build();
        when(groupCaseService.getResultingIntents(List.of(GROUP_ID))).thenReturn(response);

        mockMvc.perform(get(PATH + "/resultintent")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("groupIds", GROUP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCases[0].groupId").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.groupCases[0].resultingIntent").value("GRANT"));
    }

    @Test
    void resultingIntents_should_forward_comma_separated_groupIds() throws Exception {
        final UUID secondGroupId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        when(groupCaseService.getResultingIntents(List.of(GROUP_ID, secondGroupId)))
                .thenReturn(GroupCaseResultingIntentsResponse.builder().groupCases(List.of()).build());

        mockMvc.perform(get(PATH + "/resultintent")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("groupIds", GROUP_ID + "," + secondGroupId))
                .andExpect(status().isOk());

        verify(groupCaseService).getResultingIntents(List.of(GROUP_ID, secondGroupId));
    }

    @Test
    void resultingIntents_unknown_group_should_be_omitted_with_200() throws Exception {
        when(groupCaseService.getResultingIntents(List.of(GROUP_ID)))
                .thenReturn(GroupCaseResultingIntentsResponse.builder().groupCases(List.of()).build());

        mockMvc.perform(get(PATH + "/resultintent")
                        .header("CJSCPPUID", UUID.randomUUID().toString())
                        .param("groupIds", GROUP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCases").isEmpty());
    }

    @Test
    void resultingIntents_missing_groupIds_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/resultintent")
                        .header("CJSCPPUID", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("groupIds")));

        verify(groupCaseService, never()).getResultingIntents(any());
    }

    @Test
    void resultingIntents_without_cjscppuid_header_should_return_400() throws Exception {
        mockMvc.perform(get(PATH + "/resultintent")
                        .param("groupIds", GROUP_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("CJSCPPUID")));

        verify(groupCaseService, never()).getResultingIntents(any());
    }

    private static ExtractCasesRequest.ExtractCasesRequestBuilder extractOne() {
        return ExtractCasesRequest.builder()
                .caseIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000003")))
                .newGroup(false)
                .resultingIntent(ResultingIntent.GRANT);
    }

    private MockHttpServletRequestBuilder extractRequest(final Object body) {
        return post(PATH + "/" + GROUP_ID + "/extractcases")
                .header("CJSCPPUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
    }

    @Test
    void extract_valid_request_should_return_204_with_no_body() throws Exception {
        final ExtractCasesRequest request = extractOne().build();

        mockMvc.perform(extractRequest(request))
                .andExpect(status().isNoContent())
                .andExpect(content().string(emptyString()));

        verify(groupCaseExtractService).extractCases(eq(GROUP_ID), eq(request));
    }

    @Test
    void extract_missing_case_ids_should_return_400() throws Exception {
        mockMvc.perform(extractRequest(extractOne().caseIds(null).build()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("caseIds")));

        verify(groupCaseExtractService, never()).extractCases(any(), any());
    }

    @Test
    void extract_lead_case_without_urn_should_return_400() throws Exception {
        final ExtractLeadCase leadCase = ExtractLeadCase.builder()
                .caseId(UUID.randomUUID()).defendantName(EXAMPLE_DEFENDANT).build();

        mockMvc.perform(extractRequest(extractOne().leadCase(leadCase).build()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(EXAMPLE_DEFENDANT))));

        verify(groupCaseExtractService, never()).extractCases(any(), any());
    }

    @Test
    void extract_without_cjscppuid_header_should_return_400() throws Exception {
        mockMvc.perform(post(PATH + "/" + GROUP_ID + "/extractcases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(extractOne().build())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extract_rejected_by_business_rule_should_return_400() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only one case can be removed at a time"))
                .when(groupCaseExtractService).extractCases(eq(GROUP_ID), any(ExtractCasesRequest.class));

        mockMvc.perform(extractRequest(extractOne().build()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only one case can be removed at a time"));
    }

    @Test
    void extract_unknown_group_should_return_404() throws Exception {
        doThrow(new GroupCaseNotFoundException(GROUP_ID))
                .when(groupCaseExtractService).extractCases(eq(GROUP_ID), any(ExtractCasesRequest.class));

        mockMvc.perform(extractRequest(extractOne().build()))
                .andExpect(status().isNotFound());
        // The handler offers every exception; PublishMetricsService decides (by annotation) what is sent
        verify(publishMetricsService).publishExceptionIfEnabled(eq("rest"), any(GroupCaseNotFoundException.class),
                eq(HttpStatus.NOT_FOUND));
    }

    @Test
    void extract_not_accepted_by_progression_should_return_500() throws Exception {
        doThrow(new ProgressionCommandRejectedException(HttpStatusCode.valueOf(403)))
                .when(groupCaseExtractService).extractCases(eq(GROUP_ID), any(ExtractCasesRequest.class));

        mockMvc.perform(extractRequest(extractOne().build()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Progression did not accept the removal"));
        verify(publishMetricsService).publishExceptionIfEnabled(eq("rest"),
                any(ProgressionCommandRejectedException.class), eq(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Test
    void extract_without_a_configured_system_user_should_return_500() throws Exception {
        doThrow(new SystemUserNotConfiguredException())
                .when(groupCaseExtractService).extractCases(eq(GROUP_ID), any(ExtractCasesRequest.class));

        mockMvc.perform(extractRequest(extractOne().build()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("System user id is not configured"));
        verify(publishMetricsService).publishExceptionIfEnabled(eq("rest"),
                any(SystemUserNotConfiguredException.class), eq(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
