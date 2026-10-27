package com.nuvio.app.features.pmdb

import com.nuvio.app.core.storage.DesktopStorage

/** File-backed PublicMetaDB storage (API key per profile, cached id mappings) in the Nuvio data folder. */
internal object DesktopPublicMetaDbPersistence : PublicMetaDbPersistence {
    private val store by lazy { DesktopStorage.store("nuvio_pmdb") }

    override fun read(key: String): String? = store.getString(key)

    override fun write(key: String, value: String?) {
        store.putString(key, value)
    }
}

internal fun installDesktopPublicMetaDbPersistence() {
    PublicMetaDbPlatform.persistence = DesktopPublicMetaDbPersistence
}
