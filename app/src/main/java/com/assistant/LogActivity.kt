package com.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.assistant.diagnostic.RuntimeLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class LogActivity : AppCompatActivity() {
    private lateinit var groups: LinearLayout
    private val expanded = mutableSetOf<Long>()
    private val pages = mutableMapOf<Long, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            RuntimeLogger.reconcileExpired()
            renderHeaders()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuntimeLogger.reconcileExpired()
        setContentView(buildRoom())
        renderHeaders()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun buildRoom(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        root.addView(TextView(this).apply {
            text="Welcome to Log Room"; textSize=20f; gravity=Gravity.CENTER
            setTypeface(null,Typeface.BOLD); setPadding(12,18,12,18)
        })
        groups=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(groups) },
            LinearLayout.LayoutParams(-1,0,1f))
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(Button(this@LogActivity).apply {
                text="Delete All Logs"
                setOnClickListener {
                    if(RuntimeLogger.deleteAllOwnedLogs()) {
                        expanded.clear(); pages.clear(); renderHeaders()
                        toast("All logs deleted")
                    } else toast("Delete failed")
                }
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(Button(this@LogActivity).apply {
                text="Back"; setOnClickListener { finish() }
            },LinearLayout.LayoutParams(0,-2,1f))
        })
        return root
    }

    private fun renderHeaders() {
        val buckets=RuntimeLogger.hourBuckets()
        val valid=buckets.map { it.hourStart }.toSet()
        expanded.retainAll(valid)
        groups.removeAllViews()
        if(buckets.isEmpty()) {
            groups.addView(TextView(this).apply { text="No logs available." })
            return
        }
        buckets.forEach { bucket ->
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; setPadding(8,12,8,12)
            }
            card.addView(Button(this).apply {
                text="${hour.format(Date(bucket.hourStart))} ${if(bucket.hourStart in expanded)"▲" else "▼"}"
                setOnClickListener {
                    if(!expanded.add(bucket.hourStart)) expanded.remove(bucket.hourStart)
                    pages.putIfAbsent(bucket.hourStart,0); renderHeaders()
                }
            })
            val remaining=max(0L,bucket.expiresAt-System.currentTimeMillis())
            card.addView(TextView(this).apply {
                text="Expires in %d:%02d:%02d".format(
                    remaining/3600000,(remaining/60000)%60,(remaining/1000)%60)
            })
            card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=RETENTION_MS.toInt()
                progress=remaining.coerceAtMost(RETENTION_MS).toInt()
            })
            card.addView(LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                addView(Button(this@LogActivity).apply {
                    text="Copy Log"
                    setOnClickListener {
                        val value=RuntimeLogger.copyHour(bucket.hourStart)
                        val cb=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Hourly Logs",value))
                        toast("Hour copied")
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                addView(Button(this@LogActivity).apply {
                    text="Delete"
                    setOnClickListener {
                        RuntimeLogger.deleteHour(bucket.hourStart)
                        expanded.remove(bucket.hourStart); pages.remove(bucket.hourStart)
                        renderHeaders()
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
            })
            if(bucket.hourStart in expanded) addPage(card,bucket)
            groups.addView(card)
        }
    }

    private fun addPage(card:LinearLayout,bucket:RuntimeLogger.HourBucket) {
        val page=pages[bucket.hourStart] ?: 0
        val lines=RuntimeLogger.readHourPage(bucket.hourStart,page)
        card.addView(TextView(this).apply {
            textSize=12f; setTextIsSelectable(true)
            text=if(lines.isEmpty()) "No logs on this page." else lines.joinToString("\n")
        })
        card.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(Button(this@LogActivity).apply {
                text="Previous"; isEnabled=page>0
                setOnClickListener { pages[bucket.hourStart]=page-1; renderHeaders() }
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(TextView(this@LogActivity).apply {
                gravity=Gravity.CENTER
                text="Page ${page+1} • ${bucket.count} logs"
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(Button(this@LogActivity).apply {
                text="Next"; isEnabled=(page+1)*PAGE_SIZE<bucket.count
                setOnClickListener { pages[bucket.hourStart]=page+1; renderHeaders() }
            },LinearLayout.LayoutParams(0,-2,1f))
        })
    }

    private fun toast(text:String)=
        Toast.makeText(this,text,Toast.LENGTH_SHORT).show()

    companion object {
        private const val RETENTION_MS=10_800_000L
        private const val PAGE_SIZE=250
        private val hour=SimpleDateFormat("hh:00 a",Locale.US)
    }
}
