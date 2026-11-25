-- CAD-1859: history of cases removed from a group as individual cases. A case moved to a new
-- group is not recorded here; that history is held by group_case.parent_group_id.
CREATE TABLE removed_case (
    case_id     UUID         NOT NULL,                 -- Progression case id of the removed case
    group_id    UUID         NOT NULL,                 -- group the case was removed from
    removed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_removed_case PRIMARY KEY (case_id),
    CONSTRAINT fk_removed_case_group FOREIGN KEY (group_id) REFERENCES group_case (group_id)
);

CREATE INDEX ix_removed_case_group ON removed_case (group_id);
