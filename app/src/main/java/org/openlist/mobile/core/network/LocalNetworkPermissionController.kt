package org.openlist.mobile.core.network

interface LocalNetworkPermissionController {
    fun hasLocalNetworkPermission(): Boolean
    fun requestLocalNetworkPermission(onResult: (Boolean) -> Unit)
}

