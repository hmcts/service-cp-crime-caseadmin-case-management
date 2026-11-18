package uk.gov.hmcts.cp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.exceptions.SystemUserNotConfiguredException;

class CaseManagementSystemUserTest {

    @Test
    void configured_id_should_be_returned() {
        final CaseManagementSystemUser systemUser =
                new CaseManagementSystemUser("00000000-0000-0000-0000-0000000000aa");

        assertThat(systemUser.getSystemUserId()).isEqualTo("00000000-0000-0000-0000-0000000000aa");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missing_id_should_throw_not_configured(final String configured) {
        final CaseManagementSystemUser systemUser = new CaseManagementSystemUser(configured);

        assertThatThrownBy(systemUser::getSystemUserId)
                .isInstanceOf(SystemUserNotConfiguredException.class)
                .hasMessage("System user id is not configured");
    }
}
