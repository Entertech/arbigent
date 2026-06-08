package io.github.takahirom.arbigent

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

public enum class CompositeMode {
  SrcOver,
  Src,
}

internal val colors = listOf(
  0x3F9101,
  0x0E4A8E,
  0xBCBF01,
  0xBC0BA2,
  0x61AA0D,
  0x3D017A,
  0xD6A60A,
  0x7710A3,
  0xA502CE,
  0xeb5a00
)

public class ArbigentCanvas(width: Int, height: Int, bufferedImageType: Int) {
  private val bufferedImage: BufferedImage = BufferedImage(width, height, bufferedImageType)

  public fun drawImage(image: BufferedImage, multiply: Double, compositeMode: CompositeMode = CompositeMode.SrcOver) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.setComposite(
        when (compositeMode) {
          CompositeMode.SrcOver -> AlphaComposite.SrcOver
          CompositeMode.Src -> AlphaComposite.Src
        }
      )
      graphics2D.drawImage(
        image,
        0,
        0,
        (image.width * multiply).toInt(),
        (image.height * multiply).toInt(),
        null
      )
    }
  }


  public fun draw(elements: ArbigentElementList) {
    bufferedImage.graphics { graphics2D ->
      elements.elements.forEach { element ->
        val text = element.index.toString()
        val color = Color(colors[element.index % colors.size])
        drawRectOutline(element.rect, color)

        val (rawBoxWidth, rawBoxHeight) = textCalc(listOf(text))
        val textPadding = 5
        val boxWidth = rawBoxWidth + textPadding * 2
        val boxHeight = rawBoxHeight + textPadding * 2
        val topTextRect = ArbigentElement.Rect(
          element.rect.left,
          element.rect.top - boxHeight,
          element.rect.left + boxWidth,
          element.rect.top
        )
        drawRect(topTextRect, color)
        drawText(
          (topTextRect.left + textPadding).toFloat(),
          (topTextRect.top + textPadding + rawBoxHeight).toFloat(),
          listOf(text),
          Color.WHITE
        )
      }
    }
  }

  private fun textCalc(texts: List<String>): Pair<Int, Int> {
    return bufferedImage.graphics { graphics2D ->
      val frc: FontRenderContext = graphics2D.getFontRenderContext()
      val longestLineWidth = texts.map {
        calcTextLayout(
          it,
          graphics2D,
          frc
        ).getPixelBounds(frc, 0F, 0F).width
      }.maxBy {
        it
      }
      longestLineWidth to (texts.sumOf {
        calcTextLayout(it, graphics2D, frc).bounds.height + 1
      }).toInt()
    }
  }

  private fun drawRect(r: ArbigentElement.Rect, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      graphics2D.fillRect(
        r.left, r.top,
        (r.right - r.left), (r.bottom - r.top)
      )
    }
  }

  private fun drawText(textPointX: Float, textPointY: Float, texts: List<String>, color: Color) {
    bufferedImage.graphics {
      val graphics2D = bufferedImage.createGraphics()
      graphics2D.color = color

      val frc: FontRenderContext = graphics2D.getFontRenderContext()

      var nextY = textPointY
      for (text in texts) {
        val layout = calcTextLayout(text, graphics2D, frc)
        val height = layout.bounds.height
        layout.draw(
          graphics2D,
          textPointX,
          nextY
        )
        nextY += (height + 1).toInt()
      }
    }
  }

  private fun drawRectOutline(r: ArbigentElement.Rect, color: Color) {
    bufferedImage.graphics { graphics2D ->
      graphics2D.color = color
      val stroke = BasicStroke(minOf(r.width().toFloat(), r.height().toFloat()) / 20)
      graphics2D.setStroke(stroke)
      graphics2D.drawRect(
        r.left, r.top,
        (r.right - r.left), (r.bottom - r.top)
      )
    }
  }

  private val textCache = hashMapOf<String, TextLayout>()

  private fun calcTextLayout(
    text: String,
    graphics2D: Graphics2D,
    frc: FontRenderContext
  ) = textCache.getOrPut(text) {
    TextLayout(text, graphics2D.font, frc)
  }

  public fun save(screenshotFilePath: String, aiOptions: ArbigentAiOptions? = null) {
    ArbigentImageEncoder.saveImage(
      image = capLongEdge(bufferedImage, modelImageMaxLongEdge()),
      filePath = screenshotFilePath,
      format = aiOptions?.imageFormat ?: ImageFormat.PNG
    )
  }

  public companion object {
    // Default cap on the long edge of the annotated screenshot sent to the model.
    // High-DPI devices (e.g. Pixel 4: 1080x2280) otherwise ship a ~9x larger image
    // than iOS (~375px wide), roughly doubling model latency. arbigent uses
    // set-of-marks (numbered boxes + ClickWithIndex returns an index, not pixel
    // coordinates), so high resolution is unnecessary for grounding. iOS images are
    // already under this cap and unchanged. Override with ARBIGENT_MODEL_IMAGE_MAX_LONG_EDGE
    // (set <=0 to disable). ClickAtCoordinates is opt-in / off by default, so the
    // default action set is unaffected by the downscale.
    public const val DEFAULT_MODEL_IMAGE_MAX_LONG_EDGE: Int = 1024

    internal fun modelImageMaxLongEdge(): Int =
      System.getenv("ARBIGENT_MODEL_IMAGE_MAX_LONG_EDGE")?.toIntOrNull()
        ?: DEFAULT_MODEL_IMAGE_MAX_LONG_EDGE

    internal fun capLongEdge(image: BufferedImage, maxLongEdge: Int): BufferedImage {
      val longEdge = maxOf(image.width, image.height)
      if (maxLongEdge <= 0 || longEdge <= maxLongEdge) return image
      val scale = maxLongEdge.toDouble() / longEdge
      val w = (image.width * scale).toInt().coerceAtLeast(1)
      val h = (image.height * scale).toInt().coerceAtLeast(1)
      val type = if (image.type != BufferedImage.TYPE_CUSTOM) image.type else BufferedImage.TYPE_INT_RGB
      val scaled = BufferedImage(w, h, type)
      val g = scaled.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.drawImage(image, 0, 0, w, h, null)
      g.dispose()
      return scaled
    }

    public fun load(file: File, width: Int, bufferedImageType: Int): ArbigentCanvas {
      val bufferedImage = ImageIO.read(file)
      val multiply = width.toDouble() / bufferedImage.width
      if (multiply != 1.0) {
        arbigentDebugLog("Resize ArbigentCanvas: ${width.toDouble()} / ${bufferedImage.width} =multiply: $multiply")
      }
      val canvas = ArbigentCanvas(
        width = width,
        height = (bufferedImage.height * multiply).toInt(),
        bufferedImageType = bufferedImageType
      )
      canvas.drawImage(bufferedImage, multiply, CompositeMode.Src)
      return canvas
    }
  }
}

private fun <T> BufferedImage.graphics(block: (Graphics2D) -> T): T {
  val graphics = createGraphics()
  graphics.font = Font("Courier New", Font.BOLD, FontPointUtils.estimateRecommendedPointSize(
    fontName = "Courier New",
    fontStyle = Font.BOLD,
    originalWidth = width,
    originalHeight = height,
    g2d = graphics
  ))
  val result = block(graphics)
  graphics.dispose()
  return result
}
