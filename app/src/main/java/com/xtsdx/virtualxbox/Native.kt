package com.xtsdx.virtualxbox

object Native {
    @JvmStatic external fun uinputCreate(name: String, vid: Int, pid: Int, max: Int): Int
    @JvmStatic external fun uinputEmit(fd: Int, type: Int, code: Int, value: Int)
    @JvmStatic external fun uinputClose(fd: Int)
    @JvmStatic external fun evScan(): Array<String>
    @JvmStatic external fun evOpen(path: String, grab: Boolean): Int
    @JvmStatic external fun evClose(fd: Int)
    @JvmStatic external fun evAbs(fd: Int, code: Int, out: IntArray): Boolean
    @JvmStatic external fun evKey(fd: Int, code: Int): Boolean
    @JvmStatic external fun evRead(fd: Int, out: IntArray): Int
}
