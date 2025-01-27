package org.jetbrains.skiko.context

import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.redrawer.Direct3DRedrawer
import java.lang.ref.Reference

internal class Direct3DContextHandler(layer: SkiaLayer) : JvmContextHandler(layer) {
    private val bufferCount = 2
    private var surfaces: Array<Surface?> = arrayOfNulls(bufferCount)
    private fun isSurfacesNull() = surfaces.all { it == null }

    private val directXRedrawer: Direct3DRedrawer
        get() = layer.redrawer!! as Direct3DRedrawer

    override fun initContext(): Boolean {
        try {
            if (context == null) {
                context = directXRedrawer.makeContext()
                if (System.getProperty("skiko.hardwareInfo.enabled") == "true") {
                    Logger.info("Renderer info:\n ${rendererInfo()}")
                }
            }
        } catch (e: Exception) {
            Logger.warn("${e.message}\n Failed to create Skia Direct3D context!")
            return false
        }
        return true
    }

    private var currentWidth = 0
    private var currentHeight = 0
    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }
    private var isD3DInited = false

    override fun initCanvas() {
        val scale = layer.contentScale
        val w = (layer.width * scale).toInt().coerceAtLeast(0)
        val h = (layer.height * scale).toInt().coerceAtLeast(0)
        val surfaceProps = SurfaceProps(pixelGeometry = layer.pixelGeometry)

        if (isSizeChanged(w, h) || isSurfacesNull()) {
            disposeCanvas()
            context?.flush()
            
            if (!isD3DInited) {
                directXRedrawer.initSwapChain()
            } else {
                directXRedrawer.resizeBuffers(w, h)
            }
            
            try {
                for (bufferIndex in 0..bufferCount - 1) {
                    surfaces[bufferIndex] = directXRedrawer.makeSurface(getPtr(context!!), w, h, surfaceProps, bufferIndex)
                }
            } finally {
                Reference.reachabilityFence(context!!)
            }

            if (!isD3DInited) {
                isD3DInited = true
                directXRedrawer.initFence()
            }
        }
        surface = surfaces[directXRedrawer.getBufferIndex()]
        canvas = surface!!.canvas
    }

    override fun flush() {
        try {
            flush(
                getPtr(context!!),
                getPtr(surface!!)
            )
        } finally {
            Reference.reachabilityFence(context!!)
            Reference.reachabilityFence(surface!!)
        }
    }

    override fun disposeCanvas() {
        for (bufferIndex in 0 until bufferCount) {
            surfaces[bufferIndex]?.close()
        }
    }

    override fun rendererInfo(): String {
        return super.rendererInfo() +
            "Video card: ${directXRedrawer.adapterName}\n" +
            "Total VRAM: ${directXRedrawer.adapterMemorySize / 1024 / 1024} MB\n"
    }

    private external fun flush(context: Long, surface: Long)
}
