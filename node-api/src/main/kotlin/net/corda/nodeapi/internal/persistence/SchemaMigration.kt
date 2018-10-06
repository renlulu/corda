package net.corda.nodeapi.internal.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.Database
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import net.corda.nodeapi.internal.MigrationHelpers.getMigrationResource
import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.contextLogger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Writer
import javax.sql.DataSource

class SchemaMigration(
        val schemas: Set<MappedSchema>,
        val dataSource: DataSource,
        private val databaseConfig: DatabaseConfig,
        private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader) {

    companion object {
        private val logger = contextLogger()
    }

    /**
     * Main entry point to the schema migration.
     * Called during node startup.
     */
    fun nodeStartup(existingCheckpoints: Boolean) {
        when {
            databaseConfig.initialiseSchema -> {
                migrateOlderDatabaseToUseLiquibase(existingCheckpoints)
                runMigration(existingCheckpoints)
            }
            else -> checkState()
        }
    }

    /**
     * Will run the Liquibase migration on the actual database.
     */
    private fun runMigration(existingCheckpoints: Boolean) = doRunMigration(run = true, check = false, existingCheckpoints = existingCheckpoints)

    /**
     * Ensures that the database is up to date with the latest migration changes.
     */
    private fun checkState() = doRunMigration(run = false, check = true)

    /**  Create a resourse accessor that aggregates the changelogs included in the schemas into one dynamic stream. */
    private class CustomResourceAccessor(val dynamicInclude: String, val changelogList: List<String?>, classLoader: ClassLoader) : ClassLoaderResourceAccessor(classLoader) {
        override fun getResourcesAsStream(path: String): Set<InputStream> {
            if (path == dynamicInclude) {
                // Create a map in Liquibase format including all migration files.
                val includeAllFiles = mapOf("databaseChangeLog" to changelogList.filter { it != null }.map { file -> mapOf("include" to mapOf("file" to file)) })

                // Transform it to json.
                val includeAllFilesJson = ObjectMapper().writeValueAsBytes(includeAllFiles)

                // Return the json as a stream.
                return setOf(ByteArrayInputStream(includeAllFilesJson))
            }
            return super.getResourcesAsStream(path)?.take(1)?.toSet() ?: emptySet()
        }
    }

    private fun doRunMigration(run: Boolean, check: Boolean, existingCheckpoints: Boolean? = null) {

        // Virtual file name of the changelog that includes all schemas.
        val dynamicInclude = "master.changelog.json"

        dataSource.connection.use { connection ->

            // Collect all changelog file referenced in the included schemas.
            // For backward compatibility reasons, when failOnMigrationMissing=false, we don't manage CorDapps via Liquibase but use the hibernate hbm2ddl=update.
            val changelogList = schemas.map { mappedSchema ->
                val resource = getMigrationResource(mappedSchema, classLoader)
                when {
                    resource != null -> resource
                    else -> throw MissingMigrationException(mappedSchema)
                }
            }

            val customResourceAccessor = CustomResourceAccessor(dynamicInclude, changelogList, classLoader)

            val liquibase = Liquibase(dynamicInclude, customResourceAccessor, getLiquibaseDatabase(JdbcConnection(connection)))

            val unRunChanges = liquibase.listUnrunChangeSets(Contexts(), LabelExpression())

            when {
                (run && !check) && (unRunChanges.isNotEmpty() && existingCheckpoints!!) -> throw CheckpointsException() // Do not allow database migration when there are checkpoints
                run && !check -> liquibase.update(Contexts())
                check && !run && unRunChanges.isNotEmpty() -> throw OutstandingDatabaseChangesException(unRunChanges.size)
                check && !run -> {} // Do nothing will be interpreted as "check succeeded"
                else -> throw IllegalStateException("Invalid usage.")
            }
        }
    }

    private fun getLiquibaseDatabase(conn: JdbcConnection): Database {
        return DatabaseFactory.getInstance().findCorrectDatabaseImplementation(conn)
    }

    /** For existing database created before verions 4.0 add Liquibase support - creates DATABASECHANGELOG and DATABASECHANGELOGLOCK tables and mark changesets are executed. */
    private fun migrateOlderDatabaseToUseLiquibase(existingCheckpoints: Boolean): Boolean {
        //workaround to detect that if Corda finance module is in use then the most recent version with Liquibase migration scripts was deployed
        if (schemas.any { schema ->
                    (schema::class.qualifiedName == "net.corda.finance.schemas.CashSchemaV1" || schema::class.qualifiedName == "net.corda.finance.schemas.CommercialPaperSchemaV1")
                            && schema.migrationResource == null
                })
            throw DatabaseMigrationException("Detected incompatible corda-finance cordapp without database migration scripts, replace the existing corda-finance-VERSION.jar with the latest one.")

        val isExistingDBWithoutLiquibase = dataSource.connection.use {
            (it.metaData.getTables(null, null, "NODE%", null).next() &&
                    !it.metaData.getTables(null, null, "DATABASECHANGELOG%", null).next())
        }
        when {
            isExistingDBWithoutLiquibase && existingCheckpoints -> throw CheckpointsException()
            isExistingDBWithoutLiquibase -> {
                // Virtual file name of the changelog that includes all schemas.
                val dynamicInclude = "master.changelog.json"

                dataSource.connection.use { connection ->
                    // Schema migrations pre release 4.0
                    val preV4Baseline = mutableListOf("migration/common.changelog-init.xml",
                            "migration/node-info.changelog-init.xml",
                            "migration/node-info.changelog-v1.xml",
                            "migration/node-info.changelog-v2.xml",
                            "migration/node-core.changelog-init.xml",
                            "migration/node-core.changelog-v3.xml",
                            "migration/node-core.changelog-v4.xml",
                            "migration/node-core.changelog-v5.xml",
                            "migration/node-core.changelog-pkey.xml",
                            "migration/vault-schema.changelog-init.xml",
                            "migration/vault-schema.changelog-v3.xml",
                            "migration/vault-schema.changelog-v4.xml",
                            "migration/vault-schema.changelog-pkey.xml")

                    if (schemas.any { schema -> schema.migrationResource == "cash.changelog-master" })
                        preV4Baseline.addAll(listOf("migration/cash.changelog-init.xml",
                                "migration/cash.changelog-v1.xml"))

                    if (schemas.any { schema -> schema.migrationResource == "commercial-paper.changelog-master" })
                        preV4Baseline.addAll(listOf("migration/commercial-paper.changelog-init.xml",
                                "migration/commercial-paper.changelog-v1.xml"))

                    if (schemas.any { schema -> schema.migrationResource == "node-notary.changelog-master" })
                        preV4Baseline.addAll(listOf("migration/node-notary.changelog-init.xml",
                                "migration/node-notary.changelog-v1.xml"))

                    val customResourceAccessor = CustomResourceAccessor(dynamicInclude, preV4Baseline, classLoader)
                    val liquibase = Liquibase(dynamicInclude, customResourceAccessor, getLiquibaseDatabase(JdbcConnection(connection)))
                    liquibase.changeLogSync(Contexts(), LabelExpression())
                }
            }
        }
        return isExistingDBWithoutLiquibase
    }
}

