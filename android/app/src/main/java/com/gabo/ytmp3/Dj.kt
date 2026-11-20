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

/** Tipos de mezcla entre canciones (como un DJ de verdad). */
enum class Mix(val label: String) {
    EQ("Mezcla con ecualizador"), BASS("Cambio de bajos"), FILTER("Barrido de filtro"),
    ECHO("Salida con eco"), LOOP("Loop que crece hasta el drop"), CUT("Corte en el golpe"), SPIN("Frenado de disco");
    /** Las dos canciones suenan juntas (hay que alinear los golpes). */
    val overlap get() = this == EQ || this == BASS || this == FILTER || this == LOOP
}

/** Lo que el DJ va a hacer en la próxima mezcla. */
class DjStep(
    val track: Track, val speed: Float,
    /** Parte que sonará (inicio, fin) en ms de la canción. */
    val part: Pair<Long, Long>,
    /** Dónde arranca a sonar (antes del inicio de la parte si las canciones se superponen). */
    val entry: Long,
    val mix: Mix, val lenMs: Long, val fx: Int?,
)

/**
 * Una sesión de DJ infinita: usa TODA la playlist, toma cada vez una parte distinta de cada
 * canción (coros, versos, puentes) y la arma sobre la marcha: compatible en ritmo, tono y
 * energía, con olas de energía y mezclas variadas. Nunca termina.
 */
class DjSession(ctx: Context, tracks: List<Track>, val mood: Mood?, private val scores: Map<Long, Float>) {
    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("dj_parts", Context.MODE_PRIVATE)
    private val byId = LinkedHashMap<Long, Track>().apply { tracks.forEach { put(it.id, it) } }
    private val pool = LinkedHashSet<Long>()
    private val recent = ArrayDeque<Long>()
    val plan = ArrayDeque<Pair<Long, Float>>()   // (canción, velocidad)
    var effBpm = 120f
    private var step = 0
    private var lastMix: Mix? = null
    private val rnd = Random(System.nanoTime())

    val upcoming: List<Track> get() = plan.mapNotNull { byId[it.first] }
    fun track(id: Long) = byId[id]

    init { tracks.forEach { consider(it) }; if (pool.isEmpty()) tracks.forEach { pool += it.id } }

    private fun usable(t: Track) = t.durationMs == 0L || t.durationMs > 50_000

    private fun consider(t: Track) {
        if (!usable(t)) return
        if (mood == null) { pool += t.id; return }
        if (t.analyzed != 1 && DjBrain.genres(t).isEmpty()) { pool += t.id; return }   // aún sin datos: se prueba
        val sc = DjBrain.score(t, mood, scores[t.id] ?: 0f)
        if (sc >= 2f) pool += t.id else pool -= t.id
    }

    /** Llega una canción recién analizada: se suma (o se ajusta) en vivo. */
    fun update(t: Track) {
        if (!byId.containsKey(t.id)) return
        byId[t.id] = t
        consider(t)
    }

    fun first(start: Track?): Track {
        val t = start?.let { byId[it.id] } ?: pool.mapNotNull { byId[it] }.let { l -> l.filter { it.analyzed == 1 }.ifEmpty { l } }.random(rnd)
        remember(t.id); effBpm = t.bpm ?: 120f
        refill()
        return t
    }

    /** Pasa a la siguiente del plan. Devuelve la canción y la velocidad a usar. */
    fun advance(): Pair<Track, Float> {
        if (plan.isEmpty()) refill()
        val (id, sp0) = plan.removeFirst()
        val t = byId[id]!!
        remember(id)
        effBpm = (t.bpm ?: effBpm) * sp0 * folding(t.bpm ?: effBpm, effBpm)
        step++
        refill()
        return t to sp0
    }

    private fun remember(id: Long) {
        recent.addLast(id)
        val keep = (pool.size * 0.7).toInt().coerceIn(1, 400)
        while (recent.size > keep) recent.removeFirst()
    }

    private fun folding(b: Float, ref: Float): Float {
        val r = b / ref
        return when { r > 1.45f -> 0.5f; r < 0.7f -> 2f; else -> 1f }
    }

