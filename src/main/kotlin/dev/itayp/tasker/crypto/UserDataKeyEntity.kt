package dev.itayp.tasker.crypto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_data_key")
open class UserDataKeyEntity {
    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "wrapped_dek", nullable = false)
    var wrappedDek: ByteArray? = null

    @Column(name = "kek_version", nullable = false)
    var kekVersion: Short = 1

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}
