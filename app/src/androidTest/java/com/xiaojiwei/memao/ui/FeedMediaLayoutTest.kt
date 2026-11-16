package com.xiaojiwei.memao.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaojiwei.memao.media.AudioState
import com.xiaojiwei.memao.ui.theme.MemaoTheme
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedMediaLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val width = mutableStateOf(320.dp)
    private val items = mutableStateOf(emptyList<MediaDisplay>())
    private val files = mutableListOf<File>()
    private val galleryVisits = mutableListOf<Pair<List<String>, Int>>()

    @After fun removeFixtures() {
        files.forEach { it.delete() }
    }

    @Test fun singleImageFitsItsAspectRatioAndAvailableWidth() {
        val portrait = image("portrait", 300, 400)
        val landscape = image("landscape", 400, 200).copy(width = 0, height = 0)
        items.value = listOf(portrait)
        mount()

        val container = containerBounds()
        val portraitBounds = bounds(portrait)
        assertNear(150f, portraitBounds.width.value)
        assertNear(200f, portraitBounds.height.value)
        assertEquals(container.left, portraitBounds.left)
        assertEquals(container.top, portraitBounds.top)

        compose.runOnIdle { items.value = listOf(landscape) }
        // Existing attachments without dimensions obtain the aspect ratio from their cached file.
        compose.waitUntil(10_000) { abs(bounds(landscape).height.value - 100f) < 1f }
        val landscapeBounds = bounds(landscape)
        assertNear(200f, landscapeBounds.width.value)
        assertEquals(container.left, landscapeBounds.left)
        assertEquals(container.top, landscapeBounds.top)

        compose.runOnIdle { width.value = 170.dp }
        val narrowBounds = bounds(landscape)
        assertNear(170f, narrowBounds.width.value)
        assertNear(85f, narrowBounds.height.value)
    }

    @Test fun multipleImagesKeepThreeColumnsAndRetainGalleryOrder() {
        val images = listOf(
            image("first", 300, 400), image("second", 400, 200),
            image("third", 300, 300), image("fourth", 200, 400),
            image("fifth", 400, 300), image("sixth", 300, 400),
        )
        items.value = images.take(2)
        mount()

        assertGrid(images.take(2), 100f)
        imageNode(images[1]).performTouchInput { click() }

        compose.runOnIdle { items.value = images.take(4) }
        assertGrid(images.take(4), 100f)
        imageNode(images[3]).performTouchInput { click() }

        compose.runOnIdle {
            items.value = images
            width.value = 180.dp
        }
        assertGrid(images, 58f)
        compose.runOnIdle {
            assertEquals(listOf(images.take(2).map { it.key } to 1, images.take(4).map { it.key } to 3), galleryVisits)
        }
    }

    private fun assertGrid(images: List<MediaDisplay>, cell: Float) {
        val container = containerBounds()
        images.forEachIndexed { index, image ->
            val tile = bounds(image)
            assertNear(cell, tile.width.value)
            assertNear(cell, tile.height.value)
            assertNear((index % 3) * (cell + 3f), (tile.left - container.left).value)
            assertNear((index / 3) * (cell + 3f), (tile.top - container.top).value)
            assertTrue(tile.right <= container.right)
        }
    }

    private fun mount() {
        compose.setContent {
            MemaoTheme {
                Box(Modifier.width(width.value).testTag("feedMediaFixture")) {
                    AttachmentList(
                        items.value, AudioState(), onLoad = {}, onPlay = {}, onSeek = {},
                        onGallery = { images, index -> galleryVisits += images.map { it.key } to index },
                        feedLayout = true,
                    )
                }
            }
        }
    }

    private fun image(name: String, width: Int, height: Int): MediaDisplay {
        val file = File.createTempFile("feed-$name-", ".png", compose.activity.cacheDir).also(files::add)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(75, 112, 154))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return MediaDisplay(name, "$name.png", "image/png", file, width, height)
    }

    private fun imageNode(item: MediaDisplay) = compose.onNodeWithContentDescription(item.filename)

    private fun bounds(item: MediaDisplay): DpRect = imageNode(item).getUnclippedBoundsInRoot()

    private fun containerBounds(): DpRect = compose.onNodeWithTag("feedMediaFixture").getUnclippedBoundsInRoot()

    private fun assertNear(expected: Float, actual: Float) = assertEquals(expected, actual, 1f)
}