    private fun camelot(a: String?, b: String?): Float {
        if (a == null || b == null) return 0f
        val na = a.dropLast(1).toIntOrNull() ?: return 0f; val nb = b.dropLast(1).toIntOrNull() ?: return 0f
        val la = a.last(); val lb = b.last()
        val d = ((na - nb) % 12 + 12) % 12; val dist = minOf(d, 12 - d)
        return when {
            na == nb && la == lb -> 1.2f
            na == nb -> 0.9f              // relativa mayor/menor
            la == lb && dist == 1 -> 1.0f // vecina en la rueda
            la == lb && dist == 2 -> 0.3f // subida de energía
            else -> -0.8f
        }
    }

    private fun refill() {
        val all = pool.mapNotNull { byId[it] }
        if (all.isEmpty()) return
        val energies = all.mapNotNull { it.energy }.sorted()
        val lo = energies.getOrNull((energies.size * 0.2).toInt()) ?: 0.08f
        val hi = energies.getOrNull((energies.size * 0.9).toInt().coerceAtMost(energies.size - 1)) ?: 0.2f
        while (plan.size < 5) {
            val last = plan.lastOrNull()?.let { byId[it.first] } ?: byId[recent.lastOrNull() ?: -1]
            val ref = if (plan.isEmpty()) effBpm else (byId[plan.last().first]?.bpm ?: effBpm) * plan.last().second
            val used = HashSet(recent).apply { plan.forEach { add(it.first) } }
            var cands = all.filter { it.id !in used }
            if (cands.isEmpty()) { recent.clear(); recent.addAll(plan.map { it.first }); cands = all.filter { it.id != last?.id && plan.none { p -> p.first == it.id } } }
            if (cands.isEmpty()) cands = all.filter { it.id != last?.id }
            if (cands.isEmpty()) break
            // ola de energía: sube, respira y vuelve a subir
            val k = step + plan.size
            val target = lo + (hi - lo) * (0.5f + 0.45f * sin(k * 2 * PI.toFloat() / 7f))
            val best = cands.map { c ->
                val b = c.bpm ?: ref
                val f = folding(b, ref)
                var s = -abs(ln((b * f) / ref)) * 6f
                s += camelot(last?.keyCam, c.keyCam)
                c.energy?.let { e -> s -= abs(e - target) / max(0.02f, hi - lo) * 1.2f }
                s += (scores[c.id] ?: 0f) * 0.4f
                if (c.analyzed != 1) s -= 1.5f
                s += rnd.nextFloat() * 1.5f
                c to s
            }.sortedByDescending { it.second }.take(3).random(rnd).first
            val b = best.bpm ?: ref
            val f = folding(b, ref)
            val speed = if (best.bpm == null) 1f else (ref / (b * f)).coerceIn(0.94f, 1.06f)
            plan.addLast(best.id to speed)
        }
    }

    // ------------------------------------------------------------------ partes de cada canción
    private fun memory(id: Long): List<Long> = sp.getString(id.toString(), "")!!.split(',').mapNotNull { it.toLongOrNull() }
    private fun rememberPart(id: Long, start: Long) {
        val l = (memory(id) + start).takeLast(6)
        sp.edit().putString(id.toString(), l.joinToString(",")).apply()
    }

