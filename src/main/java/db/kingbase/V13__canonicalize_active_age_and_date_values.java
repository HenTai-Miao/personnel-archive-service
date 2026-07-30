package db.kingbase;

import com.company.personnelarchive.migration.CanonicalSourceValueBackfillV3;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public final class V13__canonicalize_active_age_and_date_values extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 13_001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        CanonicalSourceValueBackfillV3.migrate(context.getConnection());
    }
}
