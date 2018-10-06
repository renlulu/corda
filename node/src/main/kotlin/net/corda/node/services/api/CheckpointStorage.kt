package net.corda.node.services.api

import net.corda.core.flows.StateMachineRunId
import net.corda.core.serialization.SerializedBytes
import net.corda.node.services.statemachine.Checkpoint
import java.sql.Connection
import java.util.stream.Stream

/**
 * Thread-safe storage of fiber checkpoints.
 */
interface CheckpointStorage {
    /**
     * Add a checkpoint for a new id to the store. Will throw if there is already a checkpoint for this id
     */
    fun addCheckpoint(id: StateMachineRunId, checkpoint: SerializedBytes<Checkpoint>)

    /**
     * Update an existing checkpoint. Will throw if there is not checkpoint for this id.
     */
    fun updateCheckpoint(id: StateMachineRunId, checkpoint: SerializedBytes<Checkpoint>)

    /**
     * Remove existing checkpoint from the store.
     * @return whether the id matched a checkpoint that was removed.
     */
    fun removeCheckpoint(id: StateMachineRunId): Boolean

    /**
     * Load an existing checkpoint from the store.
     * @return the checkpoint, still in serialized form, or null if not found.
     */
    fun getCheckpoint(id: StateMachineRunId): SerializedBytes<Checkpoint>?

    /**
     * Stream all checkpoints from the store. If this is backed by a database the stream will be valid until the
     * underlying database connection is closed, so any processing should happen before it is closed.
     */
    fun getAllCheckpoints(): Stream<Pair<StateMachineRunId, SerializedBytes<Checkpoint>>>

    /**
     * This needs to run before Hibernate is initialised.
     *
     * @param connection The SQL Connection.
     * @return the number of checkpoints stored in the database.
     */
    fun getCheckpointCount(connection: Connection): Long
}