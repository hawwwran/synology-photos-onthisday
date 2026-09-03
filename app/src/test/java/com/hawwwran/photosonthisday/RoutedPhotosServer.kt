package com.hawwwran.photosonthisday

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * A MockWebServer that answers by the call in the form body rather than in enqueue order. The
 * repository fetches both namespaces concurrently, so a queue would hand PERSONAL's timeline to
 * SHARED's count. Routes are matched on `api=`, `method=` and any other `key=value` pairs given.
 */
class RoutedPhotosServer : Dispatcher() {
    val server = MockWebServer().apply { dispatcher = this@RoutedPhotosServer }
    private val routes = ArrayList<Pair<Set<String>, String>>()

    /** The requests answered so far, as their decoded form bodies. */
    val requests = ArrayList<String>()

    fun start() = server.start()
    fun shutdown() = server.close()
    fun url() = server.url("/")

    /** Answer a request whose body carries every one of [params] (e.g. `"api=SYNO.Foto.Browse.Item"`, `"offset=0"`). */
    fun route(vararg params: String, body: String) {
        routes += params.toSet() to body
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = java.net.URLDecoder.decode(request.body?.utf8().orEmpty(), "UTF-8")
        requests += body
        val pairs = body.split('&').toSet()
        val match = routes.lastOrNull { (params, _) -> params.all { it in pairs } }
            ?: return MockResponse.Builder().code(200).body("""{"success":false,"error":{"code":9999}}""").build()
        return MockResponse.Builder().code(200).body(match.second).build()
    }
}
