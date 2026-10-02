package db.migration;

import java.util.ArrayList;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V33__knowledge_voice_question_contexts extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        String schema=context.getConfiguration().getDefaultSchema();
        if (schema==null) schema=connection.getSchema();
        String table=quote(schema)+"."+quote("ai_mock_interviews");
        if (connection.getMetaData().getDatabaseProductName().equals("H2")) table=quote(schema)+"."+quote("AI_MOCK_INTERVIEWS");
        // V24's unnamed CHECK has different generated names in PostgreSQL and H2.
        var checks=new ArrayList<String>();
        try(var query=connection.prepareStatement("SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name WHERE LOWER(tc.table_schema)=LOWER(?) AND LOWER(tc.table_name)='ai_mock_interviews' AND tc.constraint_type='CHECK'")) {
            query.setString(1,schema);
            try(var rows=query.executeQuery()) { while(rows.next()) {
                String clause=rows.getString(2);
                // PostgreSQL 17 also exposes NOT NULL checks through this view.
                if(clause!=null && clause.toLowerCase(Locale.ROOT).contains("generation_version") && clause.contains("SIMULATION_AGENT_V1")) checks.add(rows.getString(1));
            } }
        }
        if (checks.size()!=1) throw new IllegalStateException("Expected the V24 generation version constraint.");
        try(var statement=connection.createStatement()) {
            statement.execute("ALTER TABLE "+table+" DROP CONSTRAINT "+quote(checks.getFirst()));
            statement.execute("ALTER TABLE "+table+" ADD CONSTRAINT ai_mock_interviews_generation_version_check CHECK (generation_version IN ('LEGACY','SIMULATION_AGENT_V1','KNOWLEDGE_INCREMENTAL_V1'))");
            statement.execute("ALTER TABLE "+table+" ADD COLUMN knowledge_contexts TEXT");
        }
    }
    private static String quote(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
}
