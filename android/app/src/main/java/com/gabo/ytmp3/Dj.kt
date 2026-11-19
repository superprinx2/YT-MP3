package com.gabo.ytmp3

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Los estados de ánimo del Modo DJ (con su color para el visualizador). */
enum class Mood(val emoji: String, val label: String, val hue: Float, val color: Int) {
    BICHOTA("💅", "Bichota", 320f, 0xFFFF2D95.toInt()),
    CORTAVENAS("🩸", "Cortavenas", 345f, 0xFF8E0E2E.toInt()),
    DESPECHO("🍻", "Despecho", 30f, 0xFFFF8A3D.toInt()),
    PERREO("🍑", "Perreo", 285f, 0xFFB14DFF.toInt()),
    CRUSH("😍", "Crush", 350f, 0xFFFF5A7A.toInt()),
    BESTIA("💪", "Bestia", 15f, 0xFFFF4A1C.toInt()),
    TRANQUI("🧘", "Tranqui", 165f, 0xFF2EC4B6.toInt()),
    NOSTALGIA("📼", "Nostalgia", 40f, 0xFFFFB627.toInt()),
    GHOSTEADO("👻", "Ghosteado", 230f, 0xFF7B8CFF.toInt()),
    KARAOKE("🎤", "Karaoke", 50f, 0xFFFFD400.toInt()),
    SAD("🥀", "Sad", 260f, 0xFF6C5CE7.toInt()),
    TOMAR("🍺", "Tomar hasta morir", 45f, 0xFFF5A623.toInt());
}

/**
 * Inteligencia del DJ (todo en el celular): diccionario de artistas "infaltables" por género,
 * palabras del título, el análisis de la canción y lo que aprende de ti.
 */
