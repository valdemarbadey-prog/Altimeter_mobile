package ru.altimeter.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
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
import java.util.Locale
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
    data class Weather(val msl: Double, val surface: Double, val temp: Double, val place: String)

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
            main.postDelayed({ sm.unregisterListener(listener); sensorPressure?.let { groundInput.setText(fmt(it)) } }, 1500)
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
        root.addView(Button(this).apply { text="Определить место и получить погоду"; setOnClickListener { loadWeather() } })
        root.addView(Button(this).apply { text="Рассчитать высоту"; setOnClickListener { calculate() } })
        source = TextView(this).apply { text="Данные погоды ещё не загружены"; textSize=12f; setPadding(0,12,0,8) }
        root.addView(source)
        output = TextView(this).apply { text="—"; textSize=26f; gravity=Gravity.CENTER; setPadding(0,16,0,0) }
        root.addView(output)
        setContentView(ScrollView(this).apply { addView(root) })
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
    }

    private fun loadWeather() {
        source.text="Получаю данные…"
        worker.execute {
            try {
                val loc = getLocation()
                val w = if (loc != null) fetchWeather(loc.latitude, loc.longitude, "По координатам телефона")
                else {
                    val city = cityInput.text.toString().trim()
                    require(city.isNotEmpty()) { "Включи геолокацию или введи город" }
                    val encoded = URLEncoder.encode(city, "UTF-8")
                    val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ru&format=json"))
                    val item = geo.getJSONArray("results").optJSONObject(0) ?: error("Город не найден")
                    fetchWeather(item.getDouble("latitude"), item.getDouble("longitude"), item.optString("name",city))
                }
                weather=w
                main.post { source.text = w.place + " · " + fmt(w.temp) + " °C · MSL " + fmt(w.msl) + " hPa · Ground модели " + fmt(w.surface) + " hPa"; calculate() }
            } catch (e: Exception) { main.post { source.text="Ошибка загрузки данных" } }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double, place: String): Weather {
        val url="https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,pressure_msl,surface_pressure&timezone=auto"
        val current=JSONObject(get(url)).getJSONObject("current")
        return Weather(current.getDouble("pressure_msl"),current.getDouble("surface_pressure"),current.getDouble("temperature_2m"),place)
    }

    private fun getLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm=getSystemService(LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
    }

    private fun calculate() {
        val w=weather ?: run { toast("Сначала получи давление и температуру из интернета"); return }
        val ground=groundInput.text.toString().replace(',','.').toDoubleOrNull() ?: sensorPressure ?: w.surface
        val altitude=(287.05*(w.temp+273.15)/9.80665)*ln(w.msl/ground)
        output.text=String.format(Locale.getDefault(),"%.1f м над уровнем моря\nGround %.1f hPa   MSL %.1f hPa\nТемпература %.1f °C",altitude,ground,w.msl,w.temp)
    }

    private fun get(url: String): String {
        val c=URL(url).openConnection() as HttpURLConnection
        c.connectTimeout=10000; c.readTimeout=10000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    private fun fmt(v: Double)=String.format(Locale.US,"%.1f",v)
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}