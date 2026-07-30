package db.kingbase;

import com.company.personnelarchive.migration.StructuredSourceValueBackfillV1;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V10__backfill_structured_source_values extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 10_001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        StructuredSourceValueBackfillV1.migrate(context.getConnection());
    }
}