object DjBrain {
    private val dict: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        fun add(g: String, vararg names: String) = names.forEach { m.getOrPut(it) { HashSet() } += g }
        add("cumbia", "grupo 5", "corazón serrano", "corazon serrano", "agua marina", "aguamarina", "armonía 10", "armonia 10",
            "los ángeles azules", "los angeles azules", "hermanos yaipén", "hermanos yaipen", "papillón", "papillon", "los mirlos",
            "bareto", "américa pop", "america pop", "son tentación", "son tentacion", "la única tropical", "la unica tropical",
            "marisol", "dalmiro cuellar", "grupo néctar", "grupo nectar", "ráfaga", "rafaga", "damas gratis", "los palmeras",
            "karicia", "explosión de iquitos", "kaliente", "tommy portugal", "lérida", "estrellita", "corazón valiente")
        add("chicha", "chacalón", "chacalon", "los shapis", "pintura roja", "juaneco", "los destellos", "los mirlos")
        add("salsa", "marc anthony", "héctor lavoe", "hector lavoe", "willie colón", "willie colon", "oscar d'león", "oscar de leon",
            "grupo niche", "gilberto santa rosa", "la india", "frankie ruiz", "eddie santiago", "rubén blades", "ruben blades",
            "joe arroyo", "tito nieves", "víctor manuelle", "victor manuelle", "jerry rivera", "yahaira plasencia",
            "daniela darcourt", "josimar", "tito rojas", "luis enrique", "maelo ruiz", "orquesta candela")
        add("bachata", "romeo santos", "aventura", "prince royce", "frank reyes", "anthony santos", "zacarías ferreira", "monchy")
        add("merengue", "juan luis guerra", "elvis crespo", "los hermanos rosario", "wilfrido vargas", "oro sólido", "oro solido")
        add("reggaeton", "bad bunny", "karol g", "daddy yankee", "j balvin", "ozuna", "anuel", "feid", "maluma", "rauw alejandro",
            "myke towers", "nicky jam", "farruko", "wisin", "yandel", "don omar", "sech", "jhayco", "jhay cortez", "arcángel",
            "arcangel", "de la ghetto", "young miko", "tokischa", "ivy queen", "blessd", "ryan castro", "manuel turizo", "zion",
            "lenny tavárez", "lunay", "mora", "dei v", "kali uchis", "becky g", "natti natasha", "anitta", "j quiles")
        add("trap", "anuel", "eladio carrión", "eladio carrion", "duki", "myke towers", "bad bunny", "quevedo", "bizarrap",
            "paulo londra", "khea", "trueno", "rels b", "c. tangana", "cazzu", "nicki nicole", "tiago pzk")
        add("regional", "vicente fernández", "vicente fernandez", "pepe aguilar", "christian nodal", "alejandro fernández",
            "peso pluma", "grupo frontera", "los tigres del norte", "banda ms", "calibre 50", "carin león", "carin leon",
            "julión álvarez", "natanael cano", "junior h", "fuerza regida", "ana gabriel", "jenni rivera", "los bukis")
        add("balada", "luis miguel", "josé josé", "jose jose", "camilo sesto", "ricardo arjona", "laura pausini", "alejandro sanz",
            "jesse & joy", "jesse y joy", "reik", "sin bandera", "camila", "marco antonio solís", "franco de vita",
            "ricardo montaner", "cristian castro", "rocío dúrcal", "rocio durcal", "yuridia", "pablo alborán", "pablo alboran",
            "morat", "sebastián yatra", "sebastian yatra", "camilo", "río roma", "rio roma", "gian marco", "eva ayllón")
        add("pop", "taylor swift", "dua lipa", "the weeknd", "ed sheeran", "ariana grande", "billie eilish", "olivia rodrigo",
            "harry styles", "bruno mars", "shakira", "selena gomez", "justin bieber", "sabrina carpenter", "tate mcrae",
            "chappell roan", "lady gaga", "rosalía", "rosalia", "miley cyrus", "katy perry", "rihanna", "beyoncé", "adele",
            "sza", "doja cat", "lana del rey", "aitana", "tini", "danna paola", "emilia", "ashe", "malcolm todd")
        add("rock", "queen", "guns n' roses", "ac/dc", "metallica", "nirvana", "linkin park", "soda stereo", "maná", "mana",
            "los prisioneros", "enanitos verdes", "héroes del silencio", "heroes del silencio", "caifanes", "arctic monkeys",
            "coldplay", "imagine dragons", "the killers", "foo fighters", "green day", "red hot chili peppers", "café tacvba",
            "cafe tacvba", "zoé", "zoe", "libido", "los bunkers", "bon jovi", "the strokes", "radiohead", "muse")
        add("electronica", "david guetta", "calvin harris", "avicii", "martin garrix", "marshmello", "tiësto", "tiesto", "alok",
            "bizarrap", "skrillex", "kygo", "fred again", "daft punk", "swedish house mafia", "peggy gou")
        add("criolla", "chabuca granda", "eva ayllón", "eva ayllon", "arturo cavero", "lucha reyes", "los morochucos")
        add("huayno", "dina páucar", "dina paucar", "sonia morales", "max castro", "william luna", "amanda portales")
        add("rap", "eminem", "drake", "kendrick lamar", "canserbero", "residente", "travis scott", "kanye", "50 cent", "j cole")
        add("kpop", "bts", "blackpink", "twice", "stray kids", "newjeans", "le sserafim", "aespa")
        m
    }

    private val titleGenres = mapOf(
        "cumbia" to "cumbia", "chicha" to "chicha", "salsa" to "salsa", "bachata" to "bachata", "huayno" to "huayno",
        "reggaeton" to "reggaeton", "reggaetón" to "reggaeton", "perreo" to "reggaeton", "dembow" to "reggaeton",
        "trap" to "trap", "balada" to "balada", "ranchera" to "regional", "corrido" to "regional", "merengue" to "merengue",
        "rock" to "rock", "remix" to "electronica", "edm" to "electronica", "festejo" to "criolla", "vals" to "criolla")

    fun genres(t: Track): Set<String> {
        val hay = "${t.artist} ${t.title}".lowercase()
        val out = HashSet<String>()
        for ((name, g) in dict) if (hay.contains(name)) out += g
        for ((w, g) in titleGenres) if (hay.contains(w)) out += g
        return out
    }

    private fun words(t: Track, vararg w: String): Boolean { val s = t.title.lowercase(); return w.any { s.contains(it) } }

    /** Puntaje de una canción para un estado de ánimo (más alto = encaja mejor). */
    fun score(t: Track, m: Mood, learned: Float): Float {
        val g = genres(t)
        val bpm = t.bpm ?: 110f
        val e = (((t.energy ?: 0.15f) - 0.05f) / 0.2f).coerceIn(0f, 1f)
        val sad = Analyzer.tag(t, "Sad music"); val happy = Analyzer.tag(t, "Happy music")
        val tender = Analyzer.tag(t, "Tender music"); val exc = Analyzer.tag(t, "Exciting music"); val angry = Analyzer.tag(t, "Angry music")
        val latin = Analyzer.tag(t, "Music of Latin America") + Analyzer.tag(t, "Salsa music")
        val hiphop = Analyzer.tag(t, "Hip hop music") + Analyzer.tag(t, "Reggae")
        val singing = Analyzer.tag(t, "Singing")
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun has(vararg x: String) = x.any { it in g }
        var s = when (m) {
            Mood.PERREO -> (if (has("reggaeton")) 4f else 0f) + (if (has("trap")) 1.5f else 0f) + (if (bpm in 84f..106f) 2f else 0f) +
                e * 2f + hiphop * 3f + (if (words(t, "perreo", "dembow", "bellac", "twerk")) 2f else 0f)
            Mood.BICHOTA -> (if (has("reggaeton", "trap", "pop")) 3f else 0f) + e * 1.5f + (happy + exc) * 3f +
                (if (words(t, "bichota", "jefa", "poder", "bandida", "diabla", "mala", "queen", "boss")) 2.5f else 0f)
            Mood.CORTAVENAS -> sad * 6f + (if (bpm < 95f) 2f else 0f) + (1 - e) * 2f + (if (has("balada", "bachata")) 2f else 0f) +
                (if (words(t, "llor", "dolor", "adiós", "adios", "sin ti", "duele", "triste", "olvidarte", "me dejaste", "morir")) 2.5f else 0f)
            Mood.SAD -> sad * 5f + tender * 2f + (if (bpm < 112f) 1f else 0f) + (1 - e) * 1f + (if (has("pop", "balada")) 1f else 0f) +
                (if (words(t, "sad", "alone", "sola", "solo", "tears", "lonely", "cry")) 2f else 0f)
            Mood.DESPECHO -> (if (has("balada", "bachata", "regional", "salsa", "cumbia")) 2.5f else 0f) + sad * 3f +
                (if (words(t, " ex", "olvid", "traición", "traicion", "engañ", "mentir", "despecho", "tusa", "no vuelvas", "otra vez")) 3f else 0f)
            Mood.CRUSH -> (tender + happy) * 4f + (if (bpm in 68f..116f) 1f else 0f) + (if (has("pop", "balada", "reggaeton", "bachata")) 1.5f else 0f) +
                (if (words(t, "amor", "beso", "enamor", "love", "corazón", "corazon", "mi vida", "bebé", "baby", "contigo", "crush")) 3f else 0f)
            Mood.BESTIA -> e * 3f + (if (bpm > 120f) 2f else 0f) + (if (has("rock", "rap", "trap", "electronica")) 2f else 0f) + (exc + angry) * 3f +
                (if (words(t, "gym", "fuerza", "power", "beast", "workout")) 2f else 0f)
            Mood.TRANQUI -> (1 - e) * 3f + (if (bpm < 100f) 1.5f else 0f) + tender * 3f +
                (if (words(t, "chill", "relax", "lofi", "lo-fi", "acoustic", "acústico", "acustico")) 2f else 0f)
            Mood.NOSTALGIA -> (if (t.playCount >= 5 && now - t.lastPlayed > 30 * day) 5f else 0f) +
                (if (t.playCount >= 3 && now - t.lastPlayed > 90 * day) 2f else 0f) + min(t.playCount, 20) * 0.1f +
                (if (has("balada", "rock", "criolla")) 0.5f else 0f)
            Mood.GHOSTEADO -> (sad + angry) * 3f + (if (bpm in 80f..120f) 1f else 0f) + (if (has("reggaeton", "pop", "trap")) 1f else 0f) +
                (if (words(t, "visto", "ghost", "olvidaste", "mentiste", "mentira", "no me escribes", "desapareciste", "bloque", "seen")) 3f else 0f)
            Mood.KARAOKE -> min(t.playCount, 15) * 0.25f + singing * 3f + (if (has("pop", "balada", "rock")) 2f else 0f) + (if (e in 0.3f..0.8f) 1f else 0f)
            Mood.TOMAR -> (if (has("cumbia", "chicha", "salsa", "huayno", "merengue", "regional", "criolla")) 5f else 0f) + latin * 3f +
                (if (words(t, "cerveza", "trago", "borrach", "copa", "chela", "tomar", "licor", "cantina", "tequila", " ron", "brindis")) 3f else 0f) + sad * 0.5f
        }
        s += learned * 1.5f
        return s
    }

    /** ¿Cuántas canciones encajan bien? (para sugerir descargar más). */
    fun genreHint(m: Mood): String? = when (m) {
        Mood.TOMAR -> "cumbia"; Mood.PERREO -> "reggaetón"; Mood.DESPECHO -> "despecho"; Mood.CORTAVENAS -> "baladas tristes"
        Mood.BESTIA -> "música para entrenar"; Mood.TRANQUI -> "música relajante"; Mood.CRUSH -> "canciones románticas"
        else -> null
    }

    // ------------------------------------------------------------------ aprendizaje
    suspend fun learn(ctx: Context, trackId: Long, mood: Mood?, delta: Float) {
        val key = mood?.name ?: "DJ"
        val dao = Library.dao(ctx)
        val cur = dao.moodScore(trackId, key)?.score ?: 0f
        dao.putMoodScore(MoodScore(trackId, key, (cur + delta).coerceIn(-5f, 5f)))
    }

    suspend fun learned(ctx: Context, ids: List<Long>, mood: Mood?): Map<Long, Float> {
        val key = mood?.name ?: "DJ"
        return Library.dao(ctx).moodScores(ids).filter { it.mood == key || it.mood == "DJ" }
            .groupBy { it.trackId }.mapValues { (_, v) -> v.sumOf { s -> s.score.toDouble() }.toFloat() }
    }
}

