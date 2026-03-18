package com.maxmarauder.radiawatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class RadiacodeBleClient(
    private val context: Context,
    private val status: (String) -> Unit,
) {
    private companion object {
        private const val TAG = "RadiaCode"
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 12_000L
        private const val SET_EXCHANGE_TIMEOUT_MS = 25_000L
        private const val TIMEOUT_GRACE_MS = 2_000L

        private fun timeoutMsForCommand(command: Int): Long {
            val base = when (command) {
                RadiacodeProtocol.COMMAND_SET_EXCHANGE -> SET_EXCHANGE_TIMEOUT_MS
                else -> DEFAULT_REQUEST_TIMEOUT_MS
            }
            return base + TIMEOUT_GRACE_MS
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val timeoutScheduler = Executors.newSingleThreadScheduledExecutor()

    private val readyFuture = CompletableFuture<Unit>()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var expectedResponseBytes: Int? = null
    private var responseBuffer = ByteArrayOutputStream()

    private var pending: PendingRequest? = null

    private var pendingWriteChunks: List<ByteArray>? = null
    private var pendingWriteIndex: Int = 0

    private var seq: Int = 0

    private data class PendingRequest(
        val header4: ByteArray,
        val future: CompletableFuture<ByteArray>,
        val command: Int,
        val reqSeqNo: Int,
        var timeoutTask: ScheduledFuture<*>? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PendingRequest

            if (command != other.command) return false
            if (reqSeqNo != other.reqSeqNo) return false
            if (!header4.contentEquals(other.header4)) return false
            if (future != other.future) return false
            if (timeoutTask != other.timeoutTask) return false

            return true
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + reqSeqNo
            result = 31 * result + header4.contentHashCode()
            result = 31 * result + future.hashCode()
            result = 31 * result + (timeoutTask?.hashCode() ?: 0)
            return result
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: android.bluetooth.BluetoothDevice) {
        status("Connecting…")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun ready(): CompletableFuture<Unit> = readyFuture

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        executor.execute {
            pending?.future?.completeExceptionally(IllegalStateException("Disconnected"))
            pending = null
            if (!readyFuture.isDone) {
                readyFuture.completeExceptionally(IllegalStateException("Disconnected"))
            }
        }
        try { timeoutScheduler.shutdownNow() } catch (_: Throwable) {}
        gatt?.close()
        gatt = null
        writeChar = null
        notifyChar = null
    }

    fun initializeSession(): CompletableFuture<Unit> {
        return CompletableFuture
            .runAsync({ Thread.sleep(500) }, executor)
            .thenCompose {
                execute(RadiacodeProtocol.COMMAND_SET_EXCHANGE, byteArrayOf(0x01, 0xFF.toByte(), 0x12, 0xFF.toByte()))
            }
            .thenCompose {
                val now = LocalDateTime.now()
                val timePayload = RadiacodeProtocol.packSetTimePayloadLocal(
                    day = now.dayOfMonth, month = now.monthValue, year = now.year,
                    hour = now.hour, minute = now.minute, second = now.second,
                )
                execute(RadiacodeProtocol.COMMAND_SET_TIME, timePayload)
            }
            .thenCompose {
                val wrPayload = ByteArrayOutputStream().apply {
                    write(RadiacodeProtocol.packU32LE(RadiacodeProtocol.VSFR_DEVICE_TIME.toLong()))
                    write(RadiacodeProtocol.packU32LE(0))
                }.toByteArray()
                execute(RadiacodeProtocol.COMMAND_WR_VIRT_SFR, wrPayload)
            }
            .thenApply { resp ->
                if (resp.size < 4) throw IllegalStateException("WR_VIRT_SFR response too short")
                val ret = ByteBuffer.wrap(resp, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (ret != 1) throw IllegalStateException("WR_VIRT_SFR failed ret=$ret")
                Log.d(TAG, "initializeSession: done")
            }
    }

    fun readDataBuf(): CompletableFuture<ByteArray> {
        val payload = RadiacodeProtocol.packU32LE(RadiacodeProtocol.VS_DATA_BUF.toLong())
        return execute(RadiacodeProtocol.COMMAND_RD_VIRT_STRING, payload)
            .thenApply { resp ->
                if (resp.size < 8) throw IllegalStateException("RD_VIRT_STRING response too short")
                val bb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
                val retcode = bb.int
                val flen = bb.int
                if (retcode != 1) throw IllegalStateException("RD_VIRT_STRING failed ret=$retcode")
                if (flen < 0 || resp.size < 8 + flen) throw IllegalStateException("RD_VIRT_STRING bad flen=$flen")
                val out = ByteArray(flen)
                bb.get(out)
                if (out.isNotEmpty() && out.last() == 0.toByte()) out.copyOfRange(0, out.size - 1) else out
            }
    }

    private fun execute(command: Int, args: ByteArray): CompletableFuture<ByteArray> {
        val g = gatt ?: return CompletableFuture.failedFuture(IllegalStateException("Not connected"))
        val w = writeChar ?: return CompletableFuture.failedFuture(IllegalStateException("Write char missing"))

        val reqSeq = seq
        val req = RadiacodeProtocol.buildRequest(command, reqSeq, args)
        seq = (seq + 1) and 0x1F

        val timeoutMs = timeoutMsForCommand(command)
        val header4 = req.copyOfRange(4, 8)
        val future = CompletableFuture<ByteArray>()

        synchronized(this) {
            if (pending != null) {
                return CompletableFuture.failedFuture(IllegalStateException("Only one in-flight request supported"))
            }
            val p = PendingRequest(header4 = header4, future = future, command = command, reqSeqNo = reqSeq)
            p.timeoutTask = timeoutScheduler.schedule(
                {
                    val shouldFail = synchronized(this) { pending?.future === future }
                    if (shouldFail) failPending(TimeoutException("Timeout cmd=0x${command.toString(16)} seq=$reqSeq"))
                },
                timeoutMs, TimeUnit.MILLISECONDS
            )
            pending = p
            expectedResponseBytes = null
            responseBuffer.reset()
            pendingWriteChunks = req.asListChunks(18)
            pendingWriteIndex = 0
        }

        executor.execute {
            try { startNextChunkWrite(g, w) }
            catch (t: Throwable) { failPending(t) }
        }

        return future
    }

    private fun ByteArray.asListChunks(chunkSize: Int): List<ByteArray> {
        if (isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((size + chunkSize - 1) / chunkSize)
        var offset = 0
        while (offset < size) {
            val end = minOf(size, offset + chunkSize)
            out.add(copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun startNextChunkWrite(g: BluetoothGatt, w: BluetoothGattCharacteristic) {
        val chunk: ByteArray = synchronized(this) {
            val chunks = pendingWriteChunks ?: return
            if (pendingWriteIndex >= chunks.size) { pendingWriteChunks = null; return }
            chunks[pendingWriteIndex]
        }

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(w, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                w.value = chunk
                g.writeCharacteristic(w)
            }
        }

        if (!ok) failPending(IllegalStateException("writeCharacteristic failed"))
    }

    private fun failPending(t: Throwable) {
        val p = synchronized(this) {
            val cur = pending
            pending = null
            expectedResponseBytes = null
            responseBuffer.reset()
            cur
        }
        try { p?.timeoutTask?.cancel(true) } catch (_: Throwable) {}
        p?.future?.completeExceptionally(t)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("GATT error: $statusCode")
                close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("Disconnected")
                close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Service discovery failed: $statusCode")
                return
            }
            val service = gatt.getService(RadiacodeProtocol.SERVICE_UUID)
            if (service == null) { status("RadiaCode service not found"); return }

            writeChar = service.getCharacteristic(RadiacodeProtocol.WRITE_UUID)
            notifyChar = service.getCharacteristic(RadiacodeProtocol.NOTIFY_UUID)

            if (writeChar == null || notifyChar == null) {
                status("RadiaCode characteristics not found")
                return
            }

            status("Enabling notifications…")
            gatt.setCharacteristicNotification(notifyChar, true)

            val cccd = notifyChar?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (cccd == null) { status("CCCD not found"); return }

            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("CCCD write failed: $statusCode")
                return
            }
            status("Ready")
            if (!readyFuture.isDone) readyFuture.complete(Unit)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            statusCode: Int,
        ) {
            if (characteristic.uuid != RadiacodeProtocol.WRITE_UUID) return
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failPending(IllegalStateException("Characteristic write failed: $statusCode"))
                return
            }
            synchronized(this@RadiacodeBleClient) { pendingWriteIndex += 1 }
            val w = writeChar ?: return
            startNextChunkWrite(gatt, w)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onNotify(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(characteristic, value)
        }
    }

    private fun onNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != RadiacodeProtocol.NOTIFY_UUID) return

        val completed: ByteArray? = synchronized(this@RadiacodeBleClient) {
            if (pending == null) {
                expectedResponseBytes = null
                responseBuffer.reset()
                return@synchronized null
            }
            if (expectedResponseBytes == null) {
                if (value.size < 4) return@synchronized null
                val len = ByteBuffer.wrap(value, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 0) { failPending(IllegalStateException("Negative response length: $len")); return@synchronized null }
                expectedResponseBytes = len
                responseBuffer.write(value, 4, value.size - 4)
            } else {
                responseBuffer.write(value)
            }
            val expected = expectedResponseBytes ?: return@synchronized null
            if (responseBuffer.size() >= expected) responseBuffer.toByteArray().copyOfRange(0, expected) else null
        }

        if (completed != null) handleCompletedResponse(completed)
    }

    private fun handleCompletedResponse(message: ByteArray) {
        val p = synchronized(this) {
            val cur = pending
            pending = null
            expectedResponseBytes = null
            responseBuffer.reset()
            cur
        } ?: return

        try { p.timeoutTask?.cancel(true) } catch (_: Throwable) {}

        try {
            if (message.size < 4) throw IllegalStateException("Response too short")
            val header = message.copyOfRange(0, 4)
            if (!header.contentEquals(p.header4)) {
                throw IllegalStateException("Response header mismatch cmd=0x${p.command.toString(16)} seq=${p.reqSeqNo}")
            }
            p.future.complete(message.copyOfRange(4, message.size))
        } catch (t: Throwable) {
            p.future.completeExceptionally(t)
        }
    }
}
