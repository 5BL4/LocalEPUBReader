package com.epubreader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.epubreader.app.core.Syncable

@Entity(
    tableName = "books",
    indices = [
        Index("updatedAt"),
        Index("syncedAt"),
        Index("isDeleted")
    ]
)
data class BookEntity(
    @PrimaryKey override val uuid: String,
    val title: String,
    val author: String? = null,
    val coverPath: String? = null,
    val filePath: String,
    val fileSize: Long,
    val format: String,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean,
    override val syncedAt: Long? = null,
    val userId: String? = null
) : Syncable