/** Una sesión de DJ: elige la siguiente canción con un ritmo parecido y arma "lo que viene". */
class DjSession(val tracks: List<Track>, val mood: Mood?, scores: Map<Long, Float>) {
    val pool: List<Track>
    private val played = HashSet<Long>()
    val plan = ArrayDeque<Pair<Track, Float>>()   // (canción, velocidad)
    var effBpm = 120f

    init {
        val usable = tracks.filter { (it.durationMs == 0L || it.durationMs > 60_000) }
        pool = if (mood == null) usable.ifEmpty { tracks }
        else {
            val ranked = usable.map { it to DjBrain.score(it, mood, scores[it.id] ?: 0f) }.sortedByDescending { it.second }
            val top = ranked.firstOrNull()?.second ?: 0f
            val good = ranked.filter { it.second >= max(2f, top * 0.35f) }.map { it.first }
            (if (good.size >= 12) good else ranked.take(max(12, ranked.size / 3)).map { it.first }).ifEmpty { tracks }
        }
    }

    fun first(start: Track?): Track {
        val t = start ?: pool.random()
        played += t.id; effBpm = t.bpm ?: 120f
        refill(t)
        return t
    }

    /** Pasa a la siguiente del plan. Devuelve la canción y la velocidad a usar. */
    fun advance(cur: Track): Pair<Track, Float> {
        if (plan.isEmpty()) refill(cur)
        val n = plan.removeFirst()
        played += n.first.id
        effBpm = (n.first.bpm ?: effBpm) * n.second * folding(n.first.bpm ?: effBpm, effBpm)
        refill(n.first)
        return n
    }

