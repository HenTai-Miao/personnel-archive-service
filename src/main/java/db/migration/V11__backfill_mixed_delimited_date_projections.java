package db.migration;

import com.company.personnelarchive.migration.StructuredSourceValueBackfillV2;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V11__backfill_mixed_delimited_date_projections extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 11_001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        StructuredSourceValueBackfillV2.migrate(context.getConnection());
    }
}
