package com.waseef.questaibrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Native equirectangular 360°/180° (mono, SBS, TB) player: MediaPlayer → SurfaceTexture → inside-out sphere. */
class Vr360Activity : Activity() {
    private lateinit var gl: GLSurfaceView
    private lateinit var r: Renderer
    private var mp: MediaPlayer? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        r = Renderer(intent.getStringExtra("mode") ?: "360")
        gl = GLSurfaceView(this).apply { setEGLContextClientVersion(2); setRenderer(r) }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun add(t: String, f: () -> Unit) = bar.addView(Ui.btn(this, t, null, 0xCC2A2A2A.toInt()) { f() }, LinearLayout.LayoutParams(-2, Ui.dp(this, 56)).apply { marginStart = Ui.dp(this@Vr360Activity, 10) })
        add("✕ خروج") { finish() }
        add("⏯") { mp?.let { if (it.isPlaying) it.pause() else it.start() } }
        add("⏪ 10") { mp?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) } }
        add("10 ⏩") { mp?.let { it.seekTo(it.currentPosition + 10000) } }
        add("🎯 توسيط") { r.yaw = 0f; r.pitch = 0f }
        add("360/180") { r.mode = when (r.mode) { "360" -> "180"; "180" -> "180sbs"; "180sbs" -> "360tb"; "360tb" -> "360sbs"; else -> "360" }; Toast.makeText(this, r.mode, Toast.LENGTH_SHORT).show() }
        add("➕") { r.fov = (r.fov - 10).coerceAtLeast(30f) }
        add("➖") { r.fov = (r.fov + 10).coerceAtMost(120f) }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = Ui.dp(this@Vr360Activity, 20) })
        setContentView(root)

        var lx = 0f; var ly = 0f
        gl.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_MOVE -> {
                    val k = r.fov / gl.height.coerceAtLeast(1)
                    r.yaw -= (e.x - lx) * k; r.pitch = (r.pitch + (e.y - ly) * k).coerceIn(-89f, 89f); lx = e.x; ly = e.y
                }
            }
            true
        }
        gl.setOnGenericMotionListener { _, e ->
            if (e.action == MotionEvent.ACTION_SCROLL) {
                r.yaw += e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 5; r.pitch = (r.pitch + e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 5).coerceIn(-89f, 89f); true
            } else if (e.source and InputDevice.SOURCE_JOYSTICK != 0) {
                r.yaw += e.getAxisValue(MotionEvent.AXIS_X) * 4; r.pitch = (r.pitch - e.getAxisValue(MotionEvent.AXIS_Y) * 3).coerceIn(-89f, 89f); true
            } else false
        }

        r.onSurface = { st ->
            runOnUiThread {
                val p = MediaPlayer(); mp = p
                try {
                    p.setDataSource(this, Uri.parse(url), mapOf("User-Agent" to "Mozilla/5.0"))
                    p.setSurface(Surface(st)); p.isLooping = false
                    p.setOnPreparedListener { it.start() }
                    p.setOnErrorListener { _, w, x -> Toast.makeText(this, "تعذر تشغيل الفيديو ($w/$x)", Toast.LENGTH_LONG).show(); true }
                    p.prepareAsync()
                } catch (e: Exception) { Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onPause() { super.onPause(); gl.onPause(); mp?.pause() }
    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onDestroy() { mp?.release(); mp = null; super.onDestroy() }

    class Renderer(@Volatile var mode: String) : GLSurfaceView.Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        @Volatile var fov = 90f
        var onSurface: ((SurfaceTexture) -> Unit)? = null
        private var st: SurfaceTexture? = null
        private var tex = 0; private var prog = 0; private var count = 0
        private lateinit var vb: FloatBuffer; private lateinit var ib: ShortBuffer
        private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16); private val stm = FloatArray(16)
        private var aspect = 1f

        override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            st = SurfaceTexture(tex)
            prog = program(VS, FS)
            buildSphere(48, 96)
            onSurface?.invoke(st!!)
        }

        override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) { GLES20.glViewport(0, 0, w, h); aspect = w.toFloat() / h.coerceAtLeast(1) }

        override fun onDrawFrame(g: GL10?) {
            st?.updateTexImage(); st?.getTransformMatrix(stm)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 100f)
            Matrix.setIdentityM(view, 0)
            Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(view, 0, yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            GLES20.glUseProgram(prog)
            val aP = GLES20.glGetAttribLocation(prog, "p"); val aT = GLES20.glGetAttribLocation(prog, "uv")
            vb.position(0); GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 20, vb); GLES20.glEnableVertexAttribArray(aP)
            vb.position(3); GLES20.glVertexAttribPointer(aT, 2, GLES20.GL_FLOAT, false, 20, vb); GLES20.glEnableVertexAttribArray(aT)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "M"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "S"), 1, false, stm, 0)
            val m = mode
            val rect = when { m.endsWith("sbs") -> floatArrayOf(0f, 0f, 0.5f, 1f); m.endsWith("tb") -> floatArrayOf(0f, 0f, 1f, 0.5f); else -> floatArrayOf(0f, 0f, 1f, 1f) }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "R"), rect[0], rect[1], rect[2], rect[3])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "H"), if (m.startsWith("180")) 1f else 0f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, ib.position(0))
        }

        private fun buildSphere(lat: Int, lon: Int) {
            val v = FloatArray((lat + 1) * (lon + 1) * 5); var k = 0
            for (i in 0..lat) for (j in 0..lon) {
                val th = Math.PI * i / lat; val ph = 2 * Math.PI * j / lon
                v[k++] = (-Math.sin(th) * Math.sin(ph)).toFloat() * 10f
                v[k++] = Math.cos(th).toFloat() * 10f
                v[k++] = (Math.sin(th) * Math.cos(ph)).toFloat() * 10f
                v[k++] = j.toFloat() / lon; v[k++] = i.toFloat() / lat
            }
            val idx = ShortArray(lat * lon * 6); k = 0
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = (i * (lon + 1) + j).toShort(); val b = (a + lon + 1).toShort()
                idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort(); idx[k++] = b; idx[k++] = (b + 1).toShort(); idx[k++] = (a + 1).toShort()
            }
            count = idx.size
            vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v)
            ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx)
        }

        private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        private fun program(vs: String, fs: String) = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs)); GLES20.glLinkProgram(it)
        }

        companion object {
            const val VS = "attribute vec3 p;attribute vec2 uv;uniform mat4 M;varying vec2 t;void main(){t=uv;gl_Position=M*vec4(p,1.0);}"
            const val FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 t;uniform samplerExternalOES s;uniform mat4 S;uniform vec4 R;uniform float H;" +
                "void main(){vec2 c=t;if(H>0.5){c.x=(c.x-0.25)*2.0;if(c.x<0.0||c.x>1.0){gl_FragColor=vec4(0.0,0.0,0.0,1.0);return;}}" +
                "vec2 q=vec2(R.x+c.x*R.z,1.0-(R.y+c.y*R.w));gl_FragColor=texture2D(s,(S*vec4(q,0.0,1.0)).xy);}"
        }
    }
}
