-- Family rows are label-bounded in the uploaded workbook and no longer share the blank template's six-row limit.
ALTER TABLE pa_family_member DROP CHECK ck_pa_family_member_order;

ALTER TABLE pa_family_member
    ADD CONSTRAINT ck_pa_family_member_order CHECK (sort_order >= 1);
