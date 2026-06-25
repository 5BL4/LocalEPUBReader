package com.epubreader.app.data.bookimport

import java.io.IOException

class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long
) : IOException("Insufficient storage: need $requiredBytes bytes, available $availableBytes bytes")