open class DatabaseMigrationException(message: String) : IllegalArgumentException(message) {
    override val message: String = super.message!!
}

class MissingMigrationException(@Suppress("MemberVisibilityCanBePrivate") val mappedSchema: MappedSchema) : DatabaseMigrationException(errorMessageFor(mappedSchema)) {
    internal companion object {
        fun errorMessageFor(mappedSchema: MappedSchema): String = "No migration defined for schema: ${mappedSchema.name} v${mappedSchema.version}"
    }
}

class OutstandingDatabaseChangesException(@Suppress("MemberVisibilityCanBePrivate") private val count: Int) : DatabaseMigrationException(errorMessageFor(count)) {
    internal companion object {
        fun errorMessageFor(count: Int): String = "There are $count outstanding database changes that need to be run."
    }
}

class CheckpointsException : DatabaseMigrationException("Attempting to update the database while there are flows in flight. " +
        "This is dangerous because the node might not be able to restore the flows correctly and could consequently fail. " +
        "Updating the database would make reverting to the previous version more difficult. " +
        "Please drain your node first. See: https://docs.corda.net/upgrading-cordapps.html#flow-drains")

class DatabaseIncompatibleException(@Suppress("MemberVisibilityCanBePrivate") private val reason: String) : DatabaseMigrationException(errorMessageFor(reason)) {
    internal companion object {
        fun errorMessageFor(reason: String): String = "Incompatible database schema version detected, please run the node with configuration option database.initialiseSchema=true. Reason: $reason"
    }
}