ALTER TABLE pa_person
    ADD COLUMN birth_date_year SMALLINT NULL AFTER birth_date,
    ADD COLUMN birth_date_month TINYINT NULL AFTER birth_date_year,
    ADD COLUMN birth_date_day TINYINT NULL AFTER birth_date_month,
    ADD COLUMN political_join_date_year SMALLINT NULL AFTER political_join_date,
    ADD COLUMN political_join_date_month TINYINT NULL AFTER political_join_date_year,
    ADD COLUMN political_join_date_day TINYINT NULL AFTER political_join_date_month,
    ADD COLUMN declaration_date_year SMALLINT NULL AFTER declaration_date,
    ADD COLUMN declaration_date_month TINYINT NULL AFTER declaration_date_year,
    ADD COLUMN declaration_date_day TINYINT NULL AFTER declaration_date_month,
    ADD CONSTRAINT ck_pa_person_birth_date_parts CHECK (
        (birth_date_year IS NULL OR birth_date_year BETWEEN 1000 AND 9999)
        AND (birth_date_month IS NULL OR (birth_date_year IS NOT NULL AND birth_date_month BETWEEN 1 AND 12))
        AND (birth_date_day IS NULL OR (birth_date_month IS NOT NULL AND birth_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_person_political_join_date_parts CHECK (
        (political_join_date_year IS NULL OR political_join_date_year BETWEEN 1000 AND 9999)
        AND (political_join_date_month IS NULL OR (political_join_date_year IS NOT NULL AND political_join_date_month BETWEEN 1 AND 12))
        AND (political_join_date_day IS NULL OR (political_join_date_month IS NOT NULL AND political_join_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_person_declaration_date_parts CHECK (
        (declaration_date_year IS NULL OR declaration_date_year BETWEEN 1000 AND 9999)
        AND (declaration_date_month IS NULL OR (declaration_date_year IS NOT NULL AND declaration_date_month BETWEEN 1 AND 12))
        AND (declaration_date_day IS NULL OR (declaration_date_month IS NOT NULL AND declaration_date_day BETWEEN 1 AND 31))
    );

ALTER TABLE pa_employment
    ADD COLUMN work_start_date_year SMALLINT NULL AFTER work_start_date,
    ADD COLUMN work_start_date_month TINYINT NULL AFTER work_start_date_year,
    ADD COLUMN work_start_date_day TINYINT NULL AFTER work_start_date_month,
    ADD COLUMN dispatched_to_bidding_company_date_year SMALLINT NULL AFTER dispatched_to_bidding_company_date,
    ADD COLUMN dispatched_to_bidding_company_date_month TINYINT NULL AFTER dispatched_to_bidding_company_date_year,
    ADD COLUMN dispatched_to_bidding_company_date_day TINYINT NULL AFTER dispatched_to_bidding_company_date_month,
    ADD COLUMN title_or_skill_acquired_date_year SMALLINT NULL AFTER title_or_skill_acquired_date,
    ADD COLUMN title_or_skill_acquired_date_month TINYINT NULL AFTER title_or_skill_acquired_date_year,
    ADD COLUMN title_or_skill_acquired_date_day TINYINT NULL AFTER title_or_skill_acquired_date_month,
    ADD CONSTRAINT ck_pa_employment_work_start_date_parts CHECK (
        (work_start_date_year IS NULL OR work_start_date_year BETWEEN 1000 AND 9999)
        AND (work_start_date_month IS NULL OR (work_start_date_year IS NOT NULL AND work_start_date_month BETWEEN 1 AND 12))
        AND (work_start_date_day IS NULL OR (work_start_date_month IS NOT NULL AND work_start_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_employment_dispatch_date_parts CHECK (
        (dispatched_to_bidding_company_date_year IS NULL OR dispatched_to_bidding_company_date_year BETWEEN 1000 AND 9999)
        AND (dispatched_to_bidding_company_date_month IS NULL OR (dispatched_to_bidding_company_date_year IS NOT NULL AND dispatched_to_bidding_company_date_month BETWEEN 1 AND 12))
        AND (dispatched_to_bidding_company_date_day IS NULL OR (dispatched_to_bidding_company_date_month IS NOT NULL AND dispatched_to_bidding_company_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_employment_title_date_parts CHECK (
        (title_or_skill_acquired_date_year IS NULL OR title_or_skill_acquired_date_year BETWEEN 1000 AND 9999)
        AND (title_or_skill_acquired_date_month IS NULL OR (title_or_skill_acquired_date_year IS NOT NULL AND title_or_skill_acquired_date_month BETWEEN 1 AND 12))
        AND (title_or_skill_acquired_date_day IS NULL OR (title_or_skill_acquired_date_month IS NOT NULL AND title_or_skill_acquired_date_day BETWEEN 1 AND 31))
    );

ALTER TABLE pa_education
    ADD COLUMN start_date_year SMALLINT NULL AFTER start_date,
    ADD COLUMN start_date_month TINYINT NULL AFTER start_date_year,
    ADD COLUMN start_date_day TINYINT NULL AFTER start_date_month,
    ADD COLUMN end_date_year SMALLINT NULL AFTER end_date,
    ADD COLUMN end_date_month TINYINT NULL AFTER end_date_year,
    ADD COLUMN end_date_day TINYINT NULL AFTER end_date_month,
    ADD CONSTRAINT ck_pa_education_start_date_parts CHECK (
        (start_date_year IS NULL OR start_date_year BETWEEN 1000 AND 9999)
        AND (start_date_month IS NULL OR (start_date_year IS NOT NULL AND start_date_month BETWEEN 1 AND 12))
        AND (start_date_day IS NULL OR (start_date_month IS NOT NULL AND start_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_education_end_date_parts CHECK (
        (end_date_year IS NULL OR end_date_year BETWEEN 1000 AND 9999)
        AND (end_date_month IS NULL OR (end_date_year IS NOT NULL AND end_date_month BETWEEN 1 AND 12))
        AND (end_date_day IS NULL OR (end_date_month IS NOT NULL AND end_date_day BETWEEN 1 AND 31))
    );

ALTER TABLE pa_work_experience
    ADD COLUMN start_date_year SMALLINT NULL AFTER start_date,
    ADD COLUMN start_date_month TINYINT NULL AFTER start_date_year,
    ADD COLUMN start_date_day TINYINT NULL AFTER start_date_month,
    ADD COLUMN end_date_year SMALLINT NULL AFTER end_date,
    ADD COLUMN end_date_month TINYINT NULL AFTER end_date_year,
    ADD COLUMN end_date_day TINYINT NULL AFTER end_date_month,
    ADD CONSTRAINT ck_pa_work_experience_start_date_parts CHECK (
        (start_date_year IS NULL OR start_date_year BETWEEN 1000 AND 9999)
        AND (start_date_month IS NULL OR (start_date_year IS NOT NULL AND start_date_month BETWEEN 1 AND 12))
        AND (start_date_day IS NULL OR (start_date_month IS NOT NULL AND start_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_work_experience_end_date_parts CHECK (
        (end_date_year IS NULL OR end_date_year BETWEEN 1000 AND 9999)
        AND (end_date_month IS NULL OR (end_date_year IS NOT NULL AND end_date_month BETWEEN 1 AND 12))
        AND (end_date_day IS NULL OR (end_date_month IS NOT NULL AND end_date_day BETWEEN 1 AND 31))
    );

ALTER TABLE pa_reward_punishment
    ADD COLUMN occurred_date_year SMALLINT NULL AFTER occurred_date,
    ADD COLUMN occurred_date_month TINYINT NULL AFTER occurred_date_year,
    ADD COLUMN occurred_date_day TINYINT NULL AFTER occurred_date_month,
    ADD CONSTRAINT ck_pa_reward_punishment_occurred_date_parts CHECK (
        (occurred_date_year IS NULL OR occurred_date_year BETWEEN 1000 AND 9999)
        AND (occurred_date_month IS NULL OR (occurred_date_year IS NOT NULL AND occurred_date_month BETWEEN 1 AND 12))
        AND (occurred_date_day IS NULL OR (occurred_date_month IS NOT NULL AND occurred_date_day BETWEEN 1 AND 31))
    );

ALTER TABLE pa_family_member
    ADD COLUMN birth_date_year SMALLINT NULL AFTER birth_date,
    ADD COLUMN birth_date_month TINYINT NULL AFTER birth_date_year,
    ADD COLUMN birth_date_day TINYINT NULL AFTER birth_date_month,
    ADD COLUMN age_value SMALLINT NULL AFTER age_text,
    ADD CONSTRAINT ck_pa_family_member_birth_date_parts CHECK (
        (birth_date_year IS NULL OR birth_date_year BETWEEN 1000 AND 9999)
        AND (birth_date_month IS NULL OR (birth_date_year IS NOT NULL AND birth_date_month BETWEEN 1 AND 12))
        AND (birth_date_day IS NULL OR (birth_date_month IS NOT NULL AND birth_date_day BETWEEN 1 AND 31))
    ),
    ADD CONSTRAINT ck_pa_family_member_age_value CHECK (age_value IS NULL OR age_value >= 0);
