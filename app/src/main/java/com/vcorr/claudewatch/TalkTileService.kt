package com.vcorr.claudewatch

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A tile beside the watch face: Clawd and a Talk button that opens ClaudeWatch listening, one swipe
 * and one tap from the watch face.
 */
class TalkTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        done(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
                .build()
        )

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        done(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    CLAWD,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.clawd)
                                .build()
                        )
                        .build(),
                )
                .build()
        )

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val talk = ModifiersBuilders.Clickable.Builder()
            .setId("talk")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BUTTON_SIZE))
            .setHeight(dp(BUTTON_SIZE))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(talk)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(getColor(R.color.accent)))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_SIZE / 2)).build())
                            .build()
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.talk_to_claude)).build())
                    .build()
            )
            .addContent(text("Talk", getColor(R.color.ink_on_accent), 16f))
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(CLAWD)
                    .setWidth(dp(54f))
                    .setHeight(dp(30f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(button)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(text(getString(R.string.app_name), getColor(R.color.muted), 12f))
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(column)
            .build()
    }

    private fun text(value: String, color: Int, size: Float) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                .build()
        )
        .build()

    private fun <T> done(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "tile"
        }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLAWD = "clawd"
        const val BUTTON_SIZE = 64f
    }
}