    private fun folding(b: Float, ref: Float): Float {
        val r = b / ref
        return when { r > 1.45f -> 0.5f; r < 0.7f -> 2f; else -> 1f }
    }

    private fun refill(cur: Track) {
        var last = plan.lastOrNull()?.first ?: cur
        var ref = if (plan.isEmpty()) effBpm else (plan.last().first.bpm ?: effBpm) * plan.last().second
        val used = HashSet(played).apply { plan.forEach { add(it.first.id) } }
        while (plan.size < 5) {
            var cands = pool.filter { it.id !in used }
            if (cands.isEmpty()) { played.clear(); used.clear(); used += last.id; cands = pool.filter { it.id != last.id } }
            if (cands.isEmpty()) break
            val r0 = ref
            val best = cands.sortedBy { c ->
                val b = c.bpm ?: r0
                val f = folding(b, r0)
                abs(ln((b * f) / r0))
            }.take(3).random()
            val b = best.bpm ?: ref
            val f = folding(b, ref)
            val speed = (ref / (b * f)).coerceIn(0.94f, 1.06f)
            plan.addLast(best to speed)
            used += best.id
            last = best
            ref = b * f * speed
        }
    }

    /** Desde dónde empieza (el coro) y dónde cambia (la primera pausa/bajón después de 45 s). */
    fun segment(t: Track): Pair<Long, Long> {
        val dur = if (t.durationMs > 0) t.durationMs else 210_000L
        val start = (t.climaxMs ?: (dur * 0.3).toLong()).coerceIn(0, max(0, dur - 70_000))
        val gaps = Analyzer.gaps(t)
        val minEnd = start + 45_000; val maxEnd = start + 120_000
        val end = gaps.firstOrNull { it in minEnd..maxEnd } ?: (start + 90_000)
        return start to min(end, dur - 3_000)
    }
}