    /** Elige una parte de la canción distinta a las últimas que sonaron, ajustada al compás. */
    fun segment(t: Track): Pair<Long, Long> {
        val dur = if (t.durationMs > 0) t.durationMs else 210_000L
        val beat = 60000.0 / (t.bpm ?: 120f)
        val bar = beat * 4
        val grid = (t.beatMs ?: 0L).toDouble()
        fun snap(ms: Double) = (grid + Math.round((ms - grid) / bar) * bar).toLong()
        val r = rnd.nextFloat()
        val (tMin, tMax) = when {
            r < 0.35f -> 30_000L to 50_000L
            r < 0.80f -> 50_000L to 90_000L
            else -> 100_000L to 175_000L     // a veces casi completa
        }
        val mem = memory(t.id)
        fun seen(s: Long) = mem.any { abs(it - s) < 4000 }
        val parts = Analyzer.parts(t).filter { it.len >= 5000 }
        val cands = ArrayList<Triple<Long, Long, Float>>()
        if (parts.size >= 2) {
            for (i in parts.indices) {
                val p = parts[i]
                if (p.type == 'O') continue
                var end = p.end; var j = i
                while (end - p.start < tMin && j + 1 < parts.size && (parts[j + 1].type != 'O' || tMax > 100_000)) { j++; end = parts[j].end }
                if (end - p.start > tMax) end = p.start + tMax
                if (end - p.start < 20_000) continue
                var w = when (p.type) { 'C' -> 3f; 'V' -> 1.5f; else -> if (tMax > 100_000) 1f else 0.4f }
                w += p.energy * 0.15f
                if (seen(p.start)) w *= 0.05f
                cands += Triple(p.start, end, w)
            }
        }
        if (cands.isEmpty()) {
            val starts = listOfNotNull(t.climaxMs, (dur * 0.25).toLong(), (dur * 0.45).toLong(), (dur * 0.12).toLong()) +
                Analyzer.gaps(t).filter { it in 20_000..(dur - 60_000) }
            for (s in starts.distinct()) {
                val st = s.coerceIn(0, max(0, dur - 45_000))
                val gapEnd = Analyzer.gaps(t).firstOrNull { it in (st + tMin)..(st + tMax) }
                val en = gapEnd ?: (st + (tMin + tMax) / 2)
                cands += Triple(st, en, if (seen(st)) 0.05f else if (s == t.climaxMs) 2f else 1f)
            }
        }
        val total = cands.sumOf { it.third.toDouble() }
        var x = rnd.nextDouble() * total
        val pick = cands.firstOrNull { x -= it.third; x <= 0 } ?: cands.last()
        var start = snap(pick.first.toDouble()).coerceIn(0, max(0, dur - 25_000))
        // que dure frases completas (múltiplos de 4 compases)
        val bars = ((pick.second - start) / bar).toInt()
        val phraseBars = if (bars >= 8) bars / 4 * 4 else max(bars, 4)
        var end = (start + phraseBars * bar).toLong()
        end = min(end, dur - 1500)
        if (end - start < 20_000) { start = max(0, end - 30_000) }
        rememberPart(t.id, pick.first)
        return start to end
    }

    // ------------------------------------------------------------------ cómo mezclar
    private fun bucket(t: Track?): String {
        t ?: return "pop"
        val g = DjBrain.genres(t)
        val bpm = t.bpm ?: 110f
        return when {
            g.any { it in setOf("electronica") } || Analyzer.tag(t, "Electronic music") > 0.3f -> "electro"
            g.any { it in setOf("reggaeton", "trap", "rap") } || Analyzer.tag(t, "Hip hop music") > 0.3f -> "urbano"
            g.any { it in setOf("cumbia", "salsa", "merengue", "bachata", "chicha", "huayno", "criolla") } ||
                Analyzer.tag(t, "Music of Latin America") > 0.3f -> "tropical"
            g.any { it in setOf("balada", "regional") } || (bpm < 90f && (t.energy ?: 0.15f) < 0.1f) -> "lento"
            else -> "pop"
        }
    }

