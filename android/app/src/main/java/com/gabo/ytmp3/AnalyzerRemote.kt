package com.gabo.ytmp3

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * El análisis de cada canción corre en un proceso aparte (":ana"). Algunos celulares (p. ej. Huawei) tienen
 * decodificadores de audio que se caen con ciertos archivos: antes eso cerraba toda la app (y al reabrirla volvía a
 * analizar y se cerraba otra vez). Ahora solo muere este proceso auxiliar y esa canción queda "sin analizar".
 */
class AnalyzerRemoteService : Service() {
    private val worker by lazy { HandlerThread("analisis").apply { start() } }
    private val messenger by lazy {
        Messenger(Handler(worker.looper) { msg ->
            val reply = msg.replyTo
            val uri = msg.data?.getString("uri")
            val out = Bundle()
            try {
                val r = Analyzer.analyze(this, Uri.parse(uri))
                out.putString("json", AnalyzerRemote.toJson(r))
            } catch (e: Throwable) {
                out.putString("err", "${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
            runCatching { reply?.send(Message.obtain(null, 2).apply { data = out }) }
            true
        })
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

object AnalyzerRemote {
    private var service: Messenger? = null
    private var conn: ServiceConnection? = null
    private val lock = Mutex()

    private suspend fun connect(ctx: Context): Messenger = withContext(Dispatchers.Main) {
        service?.takeIf { it.binder.isBinderAlive }?.let { return@withContext it }
        reset(ctx)
        suspendCancellableCoroutine { k ->
            val c = object : ServiceConnection {
                override fun onServiceConnected(n: ComponentName?, b: IBinder) {
                    val m = Messenger(b); service = m
                    if (k.isActive) k.resume(m)
                }
                override fun onServiceDisconnected(n: ComponentName?) { service = null }
                override fun onBindingDied(n: ComponentName?) { reset(ctx) }
            }
            conn = c
            val ok = runCatching { ctx.bindService(Intent(ctx, AnalyzerRemoteService::class.java), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!ok && k.isActive) k.resumeWithException(IllegalStateException("no se pudo iniciar el análisis"))
        }
    }

    private fun reset(ctx: Context) {
        conn?.let { c -> runCatching { ctx.unbindService(c) } }
        conn = null; service = null
    }

    /** Analiza en el proceso aparte. Si ese proceso se cae o tarda demasiado, lanza una excepción (la app sigue). */
    suspend fun analyze(ctx: Context, uri: Uri): Analyzer.Result = lock.withLock {
        val app = ctx.applicationContext
        val m = connect(app)
        val done = CompletableDeferred<Bundle>()
        val death = IBinder.DeathRecipient {
            done.completeExceptionally(IllegalStateException("el decodificador del celular se cayó con esta canción"))
            Handler(Looper.getMainLooper()).post { reset(app) }
        }
        runCatching { m.binder.linkToDeath(death, 0) }
        try {
            val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg -> done.complete(msg.data ?: Bundle()); true })
            m.send(Message.obtain(null, 1).apply { data = Bundle().apply { putString("uri", uri.toString()) }; this.replyTo = replyTo })
            val b = withTimeout(240_000) { done.await() }
            b.getString("err")?.let { throw IllegalStateException(it) }
            fromJson(b.getString("json") ?: throw IllegalStateException("sin resultado"))
        } catch (e: TimeoutCancellationException) {
            withContext(Dispatchers.Main) { reset(app) }
            throw IllegalStateException("el análisis tardó demasiado")
        } finally {
            runCatching { m.binder.unlinkToDeath(death, 0) }
        }
    }

    fun toJson(r: Analyzer.Result): String = JSONObject().apply {
        put("gainDb", r.gainDb.toDouble()); put("bpm", r.bpm.toDouble()); put("climaxMs", r.climaxMs)
        put("introMs", r.introMs); put("outroMs", r.outroMs); put("energy", r.energy.toDouble()); put("bright", r.bright.toDouble())
        put("tags", r.tags ?: JSONObject.NULL); put("env", r.env); put("gaps", r.gaps); put("durationMs", r.durationMs)
        put("keyCam", r.keyCam ?: JSONObject.NULL); put("beatMs", r.beatMs); put("sections", r.sections)
    }.toString()

    fun fromJson(s: String): Analyzer.Result = JSONObject(s).run {
        Analyzer.Result(
            getDouble("gainDb").toFloat(), getDouble("bpm").toFloat(), getLong("climaxMs"), getLong("introMs"), getLong("outroMs"),
            getDouble("energy").toFloat(), getDouble("bright").toFloat(), optString("tags").takeIf { !isNull("tags") },
            getString("env"), getString("gaps"), getLong("durationMs"),
            optString("keyCam").takeIf { !isNull("keyCam") }, getLong("beatMs"), getString("sections"))
    }

    /** ¿Este es el proceso auxiliar de análisis? (ahí no se inicia nada más de la app) */
    fun isAnaProcess(ctx: Context): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
                   else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
        return name.endsWith(":ana")
    }
}
