package com.epubreader.app.navigation

import kotlinx.serialization.Serializable

@Serializable
object BookshelfRoute

@Serializable
data class ReaderRoute(val bookUuid: String)

@Serializable
object LogViewerRoute
