ALTER TABLE pa_person_photo
    ADD COLUMN anchor_col1 SMALLINT NOT NULL DEFAULT 8 AFTER sha256,
    ADD COLUMN anchor_row1 SMALLINT NOT NULL DEFAULT 2 AFTER anchor_col1,
    ADD COLUMN anchor_dx1 INT NOT NULL DEFAULT 0 AFTER anchor_row1,
    ADD COLUMN anchor_dy1 INT NOT NULL DEFAULT 0 AFTER anchor_dx1,
    ADD COLUMN anchor_col2 SMALLINT NOT NULL DEFAULT 9 AFTER anchor_dy1,
    ADD COLUMN anchor_row2 SMALLINT NOT NULL DEFAULT 11 AFTER anchor_col2,
    ADD COLUMN anchor_dx2 INT NOT NULL DEFAULT 0 AFTER anchor_row2,
    ADD COLUMN anchor_dy2 INT NOT NULL DEFAULT 0 AFTER anchor_dx2,
    ADD COLUMN anchor_type VARCHAR(32) NOT NULL DEFAULT 'MOVE_AND_RESIZE' AFTER anchor_dy2,
    ADD CONSTRAINT ck_pa_person_photo_anchor_area CHECK (
        anchor_col1 = 8
        AND anchor_col2 BETWEEN 8 AND 9
        AND anchor_row1 BETWEEN 2 AND 11
        AND anchor_row2 BETWEEN anchor_row1 AND 11
        AND anchor_dx1 >= 0 AND anchor_dy1 >= 0 AND anchor_dx2 >= 0 AND anchor_dy2 >= 0
    ),
    ADD CONSTRAINT ck_pa_person_photo_anchor_type CHECK (
        anchor_type IN ('MOVE_AND_RESIZE', 'MOVE_DONT_RESIZE', 'DONT_MOVE_AND_RESIZE')
    );
