package uk.gov.hmcts.cp.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

class CaseManagementDroolsRulesTest {

    private static final String DRL = "acl/casemanagement-rules.drl";
    private static final String CREATE_GROUP_CASE = "casemanagement-service.create-group-case";
    private static final String VIEW_GROUP_CASE = "casemanagement-service.view-group-case";

    private static KieContainer kieContainer;

    @BeforeAll
    static void setUp() {
        final KieServices ks = KieServices.Factory.get();
        final KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write(ks.getResources().newClassPathResource(DRL).setResourceType(ResourceType.DRL));
        final KieBuilder builder = ks.newKieBuilder(kfs).buildAll();
        assertThat(builder.getResults().getMessages(Message.Level.ERROR)).isEmpty();
        kieContainer = ks.newKieContainer(ks.getRepository().getDefaultReleaseId());
    }

    @SuppressWarnings("PMD.CloseResource") // disposed in the finally block
    private static boolean allowed(final String action, final String... userGroups) {
        final KieSession session = kieContainer.newKieSession();
        try {
            final UserAndGroupProvider provider = mock(UserAndGroupProvider.class);
            when(provider.isMemberOfAnyOfTheSuppliedGroups(any(Action.class), any(String[].class)))
                    .thenAnswer(inv -> {
                        final String[] allowedGroups = (String[]) inv.getRawArguments()[1];
                        return Arrays.stream(userGroups).anyMatch(Arrays.asList(allowedGroups)::contains);
                    });
            session.setGlobal("userAndGroupProvider", provider);
            final Outcome outcome = new Outcome();
            session.insert(outcome);
            session.insert(new Action(action, Map.of()));
            session.fireAllRules();
            return outcome.isSuccess();
        } finally {
            session.dispose();
        }
    }

    @Test
    void system_users_may_create_a_group_case() {
        assertThat(allowed(CREATE_GROUP_CASE, "System Users")).isTrue();
    }

    @Test
    void court_staff_may_not_create_a_group_case() {
        assertThat(allowed(CREATE_GROUP_CASE, "Legal Advisers")).isFalse();
        assertThat(allowed(CREATE_GROUP_CASE, "Court Administrators")).isFalse();
    }

    @Test
    void a_user_with_no_groups_may_not_create_a_group_case() {
        assertThat(allowed(CREATE_GROUP_CASE)).isFalse();
    }

    @Test
    void an_unknown_action_is_denied_even_for_system_users() {
        assertThat(allowed("casemanagement-service.unknown", "System Users")).isFalse();
    }

    @Test
    void system_users_legal_advisers_court_associates_and_court_administrators_may_view_a_group_case() {
        assertThat(allowed(VIEW_GROUP_CASE, "System Users")).isTrue();
        assertThat(allowed(VIEW_GROUP_CASE, "Legal Advisers")).isTrue();
        assertThat(allowed(VIEW_GROUP_CASE, "Court Associate")).isTrue();
        assertThat(allowed(VIEW_GROUP_CASE, "Court Administrators")).isTrue();
    }

    @Test
    void a_user_with_no_groups_may_not_view_a_group_case() {
        assertThat(allowed(VIEW_GROUP_CASE)).isFalse();
    }
}
