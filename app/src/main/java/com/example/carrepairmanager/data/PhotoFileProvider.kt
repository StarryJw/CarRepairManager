package com.example.carrepairmanager.data

import androidx.core.content.FileProvider

/** 官方建议使用应用自己的无参子类，避免部分设备直接实例化基础 FileProvider 时异常。 */
class PhotoFileProvider : FileProvider()
