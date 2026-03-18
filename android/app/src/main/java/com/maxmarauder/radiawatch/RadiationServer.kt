package com.maxmarauder.radiawatch

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class RadiationServer(port: Int) : NanoHTTPD(port) {

    @Volatile
    var doseRate: Double = 0.0

    @Volatile
    var cps: Int = 0

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/radiation" -> {
                val json = JSONObject().apply {
                    put("usvh", doseRate)
                    put("cps", cps)
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}
