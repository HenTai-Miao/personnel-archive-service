ALTER TABLE pa_person
    ADD COLUMN birth_date_text VARCHAR(64) NULL AFTER birth_date,
    ADD COLUMN political_join_date_text VARCHAR(64) NULL AFTER political_join_date,
    ADD COLUMN declaration_date_text VARCHAR(64) NULL AFTER declaration_date;

ALTER TABLE pa_employment
    ADD COLUMN work_start_date_text VARCHAR(64) NULL AFTER work_start_date,
    ADD COLUMN dispatched_to_bidding_company_date_text VARCHAR(64) NULL AFTER dispatched_to_bidding_company_date,
    ADD COLUMN title_or_skill_acquired_date_text VARCHAR(64) NULL AFTER title_or_skill_acquired_date;

ALTER TABLE pa_family_member
    ADD COLUMN birth_date_text VARCHAR(64) NULL AFTER birth_date,
    ADD COLUMN age_text TEXT NULL AFTER birth_date_text;

UPDATE pa_person
SET birth_date_text = DATE_FORMAT(birth_date, '%Y-%m-%d')
WHERE birth_date IS NOT NULL AND birth_date_text IS NULL;

UPDATE pa_person
SET political_join_date_text = DATE_FORMAT(political_join_date, '%Y-%m-%d')
WHERE political_join_date IS NOT NULL AND political_join_date_text IS NULL;

UPDATE pa_person
SET declaration_date_text = DATE_FORMAT(declaration_date, '%Y-%m-%d')
WHERE declaration_date IS NOT NULL AND declaration_date_text IS NULL;

UPDATE pa_employment
SET work_start_date_text = DATE_FORMAT(work_start_date, '%Y-%m-%d')
WHERE work_start_date IS NOT NULL AND work_start_date_text IS NULL;

UPDATE pa_employment
SET dispatched_to_bidding_company_date_text = DATE_FORMAT(dispatched_to_bidding_company_date, '%Y-%m-%d')
WHERE dispatched_to_bidding_company_date IS NOT NULL
  AND dispatched_to_bidding_company_date_text IS NULL;

UPDATE pa_employment
SET title_or_skill_acquired_date_text = DATE_FORMAT(title_or_skill_acquired_date, '%Y-%m-%d')
WHERE title_or_skill_acquired_date IS NOT NULL
  AND title_or_skill_acquired_date_text IS NULL;

UPDATE pa_family_member
SET birth_date_text = DATE_FORMAT(birth_date, '%Y-%m-%d')
WHERE birth_date IS NOT NULL AND birth_date_text IS NULL;
