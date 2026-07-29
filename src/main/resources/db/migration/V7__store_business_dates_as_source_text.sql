ALTER TABLE pa_person
    MODIFY COLUMN birth_date VARCHAR(64) NULL,
    MODIFY COLUMN political_join_date VARCHAR(64) NULL,
    MODIFY COLUMN declaration_date VARCHAR(64) NULL;

UPDATE pa_person
SET birth_date = COALESCE(NULLIF(birth_date_text, ''), birth_date),
    political_join_date = COALESCE(NULLIF(political_join_date_text, ''), political_join_date),
    declaration_date = COALESCE(NULLIF(declaration_date_text, ''), declaration_date);

ALTER TABLE pa_person
    DROP COLUMN birth_date_text,
    DROP COLUMN political_join_date_text,
    DROP COLUMN declaration_date_text;

ALTER TABLE pa_employment
    MODIFY COLUMN work_start_date VARCHAR(64) NULL,
    MODIFY COLUMN dispatched_to_bidding_company_date VARCHAR(64) NULL,
    MODIFY COLUMN title_or_skill_acquired_date VARCHAR(64) NULL;

UPDATE pa_employment
SET work_start_date = COALESCE(NULLIF(work_start_date_text, ''), work_start_date),
    dispatched_to_bidding_company_date = COALESCE(
        NULLIF(dispatched_to_bidding_company_date_text, ''),
        dispatched_to_bidding_company_date
    ),
    title_or_skill_acquired_date = COALESCE(
        NULLIF(title_or_skill_acquired_date_text, ''),
        title_or_skill_acquired_date
    );

ALTER TABLE pa_employment
    DROP COLUMN work_start_date_text,
    DROP COLUMN dispatched_to_bidding_company_date_text,
    DROP COLUMN title_or_skill_acquired_date_text;

ALTER TABLE pa_education
    MODIFY COLUMN start_date VARCHAR(64) NULL,
    MODIFY COLUMN end_date VARCHAR(64) NULL;

ALTER TABLE pa_work_experience
    MODIFY COLUMN start_date VARCHAR(64) NULL,
    MODIFY COLUMN end_date VARCHAR(64) NULL;

ALTER TABLE pa_reward_punishment
    MODIFY COLUMN occurred_date VARCHAR(64) NULL;

ALTER TABLE pa_family_member
    MODIFY COLUMN birth_date VARCHAR(64) NULL;

UPDATE pa_family_member
SET birth_date = COALESCE(NULLIF(birth_date_text, ''), birth_date);

ALTER TABLE pa_family_member
    DROP COLUMN birth_date_text;
