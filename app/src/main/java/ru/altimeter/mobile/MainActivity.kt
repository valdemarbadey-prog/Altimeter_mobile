package ru.altimeter.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.math.ln

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var groundInput: EditText
    private lateinit var cityInput: EditText
    private lateinit var output: TextView
    private lateinit var source: TextView
    private var sensorPressure: Double? = null
    private var weather: Weather? = null
    private var terrainElevation: Double? = null

    data class Weather(val msl: Double, val surface: Double, val temp: Double, val place: String, val lat: Double, val lon: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val barometer = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (barometer != null) {
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) { sensorPressure = event.values[0].toDouble() }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, barometer, SensorManager.SENSOR_DELAY_NORMAL)
            main.postDelayed({ sm.unregisterListener(listener); sensorPressure?.let { if (groundInput.text.isNullOrBlank()) groundInput.setText(fmt(it)) } }, 2000)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24); gravity=Gravity.CENTER_HORIZONTAL }
        root.addView(TextView(this).apply { text="ALTIMETER MOBILE"; textSize=22f; gravity=Gravity.CENTER; setPadding(0,0,0,18) })
        root.addView(TextView(this).apply { text="Давление на земле, hPa"; textSize=15f })
        groundInput = EditText(this).apply { hint="Например, 1001.3"; inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; textSize=22f }
        root.addView(groundInput, LinearLayout.LayoutParams(-1,-2))
        root.addView(Button(this).apply { text="Взять давление с барометра телефона"; setOnClickListener { sensorPressure?.let { groundInput.setText(fmt(it)) } ?: toast("В телефоне нет доступного барометра") } })
        root.addView(TextView(this).apply { text="Город (если геолокация выключена)"; textSize=15f; setPadding(0,16,0,0) })
        cityInput = EditText(this).apply { hint="Название города"; textSize=18f }
        root.addView(cityInput, LinearLayout.LayoutParams(-1,-2))
        root.addView(Button(this).apply { text="Получить GPS и данные Open-Meteo + ASTER"; setOnClickListener { loadData() } })
        root.addView(Button(this).apply { text="Проверить только Open-Meteo"; setOnClickListener { checkMeteo() } })
        root.addView(Button(this).apply { text="Рассчитать высоту"; setOnClickListener { calculate() } })
        source = TextView(this).apply { text="Данные ещё не загружены"; textSize=13f; setPadding(0,12,0,8) }
        root.addView(source)
        output = TextView(this).apply { text="—"; textSize=24f; gravity=Gravity.CENTER; setPadding(0,16,0,0) }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadData() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            source.text = "Разреши геолокацию и нажми кнопку ещё раз. Можно вместо этого ввести город."
            return
        }
        source.text = "Определяю координаты и загружаю данные…"
        worker.execute {
            try {
                val loc = getLocation()
                val w = if (loc != null) fetchWeather(loc.latitude, loc.longitude, "GPS телефона") else fetchCityWeather()
                weather = w
                val terrain = try { fetchAster(w.lat, w.lon) } catch (_: Exception) { null }
                terrainElevation = terrain
                main.post {
                    source.text = w.place + " (" + fmt(w.lat) + ", " + fmt(w.lon) + ")" +
                        "\nOpen-Meteo: " + fmt(w.temp) + " °C · MSL " + fmt(w.msl) + " hPa" +
                        "\nASTER: " + (terrain?.let { fmt(it) + " м (справочно)" } ?: "не удалось получить высоту")
                    calculate()
                }
            } catch (e: Exception) {
                val message = describeError(e)
                main.post { source.text = message }
            }
        }
    }

    private fun checkMeteo() {
        source.text = "Проверяю соединение с Open-Meteo…"
        worker.execute {
            try {
                val loc = if (hasLocationPermission()) getLocation() else null
                val result = if (loc != null) fetchWeather(loc.latitude, loc.longitude, "GPS телефона") else fetchCityWeather()
                weather = result
                main.post {
                    source.text = "Open-Meteo доступен\n" + result.place + " · " + fmt(result.temp) + " °C · MSL " + fmt(result.msl) + " hPa"
                    calculate()
                }
            } catch (e: Exception) {
                main.post { source.text = "Open-Meteo: " + describeError(e) }
            }
        }
    }

    private fun fetchCityWeather(): Weather {
        val city = cityInput.text.toString().trim()
        require(city.isNotEmpty()) { "Геолокация недоступна. Введи город вручную." }
        val encoded = URLEncoder.encode(city, "UTF-8")
        val geoJson = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=ru&format=json"))
        val item = geoJson.optJSONArray("results")?.optJSONObject(0) ?: error("Город «" + city + "» не найден в геокодере Open-Meteo.")
        return fetchWeather(item.getDouble("latitude"), item.getDouble("longitude"), item.optString("name", city))
    }

    private fun fetchWeather(lat: Double, lon: Double, place: String): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,pressure_msl,surface_pressure&timezone=auto"
        val json = JSONObject(get(url))
        if (json.has("error") && json.optBoolean("error")) error("Open-Meteo вернул ошибку: " + json.optString("reason"))
        val current = json.optJSONObject("current") ?: error("Open-Meteo не вернул блок current.")
        return Weather(current.getDouble("pressure_msl"), current.getDouble("surface_pressure"),
            current.getDouble("temperature_2m"), place, lat, lon)
    }

    private fun fetchAster(lat: Double, lon: Double): Double {
        val url = "https://api.opentopodata.org/v1/aster30m?locations=" + lat + "," + lon
        val json = JSONObject(get(url))
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: error("ASTER не вернул результат")
        return result.getDouble("elevation")
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun getLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60 * 1000) return last
        val latch = CountDownLatch(1)
        var found: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { found = location; latch.countDown() }
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            providers.forEach { provider -> try { lm.requestLocationUpdates(provider, 0L, 0f, listener) } catch (_: Exception) {} }
            latch.await(12, TimeUnit.SECONDS)
        } finally {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }
        return found ?: last
    }

    private fun calculate() {
        val w = weather ?: run { toast("Сначала получи данные Open-Meteo"); return }
        val ground = groundInput.text.toString().replace(',', '.').toDoubleOrNull() ?: sensorPressure
            ?: run { toast("Введи давление на земле или используй барометр телефона"); return }
        if (ground <= 0.0 || w.msl <= 0.0) { toast("Давление должно быть больше нуля"); return }
        val altitude = (287.05 * (w.temp + 273.15) / 9.80665) * ln(w.msl / ground)
        output.text = String.format(Locale.getDefault(),
            "Барометрическая высота: %.1f м\n\nGround %.1f hPa   MSL %.1f hPa\nТемпература %.1f °C\nВысота ASTER: %s",
            altitude, ground, w.msl, w.temp, terrainElevation?.let { String.format(Locale.getDefault(), "%.1f м (справочно)", it) } ?: "нет данных")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 12000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "AltimeterMobile/1.1")
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP " + code + ": " + body.take(220))
            body
        } finally { c.disconnect() }
    }

    private fun describeError(e: Exception): String = when (e) {
        is UnknownHostException -> "Не удалось найти сервер (DNS). Возможно, ресурс недоступен в этой сети."
        is SocketTimeoutException -> "Сервер не ответил за 12 секунд. Проверь интернет или доступность сервиса."
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
