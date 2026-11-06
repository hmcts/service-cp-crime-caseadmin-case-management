package uk.gov.hmcts.cp.controllers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.exceptions.GroupCaseAlreadyExistsException;
import uk.gov.hmcts.cp.exceptions.GroupCaseNotFoundException;
import uk.gov.hmcts.cp.openapi.model.CreateGroupCaseRequest;
import uk.gov.hmcts.cp.openapi.model.GroupCaseResponse;
import uk.gov.hmcts.cp.services.GroupCaseService;

@TestPropertySource(properties = "server.servlet.context-path=")
@WebMvcTest(controllers = GroupCaseController.class)
@Import(GlobalExceptionHandler.class)
class GroupCaseControllerTest {

    private static final String PATH = "/groupcases";
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private GroupCaseService groupCaseService;

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
}
