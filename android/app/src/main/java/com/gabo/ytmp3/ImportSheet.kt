package com.gabo.ytmp3

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "+": elegir una carpeta del celular, marcar canciones y agregarlas a una playlist (sin copiarlas). */
object ImportSheet {

    val permission: String get() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(c: Context) = ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    fun start(a: AppCompatActivity, playlistId: Long?) {
        if (granted(a)) { pickFolder(a, playlistId); return }
        var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
        launcher = a.activityResultRegistry.register("import_perm_${System.nanoTime()}", ActivityResultContracts.RequestPermission()) { ok ->
            launcher?.unregister()
            if (ok) pickFolder(a, playlistId)
            else Toast.makeText(a, "Sin permiso no podemos ver la música del celular", Toast.LENGTH_LONG).show()
        }
        launcher.launch(permission)
    }

    private fun pickFolder(a: AppCompatActivity, playlistId: Long?) {
        a.lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) { Library.scanDevice(a) }
            if (songs.isEmpty()) { Toast.makeText(a, "No encontramos música en el celular", Toast.LENGTH_LONG).show(); return@launch }
            val folders = songs.groupBy { it.folder }.toList().sortedByDescending { it.second.size }
            val labels = listOf("Toda la música (${songs.size})") + folders.map { "📁 ${it.first} (${it.second.size})" }
            ActionSheet.show(a, "¿De qué carpeta?", labels) { i ->
                val chosen = if (i == 0) songs else folders[i - 1].second
                pickSongs(a, chosen, playlistId, if (i == 0) "Mi música" else folders[i - 1].first)
            }
        }
    }

    private fun pickSongs(a: AppCompatActivity, songs: List<Library.LocalSong>, playlistId: Long?, folderName: String) {
        val checked = BooleanArray(songs.size) { true }
        val names = songs.map { s -> if (s.artist.isNotEmpty()) "${s.title} · ${s.artist}" else s.title }.toTypedArray()
        MaterialAlertDialogBuilder(a)
            .setTitle("Elige las canciones")
            .setMultiChoiceItems(names, checked) { _, w, v -> checked[w] = v }
            .setPositiveButton("Agregar") { _, _ ->
                val pick = songs.filterIndexed { i, _ -> checked[i] }
                if (pick.isEmpty()) return@setPositiveButton
                if (playlistId != null) import(a, pick, setOf(playlistId))
                else PlaylistPicker.show(a, "¿En qué playlist?", emptySet(), folderName, "Agregar") { ids, _ -> import(a, pick, ids) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun import(a: AppCompatActivity, songs: List<Library.LocalSong>, playlists: Set<Long>) {
        a.lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) { Library.importLocal(a, songs) }
            Library.addToPlaylists(a, ids, playlists)
            Toast.makeText(a, "${ids.size} ${if (ids.size == 1) "canción agregada" else "canciones agregadas"} ✓", Toast.LENGTH_SHORT).show()
        }
    }
}