/** Efectos de transición estilo DJ, generados por la app (sin derechos de autor). */
object DjFx {
    private const val SR = 44100
    val names = listOf("Barrido", "Whoosh", "Scratch", "Subida", "Platillo al revés", "Frenado de casete",
        "Eco", "Golpe de bajo", "Ráfaga", "Redoble", "Bocina DJ")
    private var pool: SoundPool? = null
    private val ids = IntArray(names.size)
    private var lastFx = -1

    fun preload(ctx: Context) {
        if (pool != null) return
        val dir = File(ctx.cacheDir, "djfx").apply { mkdirs() }
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
        names.indices.forEach { i ->
            val f = File(dir, "fx_v1_$i.wav")
            if (!f.exists()) writeWav(f, synth(i))
            ids[i] = p.load(f.absolutePath, 1)
        }
        pool = p
    }

    fun play(ctx: Context, volume: Float) {
        preload(ctx)
        var i: Int
        do { i = Random.nextInt(names.size) } while (i == lastFx && names.size > 1)
        lastFx = i
        pool?.play(ids[i], volume, volume, 1, 0, 1f)
    }

    fun release() { pool?.release(); pool = null }

    private fun synth(kind: Int): FloatArray {
        val r = Random(kind * 97 + 3)
        fun noise() = r.nextFloat() * 2 - 1
        val dur = when (kind) { 3 -> 2.2; 9 -> 1.8; 10 -> 1.6; else -> 1.4 }
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        // filtro de estado variable para barridos
        var low = 0f; var band = 0f
        fun svf(x: Float, fc: Float, q: Float): Float {
            val f = (2 * sin(PI * min(fc, 18000f) / SR)).toFloat()
            low += f * band; val high = x - low - q * band; band += f * high
            return band
        }
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val p = t / dur
            out[i] = when (kind) {
                0 -> svf(noise(), (200 * (8000.0 / 200).pow(p)).toFloat(), 0.25f) * (sin(PI * p)).toFloat() * 1.6f
                1 -> svf(noise(), (300 + 5000 * sin(PI * p)).toFloat(), 0.5f) * (sin(PI * p).pow(2)).toFloat() * 1.8f
                2 -> { phase += 2 * PI * (320 + 260 * sin(2 * PI * 7 * t)) / SR
                    val saw = ((phase / PI) % 2 - 1).toFloat()
                    (saw * 0.6f + noise() * 0.25f) * (if ((t * 14).toInt() % 2 == 0) 1f else 0.2f) * (1 - p).toFloat() }
                3 -> { phase += 2 * PI * (180 * 9.0.pow(p)) / SR
                    (((phase / PI) % 2 - 1).toFloat() * 0.35f + svf(noise(), (500 + 9000 * p).toFloat(), 0.4f) * 0.6f) * p.toFloat() }
                4 -> { val hp = noise() - svf(noise(), 6000f, 0.7f); hp * exp(5 * (p - 1)).toFloat() * (if (p > 0.97) 0f else 1f) }
                5 -> { val f = 110 * exp(-4.5 * p); phase += 2 * PI * f / SR
                    ((sin(phase) + 0.5 * sin(phase * 1.5) + 0.3 * sin(phase * 2)).toFloat()) * 0.45f * (1 - p).toFloat() }
                6 -> { var s = 0f
                    for (k in 0 until 6) { val d = t - k * 0.18; if (d >= 0 && d < 0.12) s += (0.55.pow(k) * (noise() * 0.6 + sin(2 * PI * 180 * d) * 0.7) * exp(-d * 30)).toFloat() }
                    s }
                7 -> { val f = 120 * exp(-1.6 * p) + 35; phase += 2 * PI * f / SR
                    (kotlin.math.tanh(sin(phase) * 2.5)).toFloat() * 0.7f * (1 - p).toFloat() }
                8 -> svf(noise(), 2500f, 0.3f) * (if (((t * 16).toInt()) % 2 == 0) 1f else 0f) * (1 - p).toFloat() * 2f
                9 -> { val rate = 8 + 26 * p; val ph = (t * rate) % 1.0
                    (noise() * exp(-ph * 18) * (0.3 + 0.7 * p)).toFloat() }
                else -> { // bocina: "ba-ba-baaaa"
                    val on = (t < 0.18) || (t in 0.26..0.44) || (t in 0.52..1.5)
                    phase += 2 * PI * (415 + 6 * sin(2 * PI * 6 * t)) / SR
                    var s = 0.0; for (h in 1..6) s += sin(phase * h * (if (h % 2 == 0) 1.005 else 1.0)) / h
                    if (on) (kotlin.math.tanh(s * 1.8) * 0.55).toFloat() else 0f }
            }
        }
        // normalizar y suavizar bordes
        var peak = 0.001f; for (x in out) peak = max(peak, abs(x))
        val fade = SR / 100
        for (i in out.indices) {
            var g = 0.85f / peak
            if (i < fade) g *= i / fade.toFloat()
            if (i > n - fade) g *= (n - i) / fade.toFloat()
            out[i] *= g
        }
        return out
    }

    private fun writeWav(f: File, data: FloatArray) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            val bytes = data.size * 2
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            raf.write("RIFF".toByteArray()); raf.write(le32(36 + bytes)); raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray()); raf.write(le32(16)); raf.write(le16(1)); raf.write(le16(1))
            raf.write(le32(SR)); raf.write(le32(SR * 2)); raf.write(le16(2)); raf.write(le16(16))
            raf.write("data".toByteArray()); raf.write(le32(bytes))
            val buf = ByteArray(bytes)
            data.forEachIndexed { i, x -> val v = (x.coerceIn(-1f, 1f) * 32767).toInt(); buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte() }
            raf.write(buf)
        }
    }
}
