package com.screen.buddy.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Makes an illustration for the whiteboard (cell diagrams, anatomy, architectures...). */
@OptIn(PublicPreviewAPI::class)
@Singleton
class ImageGenerator @Inject constructor() {
    companion object {
        private val MODELS = listOf("gemini-3.1-flash-image", "gemini-3.1-flash-lite-image")
        private const val TIMEOUT_MS = 45_000L
    }

    suspend fun generate(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        val fullPrompt = "A clean, clearly labelled educational diagram on a dark background, " +
            "flat vector style, high contrast, no watermark: $prompt"
        for (modelName in MODELS) {
            try {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = modelName,
                    generationConfig = generationConfig {
                        responseModalities = listOf(ResponseModality.TEXT, ResponseModality.IMAGE)
                    },
                )
                val response = withTimeoutOrNull(TIMEOUT_MS) { model.generateContent(fullPrompt) }
                val parts = response?.candidates?.firstOrNull()?.content?.parts.orEmpty()
                for (part in parts) {
                    when {
                        part is ImagePart -> return@withContext part.image
                        part is InlineDataPart && part.mimeType.startsWith("image/") ->
                            BitmapFactory.decodeByteArray(part.inlineData, 0, part.inlineData.size)
                                ?.let { return@withContext it }
                    }
                }
                Timber.w("Image model %s returned no image", modelName)
            } catch (e: Exception) {
                Timber.w(e, "Image generation failed with %s", modelName)
            }
        }
        null
    }
}
