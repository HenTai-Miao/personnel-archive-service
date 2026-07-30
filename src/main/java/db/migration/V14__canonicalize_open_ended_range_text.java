package db.migration;

import com.company.personnelarchive.migration.OpenEndedRangeTextBackfillV4;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V14__canonicalize_open_ended_range_text extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 14_001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        OpenEndedRangeTextBackfillV4.migrate(context.getConnection());
    }
}
