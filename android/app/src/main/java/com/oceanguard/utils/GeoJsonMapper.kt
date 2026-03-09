package com.oceanguard.ai.utils

import com.oceanguard.ai.data.DetectionSession
import com.oceanguard.ai.data.ZoneCluster
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Converts a list of [DetectionSession]s with non-null location into a GeoJSON
 * FeatureCollection JSON string for MapLibre's GeoJsonSource.
 *
 * Each Feature carries properties used by CircleLayer expressions and click
 * handlers: sessionId, healthScore, totalCount, riskLevel, timestamp.
 */
fun List<DetectionSession>.toGeoJsonFeatureCollection(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    val features = JSONArray()

    for (session in this) {
        val loc = session.location ?: continue
        val geometry = JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().apply {
                put(loc.longitude) // GeoJSON: [lng, lat]
                put(loc.latitude)
            })
        }
        val properties = JSONObject().apply {
            put("sessionId", session.id)
            put("healthScore", session.healthScore)
            put("totalCount", session.totalCount)
            put("riskLevel", session.getRiskLevel().name)
            put("timestamp", formatter.format(session.timestamp))
        }
        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", properties)
        }
        features.put(feature)
    }

    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}

/**
 * Converts a list of [ZoneCluster]s into a GeoJSON FeatureCollection.
 *
 * Each zone becomes a single Point feature at the cluster centroid with
 * aggregate properties: healthScore, totalCount, sessionCount, trend, zoneIndex.
 */
fun List<ZoneCluster>.toZoneGeoJson(): String {
    val features = JSONArray()

    for ((index, zone) in this.withIndex()) {
        val geometry = JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().apply {
                put(zone.centroidLon)
                put(zone.centroidLat)
            })
        }
        val properties = JSONObject().apply {
            put("zoneIndex", index)
            put("healthScore", zone.aggregateHealthScore)
            put("totalCount", zone.sessions.sumOf { it.totalCount })
            put("sessionCount", zone.sessions.size)
            put("trend", zone.trend.name)
        }
        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", geometry)
            put("properties", properties)
        }
        features.put(feature)
    }

    return JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()
}
