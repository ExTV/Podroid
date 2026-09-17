/*
 * Single source of truth for Podroid design tokens. Theme.kt maps these into
 * Material colorScheme; screens consume colors via MaterialTheme.colorScheme.*
 * and consume the non-color tokens (spacing, fonts, radii) directly from here.
 */
package com.excp.podroid.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

object PodroidTokens {

    object Spacing {
        val XS  = 4.dp
        val SM  = 8.dp
        val MD  = 12.dp
        val LG  = 16.dp
        val XL  = 20.dp
        val XL2 = 24.dp
    }

    object Radius {
        val Chip   = 4.dp
        val Button = 8.dp
        val Card   = 12.dp
        val Sheet  = 20.dp
    }

    object TypeSize {
        val Display  = 32.sp
        val Headline = 20.sp
        val Title    = 14.sp
        val Body     = 12.sp
        val Label    = 10.sp
    }

    val Accent     = PodroidAccent
    val AccentInk  = PodroidAccentInk
    val Amber      = PodroidAmber
    val Red        = PodroidRed

    @Volatile private var interFamily: FontFamily? = null

    fun interFamily(context: Context): FontFamily {
        interFamily?.let { return it }
        synchronized(this) {
            interFamily?.let { return it }
            val regular  = extractAsset(context, "ui-fonts/Inter-Regular.ttf")
            val semiBold = extractAsset(context, "ui-fonts/Inter-SemiBold.ttf")
            val fam = FontFamily(
                Font(regular,  FontWeight.Normal),
                Font(semiBold, FontWeight.SemiBold),
            )
            interFamily = fam
            return fam
        }
    }

    @Volatile private var monoFamily: FontFamily? = null

    fun monoFamily(context: Context): FontFamily {
        monoFamily?.let { return it }
        synchronized(this) {
            monoFamily?.let { return it }
            val mono = extractAsset(context, "fonts/JetBrains-Mono.ttf")
            val fam = FontFamily(Font(mono, FontWeight.Normal))
            monoFamily = fam
            return fam
        }
    }

    @Composable @ReadOnlyComposable
    fun ui(): FontFamily = interFamily(LocalContext.current)

    @Composable @ReadOnlyComposable
    fun mono(): FontFamily = monoFamily(LocalContext.current)

    private fun extractAsset(context: Context, assetPath: String): File {
        val outFile = File(context.filesDir, assetPath)
        if (outFile.exists() && outFile.length() > 0) return outFile
        outFile.parentFile?.mkdirs()
        // Write to a tmp file and rename onto outFile so a process killed
        // mid-copy never leaves a half-written font at the canonical path
        // (the exists()+length() check above would otherwise treat it as done).
        val tmpFile = File(outFile.parentFile, outFile.name + ".tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
        }
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.delete()
            throw java.io.IOException("atomic rename ${tmpFile.name} -> ${outFile.name} failed")
        }
        return outFile
    }
}