    /**
     * Decide la mezcla entre [out] e [inc] como lo haría un DJ en vivo:
     * - Mezclas largas (ecualizador, filtro, loop) solo si la velocidad y el tono combinan.
     * - Si no combinan: salida con eco o corte limpio en el golpe (ahí el choque no se nota).
     * - El corte solo cuando la que entra sube la energía (coro o parte fuerte): si no, suena a error.
     * - La duración sigue las frases: 2, 4 u 8 compases según el género.
     * [creativity] 0..1: más alto = más loops, ecos y cortes; más bajo = mezclas largas y suaves.
     */
    fun mixFor(out: Track?, inc: Track, outSpeed: Float, incPart: Pair<Long, Long>, skip: Boolean, creativity: Float): Triple<Mix, Long, Int?> {
        val b = bucket(out)
        val outBpm = (out?.bpm ?: 120f) * outSpeed
        val inBpm = inc.bpm ?: outBpm
        val ratio = inBpm * folding(inBpm, outBpm) / outBpm
        val tempoClash = abs(ln(ratio)) > 0.065f          // ni ajustando la velocidad se cuadran
        val keyScore = camelot(out?.keyCam, inc.keyCam)
        val keyClash = keyScore < 0f
        val part = Analyzer.parts(inc).minByOrNull { abs(it.start - incPart.first) }
        val lift = part?.type == 'C' || (part?.energy ?: 0) >= 6 || (inc.energy ?: 0f) >= (out?.energy ?: 1f)
        val cr = creativity.coerceIn(0f, 1f)
        val w = HashMap<Mix, Float>()
        fun add(m: Mix, v: Float) { w[m] = (w[m] ?: 0f) + v }
        when {
            skip -> { add(Mix.ECHO, 3f); add(Mix.BASS, 2f); if (lift) add(Mix.CUT, 2f); add(Mix.SPIN, 0.6f + cr) }
            tempoClash -> { add(Mix.ECHO, 4f); if (lift) add(Mix.CUT, 2f + cr * 2); add(Mix.SPIN, 0.3f + cr * 0.7f) }
            keyClash -> { add(Mix.ECHO, 3f); if (lift) add(Mix.CUT, 1.5f + cr); add(Mix.LOOP, 1f + cr * 1.5f); add(Mix.BASS, 1f) }
            else -> when (b) {
                "urbano" -> { add(Mix.BASS, 3f); add(Mix.EQ, 1.5f); add(Mix.LOOP, 1f + cr * 2); add(Mix.ECHO, 1f + cr); if (lift) add(Mix.CUT, 1f + cr * 1.5f); add(Mix.FILTER, 1f) }
                "tropical" -> { add(Mix.EQ, 3f); add(Mix.BASS, 2f); add(Mix.ECHO, 1.5f + cr); add(Mix.LOOP, 0.5f + cr); if (lift) add(Mix.CUT, 0.5f + cr); add(Mix.FILTER, 1f) }
                "electro" -> { add(Mix.EQ, 3f); add(Mix.FILTER, 3f); add(Mix.LOOP, 1f + cr * 2); add(Mix.BASS, 2f); add(Mix.ECHO, 0.5f + cr) }
                "lento" -> { add(Mix.EQ, 3.5f); add(Mix.ECHO, 2f + cr); add(Mix.FILTER, 1f) }
                else -> { add(Mix.EQ, 3f); add(Mix.BASS, 2f); add(Mix.FILTER, 1.5f); add(Mix.ECHO, 1.5f + cr); add(Mix.LOOP, 0.5f + cr * 1.5f); if (lift) add(Mix.CUT, 0.5f + cr) }
            }
        }
        // más suave con creatividad baja
        w[Mix.EQ]?.let { w[Mix.EQ] = it * (1.6f - cr) }
        lastMix?.let { m -> w[m]?.let { w[m] = it * 0.25f } }
        var x = rnd.nextFloat() * w.values.sum()
        val mix = w.entries.firstOrNull { x -= it.value; x <= 0f }?.key ?: Mix.ECHO
        lastMix = mix
        val beat = 60000f / outBpm
        val phrase = when (b) { "electro" -> 32; "urbano" -> 16; else -> 16 }
        val beats = when (mix) {
            Mix.EQ -> if (skip) 8 else phrase
            Mix.BASS -> 8
            Mix.FILTER -> if (b == "electro") 32 else 16
            Mix.LOOP -> 8
            Mix.ECHO -> 4
            else -> 0
        }
        val len = when (mix) {
            Mix.CUT -> 0L
            Mix.SPIN -> 1100L
            else -> (beat * beats).toLong().coerceIn(1500L, 20_000L)
        }
        return Triple(mix, len, null)
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

    fun play(ctx: Context, volume: Float, kind: Int? = null) {
        preload(ctx)
        var i: Int
        if (kind != null) i = kind.coerceIn(names.indices)
        else do { i = Random.nextInt(names.size) } while (i == lastFx && names.size > 1)
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
