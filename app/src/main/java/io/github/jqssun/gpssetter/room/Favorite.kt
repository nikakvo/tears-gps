package io.github.jqssun.gpssetter.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Favorite(
    @PrimaryKey(autoGenerate = false)
    val id: Long? = null,
    val address: String?,
    val lat: Double?,
    val lng: Double?
)