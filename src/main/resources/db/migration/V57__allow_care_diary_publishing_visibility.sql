ALTER TABLE `care_diary`
    DROP CHECK `ck_care_diary_visibility`;

ALTER TABLE `care_diary`
    ADD CONSTRAINT `ck_care_diary_visibility`
    CHECK (`visibility` IN ('PUBLIC', 'PRIVATE', 'PUBLISHING'));
