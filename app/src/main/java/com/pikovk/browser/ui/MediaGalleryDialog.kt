package com.pikovk.browser.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

// Собственная галерея фото и видео (1.3.33, доработка-23) — третий путь
// прикрепления, обходящий ВСЕ системные пикеры. Журнал владельца доказал:
// пикеры самой прошивки на устройстве сломаны (Photo Picker вернул
// RESULT_OK с пустым результатом — ни Data, ни ClipData). Список даёт
// прямой запрос MediaStore, результат — content:// uri выбранных файлов,
// отдаётся странице напрямую через filePathCallback: канал «результат
// активити», в котором у системных пикеров пропадал payload, не
// используется вовсе. Библиотека пуста — MainActivity откатывается на
// системный пикер; отказ в разрешении — тоже.
object MediaGalleryDialog {

    private val io = Executors.newSingleThreadExecutor()
    private const val LIMIT = 500

    // onResult: choice — выбранные uri (null — закрытие), emptyLibrary —
    // в библиотеке нет подходящих медиа (тогда fallback на системный пикер).
    fun show(
        activity: MainActivity,
        images: Boolean,
        videos: Boolean,
        multiple: Boolean,
        onResult: (choice: Array<Uri>?, emptyLibrary: Boolean) -> Unit
    ) {
        io.execute {
            val uris = ArrayList<Uri>()
            if (images) {
                collect(
                    activity.contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    uris
                )
            }
            if (videos) {
                collect(
                    activity.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    uris
                )
            }
            activity.runOnUiThread {
                if (uris.isEmpty()) {
                    onResult(null, true)
                } else {
                    build(activity, uris, multiple, onResult)
                }
            }
        }
    }

    private fun collect(resolver: ContentResolver, collection: Uri, out: ArrayList<Uri>) {
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < LIMIT) {
                    out += ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
        } catch (_: Exception) {
            // запрос не удался — список останется пустым, сработает fallback
        }
    }

    private fun build(
        activity: MainActivity,
        uris: ArrayList<Uri>,
        multiple: Boolean,
        onResult: (Array<Uri>?, Boolean) -> Unit
    ) {
        val sel = LinkedHashSet<Uri>()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val hint = TextView(activity).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            text = "Тап — выбрать, повторный — снять. Выбрано: 0"
        }
        val grid = GridView(activity).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val adapter = object : BaseAdapter() {
            private val thumbs = HashMap<Uri, Bitmap>()
            override fun getCount() = uris.size
            override fun getItem(position: Int) = uris[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = (convertView as? FrameLayout)
                    ?: FrameLayout(parent.context).apply {
                        addView(ImageView(parent.context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                dp(120)
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setImageResource(android.R.drawable.ic_menu_gallery)
                        })
                    }
                val img = cell.getChildAt(0) as ImageView
                val uri = uris[position]
                img.tag = uri
                val cached = thumbs[uri]
                if (cached != null) {
                    img.setImageBitmap(cached)
                } else if (Build.VERSION.SDK_INT >= 29) {
                    io.execute {
                        val bmp = try {
                            activity.contentResolver.loadThumbnail(
                                uri, Size(160, 160), null
                            )
                        } catch (_: Exception) {
                            null
                        }
                        if (bmp != null) {
                            thumbs[uri] = bmp
                            // ячейка могла уйти к другому uri за время загрузки
                            if (img.tag == uri) {
                                activity.runOnUiThread { img.setImageBitmap(bmp) }
                            }
                        }
                    }
                }
                img.alpha = if (uri in sel) 1f else 0.45f
                return cell
            }
        }
        grid.adapter = adapter
        grid.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(grid)
        }

        var finished = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Галерея")
            .setView(root)
            .setPositiveButton("Прикрепить", null)
            .setNegativeButton("Отмена") { _, _ ->
                if (!finished) {
                    finished = true
                    onResult(null, false)
                }
            }
            .setOnCancelListener {
                if (!finished) {
                    finished = true
                    onResult(null, false)
                }
            }
            .create()
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        ok.setOnClickListener {
            if (!finished && sel.isNotEmpty()) {
                finished = true
                onResult(sel.toTypedArray(), false)
                dialog.dismiss()
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val uri = uris[position]
            if (uri in sel) {
                sel.remove(uri)
            } else {
                if (!multiple) sel.clear()
                sel.add(uri)
            }
            hint.text = "Тап — выбрать, повторный — снять. Выбрано: ${sel.size}"
            ok.text = "Прикрепить (${sel.size})"
            ok.isEnabled = sel.isNotEmpty()
            adapter.notifyDataSetChanged()
        }
    }
}
