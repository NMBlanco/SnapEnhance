package me.rhunk.snapenhance.features.impl.ui.menus.impl

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import me.rhunk.snapenhance.Constants.VIEW_INJECTED_CODE
import me.rhunk.snapenhance.config.ConfigProperty
import me.rhunk.snapenhance.features.impl.Messaging
import me.rhunk.snapenhance.features.impl.downloader.MediaDownloader
import me.rhunk.snapenhance.features.impl.spying.MessageLogger
import me.rhunk.snapenhance.features.impl.ui.menus.AbstractMenu


class ChatActionMenu : AbstractMenu() {
    private fun wasInjectedView(view: View): Boolean {
        if (view.getTag(VIEW_INJECTED_CODE) != null) return true
        view.setTag(VIEW_INJECTED_CODE, true)
        return false
    }

    private fun applyButtonTheme(parent: View, button: Button) {
        button.background = ColorDrawable(Color.WHITE)
        button.setTextColor(Color.BLACK)
        button.transformationMethod = null
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            20f,
            Resources.getSystem().displayMetrics
        ).toInt()
        val params = MarginLayoutParams(parent.layoutParams)
        params.setMargins(margin, 5, margin, 5)
        params.marginEnd = margin
        params.marginStart = margin
        button.layoutParams = params
        button.height = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            50f,
            Resources.getSystem().displayMetrics
        ).toInt()
    }

    @SuppressLint("SetTextI18n")
    fun inject(viewGroup: ViewGroup) {
        val parent = viewGroup.parent.parent as ViewGroup
        if (wasInjectedView(parent)) return
        //close the action menu using a touch event
        val closeActionMenu = {
            viewGroup.dispatchTouchEvent(
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    0f,
                    0f,
                    0
                )
            )
        }
        if (context.config.bool(ConfigProperty.CHAT_DOWNLOAD_CONTEXT_MENU)) {
            parent.addView(Button(viewGroup.context).apply {
                applyButtonTheme(parent, this)
                text = this@ChatActionMenu.context.translation.get("chat_action_menu.preview_button")
                setOnClickListener {
                    closeActionMenu()
                    this@ChatActionMenu.context.executeAsync { this@ChatActionMenu.context.feature(MediaDownloader::class).onMessageActionMenu(true) }
                }
            })

            parent.addView(Button(viewGroup.context).apply {
                applyButtonTheme(parent, this)
                text = this@ChatActionMenu.context.translation.get("chat_action_menu.download_button")
                setOnClickListener {
                    closeActionMenu()
                    this@ChatActionMenu.context.executeAsync {
                        this@ChatActionMenu.context.feature(
                            MediaDownloader::class
                        ).onMessageActionMenu(false)
                    }
                }
            })
        }

        //delete logged message button
        if (context.config.bool(ConfigProperty.MESSAGE_LOGGER)) {
            val downloadButton = Button(viewGroup.context)
            applyButtonTheme(parent, downloadButton)
            downloadButton.text = context.translation.get("chat_action_menu.delete_logged_message_button")
            downloadButton.setOnClickListener {
                closeActionMenu()
                context.executeAsync {
                    with(context.feature(Messaging::class)) {
                        context.feature(MessageLogger::class).deleteMessage(lastOpenedConversationUUID.toString(), lastFocusedMessageId)
                    }
                }
            }
            parent.addView(downloadButton)
        }
    }
}
