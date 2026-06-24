package com.epubreader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.epubreader.app.core.Syncable

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["bookUuid"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("bookUuid"),
        Index("updatedAt"),
        Index("syncedAt"),
        Index("isDeleted")
    ]
)
data class HighlightEntity(
    @PrimaryKey override val uuid: String,
    val bookUuid: String,
    val locator: String,
    val text: String,
    val color: String,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean,
    override val syncedAt: Long? = null,
    val userId: String? = null
) : Syncable
