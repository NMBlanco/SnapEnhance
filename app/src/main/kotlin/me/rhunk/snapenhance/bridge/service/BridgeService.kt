package me.rhunk.snapenhance.bridge.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Service
import android.content.*
import android.net.Uri
import android.os.*
import me.rhunk.snapenhance.Logger
import me.rhunk.snapenhance.bridge.MessageLoggerWrapper
import me.rhunk.snapenhance.bridge.common.BridgeMessageType
import me.rhunk.snapenhance.bridge.common.impl.*
import me.rhunk.snapenhance.bridge.common.impl.download.DownloadContentRequest
import me.rhunk.snapenhance.bridge.common.impl.download.DownloadContentResult
import me.rhunk.snapenhance.bridge.common.impl.file.BridgeFileType
import me.rhunk.snapenhance.bridge.common.impl.file.FileAccessRequest
import me.rhunk.snapenhance.bridge.common.impl.file.FileAccessResult
import me.rhunk.snapenhance.bridge.common.impl.locale.LocaleRequest
import me.rhunk.snapenhance.bridge.common.impl.locale.LocaleResult
import me.rhunk.snapenhance.bridge.common.impl.messagelogger.MessageLoggerRequest
import me.rhunk.snapenhance.bridge.common.impl.messagelogger.MessageLoggerResult
import java.io.File
import java.util.*

class BridgeService : Service() {
    private lateinit var messageLoggerWrapper: MessageLoggerWrapper

    override fun onBind(intent: Intent): IBinder {
        messageLoggerWrapper = MessageLoggerWrapper(getDatabasePath(BridgeFileType.MESSAGE_LOGGER_DATABASE.fileName)).also { it.init() }

        return Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                runCatching {
                    this@BridgeService.handleMessage(msg)
                }.onFailure {
                    Logger.error("Failed to handle message", it)
                }
            }
        }).binder
    }

    private fun handleMessage(msg: Message) {
        val replyMessenger = msg.replyTo
        when (BridgeMessageType.fromValue(msg.what)) {
            BridgeMessageType.FILE_ACCESS_REQUEST -> {
                with(FileAccessRequest()) {
                    read(msg.data)
                    handleFileAccess(this) { message ->
                        replyMessenger.send(message)
                    }
                }
            }
            BridgeMessageType.DOWNLOAD_CONTENT_REQUEST -> {
                with(DownloadContentRequest()) {
                    read(msg.data)
                    handleDownloadContent(this) { message ->
                        replyMessenger.send(message)
                    }
                }
            }
            BridgeMessageType.LOCALE_REQUEST -> {
                with(LocaleRequest()) {
                    read(msg.data)
                    handleLocaleRequest { message ->
                        replyMessenger.send(message)
                    }
                }
            }
            BridgeMessageType.MESSAGE_LOGGER_REQUEST -> {
                with(MessageLoggerRequest()) {
                    read(msg.data)
                    handleMessageLoggerRequest(this) { message ->
                        replyMessenger.send(message)
                    }
                }
            }

            else -> Logger.log("Unknown message type: " + msg.what)
        }
    }

    private fun handleMessageLoggerRequest(msg: MessageLoggerRequest, reply: (Message) -> Unit) {
        when (msg.action) {
            MessageLoggerRequest.Action.ADD  -> {
                val isSuccess = messageLoggerWrapper.addMessage(msg.conversationId!!, msg.messageId!!, msg.message!!)
                reply(MessageLoggerResult(isSuccess).toMessage(BridgeMessageType.MESSAGE_LOGGER_RESULT.value))
                return
            }
            MessageLoggerRequest.Action.CLEAR -> {
                messageLoggerWrapper.clearMessages()
            }
            MessageLoggerRequest.Action.DELETE -> {
                messageLoggerWrapper.deleteMessage(msg.conversationId!!, msg.messageId!!)
            }
            MessageLoggerRequest.Action.GET -> {
                val (state, messageData) = messageLoggerWrapper.getMessage(msg.conversationId!!, msg.messageId!!)
                reply(MessageLoggerResult(state, messageData).toMessage(BridgeMessageType.MESSAGE_LOGGER_RESULT.value))
            }
            else -> {
                Logger.log(Exception("Unknown message logger action: ${msg.action}"))
            }
        }

        reply(MessageLoggerResult(true).toMessage(BridgeMessageType.MESSAGE_LOGGER_RESULT.value))
    }

    private fun handleLocaleRequest(reply: (Message) -> Unit) {
        val deviceLocale = Locale.getDefault().toString()
        val compatibleLocale = resources.assets.list("lang")?.find { it.startsWith(deviceLocale) }?.substring(0, 5) ?: "en_US"

        resources.assets.open("lang/$compatibleLocale.json").use { inputStream ->
            val json = inputStream.bufferedReader().use { it.readText() }
            reply(LocaleResult(compatibleLocale, json.toByteArray(Charsets.UTF_8)).toMessage(BridgeMessageType.LOCALE_RESULT.value))
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun handleDownloadContent(msg: DownloadContentRequest, reply: (Message) -> Unit) {
        if (!msg.url!!.startsWith("http://127.0.0.1:")) return

        val outputFile = File(msg.path!!)
        outputFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(msg.url))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationUri(Uri.fromFile(outputFile))
        val downloadId = downloadManager.enqueue(request)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                unregisterReceiver(this)
                reply(DownloadContentResult(true).toMessage(BridgeMessageType.DOWNLOAD_CONTENT_RESULT.value))
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun handleFileAccess(msg: FileAccessRequest, reply: (Message) -> Unit) {
        val fileFolder = if (msg.fileType!!.isDatabase) {
            File(dataDir, "databases")
        } else {
            File(filesDir.absolutePath)
        }
        val requestFile =  File(fileFolder, msg.fileType!!.fileName)

        val result: FileAccessResult = when (msg.action) {
            FileAccessRequest.FileAccessAction.READ -> {
                if (!requestFile.exists()) {
                    FileAccessResult(false, null)
                } else {
                    FileAccessResult(true, requestFile.readBytes())
                }
            }
            FileAccessRequest.FileAccessAction.WRITE -> {
                if (!requestFile.exists()) {
                    requestFile.createNewFile()
                }
                requestFile.writeBytes(msg.content!!)
                FileAccessResult(true, null)
            }
            FileAccessRequest.FileAccessAction.DELETE -> {
                if (!requestFile.exists()) {
                    FileAccessResult(false, null)
                } else {
                    requestFile.delete()
                    FileAccessResult(true, null)
                }
            }
            FileAccessRequest.FileAccessAction.EXISTS -> FileAccessResult(requestFile.exists(), null)
            else -> throw Exception("Unknown action: " + msg.action)
        }

        reply(result.toMessage(BridgeMessageType.FILE_ACCESS_RESULT.value))
    }

}
