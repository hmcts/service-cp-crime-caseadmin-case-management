-- CAD-1848: group_case table for civil bulk (group) cases.
-- This service owns the group_case record only; case membership and individual
-- case data stay in Progression (prosecution_case.group_id).
-- Design: Confluence "CAD 1051 Solution Designs" (New Data Model).

CREATE TABLE group_case (
    group_id                     UUID          NOT NULL,
    parent_group_id              UUID,
    lead_case_id                 UUID          NOT NULL,
    lead_case_urn                VARCHAR(255)  NOT NULL,   -- case URN; no upstream limit, matches PCF viewstore
    lead_defendant_name          VARCHAR(255)  NOT NULL,   -- personal data
    lead_defendant_dob           DATE,                     -- personal data
    date_of_hearing              DATE          NOT NULL,
    court_hearing_location       VARCHAR(7)    NOT NULL,   -- OU code: 5-char court house + 2-char courtroom
    court_centre_id              UUID          NOT NULL,   -- reference data id, resolved from the OU code at ingestion
    courtroom_id                 UUID,                     -- reference data id, resolved from the OU code at ingestion
    prosecuting_authority_code   VARCHAR(10)   NOT NULL,   -- prosecutor OU code (PCF prosecutingAuthority, reference data oucode)
    prosecuting_authority_name   VARCHAR(255),             -- resolved from reference data at ingestion (CAD-1849)
    offence_type                 VARCHAR(250)  NOT NULL,   -- matches reference data moj_offence.title until the source is confirmed
    case_count                   INTEGER       NOT NULL,
    resulting_intent             VARCHAR(20)   NOT NULL DEFAULT 'GRANT',
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                      BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_group_case PRIMARY KEY (group_id),
    CONSTRAINT fk_group_case_parent FOREIGN KEY (parent_group_id) REFERENCES group_case (group_id),
    CONSTRAINT ck_group_case_case_count CHECK (case_count >= 1),
    CONSTRAINT ck_group_case_location_len CHECK (char_length(court_hearing_location) = 7),
    CONSTRAINT ck_group_case_resulting_intent CHECK (resulting_intent IN ('GRANT', 'ADJOURN', 'WITHDRAW'))
);

CREATE INDEX ix_group_case_centre_date ON group_case (court_centre_id, date_of_hearing);
CREATE INDEX ix_group_case_room_date   ON group_case (courtroom_id, date_of_hearing);
CREATE INDEX ix_group_case_parent      ON group_case (parent_group_id);
CREATE INDEX ix_group_case_lead_case   ON group_case (lead_case_id);
