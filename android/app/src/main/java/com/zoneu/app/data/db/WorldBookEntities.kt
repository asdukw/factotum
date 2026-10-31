package com.zoneu.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Heads survive deletion; content revisions and original import fingerprints are immutable. */
@Entity(tableName = "world_book", indices = [Index(value = ["fingerprint"], unique = true)])
data class WorldBookHeadEntity(
  @PrimaryKey val id: String,
  val revision: String,
  val deleted: Boolean,
  val fingerprint: String?,
)

@Entity(
  tableName = "world_revision",
  primaryKeys = ["bookId", "revision"],
  foreignKeys = [ForeignKey(entity = WorldBookHeadEntity::class, parentColumns = ["id"], childColumns = ["bookId"])],
)
data class WorldBookRevisionEntity(val bookId: String, val revision: String, val payload: String)

/** Removed entry IDs remain reserved, even after book deletion. */
@Entity(
  tableName = "world_entry_identity",
  foreignKeys = [ForeignKey(entity = WorldBookHeadEntity::class, parentColumns = ["id"], childColumns = ["bookId"])],
  indices = [Index("bookId")],
)
data class WorldBookEntryIdentityEntity(
  @PrimaryKey val id: String,
  val bookId: String,
)

@Entity(tableName = "world_role_bindings")
data class WorldRoleBindingsEntity(
  @PrimaryKey val characterId: String,
  val revision: String,
  val payload: String,
)

@Entity(
  tableName = "world_role_reference",
  primaryKeys = ["characterId", "bookId"],
  foreignKeys = [
    ForeignKey(
      entity = WorldRoleBindingsEntity::class,
      parentColumns = ["characterId"],
      childColumns = ["characterId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(entity = WorldBookHeadEntity::class, parentColumns = ["id"], childColumns = ["bookId"]),
  ],
  indices = [Index("bookId")],
)
data class WorldRoleReferenceEntity(val characterId: String, val bookId: String)

@Entity(
  tableName = "world_manifest",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
)
data class WorldManifestEntity(
  @PrimaryKey val sessionId: String,
  val revision: String,
  val payload: String,
)

@Entity(
  tableName = "world_session_reference",
  primaryKeys = ["sessionId", "bookId"],
  foreignKeys = [
    ForeignKey(
      entity = WorldManifestEntity::class,
      parentColumns = ["sessionId"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = WorldBookRevisionEntity::class,
      parentColumns = ["bookId", "revision"],
      childColumns = ["bookId", "revision"],
    ),
  ],
  indices = [Index(value = ["bookId", "revision"])],
)
data class WorldSessionReferenceEntity(val sessionId: String, val bookId: String, val revision: String)

@Entity(
  tableName = "world_runtime_state",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
)
data class WorldRuntimeStateEntity(
  @PrimaryKey val sessionId: String,
  val payload: String,
)

@Entity(
  tableName = "world_request_plan",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("sessionId")],
)
data class WorldRequestPlanEntity(
  @PrimaryKey val requestId: String,
  val sessionId: String,
  val status: String,
  val payload: String,
  val parameterHash: String,
  val evidenceHash: String?,
)

/** Only identities and hashes belong in a receipt, never tool arguments or book content. */
@Entity(tableName = "world_operation_receipt")
data class WorldOperationReceiptEntity(
  @PrimaryKey val requestId: String,
  val operation: String,
  val parameterHash: String,
  val actualHash: String,
  val targetId: String,
  val revision: String,
)

@Entity(tableName = "world_operation_cancellation")
data class WorldOperationCancellationEntity(
  @PrimaryKey val requestId: String,
  val parameterHash: String,
)
