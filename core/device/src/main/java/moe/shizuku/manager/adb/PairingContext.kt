// SPDX-FileCopyrightText: RikkaApps Shizuku contributors
// SPDX-License-Identifier: Apache-2.0

package moe.shizuku.manager.adb

// JNI wrapper for libadb.so.
// Package name and class name MUST NOT be changed — they are hardcoded in the native library's symbol table.
class PairingContext private constructor(private val nativePtr: Long) {

    companion object {
        init {
            System.loadLibrary("adb")
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long

        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }
    }

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    val msg: ByteArray get() = nativeMsg(nativePtr)
    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(data: ByteArray): ByteArray? = nativeEncrypt(nativePtr, data)
    fun decrypt(data: ByteArray): ByteArray? = nativeDecrypt(nativePtr, data)
    fun destroy() = nativeDestroy(nativePtr)
}
