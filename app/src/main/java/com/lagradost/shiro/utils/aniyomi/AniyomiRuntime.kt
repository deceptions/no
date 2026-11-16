package com.lagradost.shiro.utils.aniyomi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

/** Thin SharedPreferences wrapper extensions can resolve through Injekt. */
class SourcePreferences(val sharedPreferences: SharedPreferences)

/**
 * Stands up the dependency-injection graph (Injekt) that Aniyomi and Mihon extensions resolve with
 * `injectLazy()`. Registered lazily on first use, never at app start. Adapted from the Aniyomi project
 * (Apache-2.0) for host-side extension loading.
 */
object AniyomiRuntime {
    @Volatile
    private var registered = false

    @OptIn(ExperimentalSerializationApi::class)
    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = context.applicationContext as Application
            val client = OkHttpClient.Builder()
                .dns(com.lagradost.shiro.utils.DohProvider.buildDns())
                .build()
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingleton(app)
                    addSingleton(NetworkHelper(app, client))
                    addSingleton(Json { ignoreUnknownKeys = true; explicitNulls = false })
                    addSingleton<ProtoBuf>(ProtoBuf)
                    addSingleton(
                        SourcePreferences(app.getSharedPreferences("nekomo_extensions", Context.MODE_PRIVATE))
                    )
                }
            })
            registered = true
        }
    }
}
