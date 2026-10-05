package br.com.anderson.techrace

import android.bluetooth.BluetoothSocket
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.InputStream
import java.io.OutputStream

/** Byte-stream interface shared by USB OTG and Bluetooth Classic SPP. */
interface SerialTransport {
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun write(bytes: ByteArray, timeoutMs: Int)
    fun close()
}

class UsbSerialTransport(private val port: UsbSerialPort) : SerialTransport {
    override fun read(buffer: ByteArray, timeoutMs: Int) = port.read(buffer, timeoutMs)
    override fun write(bytes: ByteArray, timeoutMs: Int) = port.write(bytes, timeoutMs)
    override fun close() = port.close()
}

class BluetoothSppTransport(private val socket: BluetoothSocket) : SerialTransport {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val available = input.available()
            if (available > 0) return input.read(buffer, 0, minOf(buffer.size, available))
            Thread.sleep(4)
        }
        return 0
    }

    override fun write(bytes: ByteArray, timeoutMs: Int) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = socket.close()
}
