package com.homeassisthub.client.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.json.JSONArray

/**
 * Bridges org.json responses coming from the Socket.IO relay into the
 * existing Moshi-annotated DTOs, so both the (LAN) Retrofit path and the
 * (remote-capable) Socket.IO path share the same model classes.
 */
object JsonParsing {

    private val moshi = Moshi.Builder().build()

    fun <T> parseList(jsonArray: JSONArray?, clazz: Class<T>): List<T> {
        if (jsonArray == null) return emptyList()
        val type = Types.newParameterizedType(List::class.java, clazz)
        val adapter = moshi.adapter<List<T>>(type)
        return adapter.fromJson(jsonArray.toString()).orEmpty()
    }
}
