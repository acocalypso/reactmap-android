package be.mygod.reactmap.follower

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.MainActivity
import be.mygod.reactmap.R
import be.mygod.reactmap.ReactMapHttpEngine
import be.mygod.reactmap.util.findErrorStream
import be.mygod.reactmap.util.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

class LocationSetter(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val CHANNEL_ID = "locationSetter"
        const val CHANNEL_ID_ERROR = "locationSetterError"
        const val CHANNEL_ID_SUCCESS = "locationSetterSuccess"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_TIME = "time"
        private const val ID_STATUS = 3

        val apiUrl get() = app.activeUrl.toUri().buildUpon().apply {
            path("/graphql")
        }.build().toString()

        fun notifyError(message: CharSequence) {
            app.nm.notify(ID_STATUS, NotificationCompat.Builder(app, CHANNEL_ID_ERROR).apply {
                color = app.getColor(R.color.main_orange)
                setCategory(NotificationCompat.CATEGORY_ALARM)
                setContentTitle("Failed to update location")
                setContentText(message)
                setGroup(CHANNEL_ID)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setSmallIcon(R.drawable.ic_notification_sync_problem)
                priority = NotificationCompat.PRIORITY_MAX
                setContentIntent(PendingIntent.getActivity(app, 2,
                    Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }.build())
        }

        private fun timeSpan(from: Long): String {
            var t = (System.currentTimeMillis() - from) * .001
            if (t < 60) return "${t}s"
            if (t < 60 * 60) {
                val s = (t % 60).toInt()
                if (s == 0) return "${t.toInt()}m"
                return "${t.toInt()}m ${s}s"
            }
            t /= 60
            if (t < 24 * 60) {
                val m = (t % 60).toInt()
                if (m == 0) return "${t.toInt()}h"
                return "${t.toInt()}h ${m}m"
            }
            t /= 60
            val h = (t % 24).toInt()
            if (h == 0) return "${t.toInt()}d"
            return "${t.toInt()}d ${h}h"
        }
    }

    override suspend fun doWork() = try {
        val lat = inputData.getDouble(KEY_LATITUDE, Double.NaN)
        val lon = inputData.getDouble(KEY_LONGITUDE, Double.NaN)
        val time = inputData.getLong(KEY_TIME, 0)
        val conn = ReactMapHttpEngine.openConnection(apiUrl) {
            doOutput = true
            requestMethod = "POST"
            addRequestProperty("Content-Type", "application/json")
            outputStream.bufferedWriter().use {
                it.write(JSONObject().apply {
                    put("operationName", "Webhook")
                    put("variables", JSONObject().apply {
                        put("category", "setLocation")
                        put("data", JSONArray(arrayOf(lat, lon)))
                        put("status", "POST")
                    })
                    // epic graphql query yay >:(
                    put("query", "mutation Webhook(\$data: JSON, \$category: String!, \$status: String!) {" +
                            "webhook(data: \$data, category: \$category, status: \$status) {" +
                            "human { current_profile_no name type } } }")
                }.toString())
            }
        }
        when (val code = conn.responseCode) {
            200 -> {
                val response = conn.inputStream.bufferedReader().readText()
                val human = try {
                    val o = JSONObject(response).getJSONObject("data").getJSONObject("webhook").getJSONObject("human")
                    o.getString("type") + ' ' + o.getString("name") + " profile#" + o.getLong("current_profile_no")
                } catch (e: JSONException) {
                    throw Exception(response, e)
                }
                withContext(Dispatchers.Main) {
                    BackgroundLocationReceiver.onLocationSubmitted(Location("bg").apply {
                        latitude = lat
                        longitude = lon
                    })
                }
                app.nm.notify(ID_STATUS, NotificationCompat.Builder(app, CHANNEL_ID_SUCCESS).apply {
                    color = app.getColor(R.color.main_blue)
                    setCategory(NotificationCompat.CATEGORY_STATUS)
                    setContentTitle("Location updated")
                    setGroup(CHANNEL_ID)
                    setSmallIcon(R.drawable.ic_reactmap)
                    priority = NotificationCompat.PRIORITY_MIN
                    setContentIntent(PendingIntent.getActivity(app, 2,
                        Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    setPublicVersion(build())
                    setContentText("$lat, $lon (stale ${timeSpan(time)}) for $human")
                }.build())
                Result.success()
            }
            else -> {
                val error = conn.findErrorStream.bufferedReader().readText()
                val json = JSONObject(error).getJSONArray("errors")
                notifyError((0 until json.length()).joinToString { json.getJSONObject(it).getString("message") })
                if (code == 401 || code == 511) {
                    withContext(Dispatchers.Main) { BackgroundLocationReceiver.stop() }
                    Result.failure()
                } else {
                    Timber.w(Exception(error + code))
                    Result.retry()
                }
            }
        }
    } catch (e: IOException) {
        Timber.d(e)
        Result.retry()
    } catch (_: CancellationException) {
        Result.failure()
    } catch (e: Exception) {
        Timber.w(e)
        notifyError(e.readableMessage)
        Result.failure()
    }

    override suspend fun getForegroundInfo() = ForegroundInfo(2, NotificationCompat.Builder(app, CHANNEL_ID).apply {
        color = app.getColor(R.color.main_blue)
        setCategory(NotificationCompat.CATEGORY_SERVICE)
        setContentTitle("Updating location")
        setContentText("${inputData.getDouble(KEY_LATITUDE, Double.NaN)}, " +
                inputData.getDouble(KEY_LONGITUDE, Double.NaN) + " from " +
                DateUtils.getRelativeTimeSpanString(inputData.getLong(KEY_TIME, 0),
                    System.currentTimeMillis(), 0, DateUtils.FORMAT_ABBREV_RELATIVE))
        setGroup(CHANNEL_ID)
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        setSmallIcon(R.drawable.ic_notification_sync)
        setProgress(0, 0, true)
        priority = NotificationCompat.PRIORITY_LOW
        setContentIntent(PendingIntent.getActivity(app, 2,
            Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONFIGURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
