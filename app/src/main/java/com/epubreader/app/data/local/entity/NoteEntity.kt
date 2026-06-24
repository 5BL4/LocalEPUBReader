package com.epubreader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.epubreader.app.core.Syncable

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["bookUuid"],
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = HighlightEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["highlightUuid"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("bookUuid"),
        Index("highlightUuid"),
        Index("updatedAt"),
        Index("syncedAt"),
        Index("isDeleted")
    ]
)
data class NoteEntity(
    @PrimaryKey override val uuid: String,
    val bookUuid: String,
    val highlightUuid: String? = null,
    val locator: String? = null,
    val content: String,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean,
    override val syncedAt: Long? = null,
    val userId: String? = null
) : Syncable
