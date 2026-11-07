package com.yll682.houche.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 始终从仓库真实样本读取，兼容仓库根目录和 app 模块两种测试工作目录。 */
internal object MetroFixtures {
    fun file(name: String): File = sequenceOf(File("analysis/fixtures/$name"), File("../analysis/fixtures/$name"))
        .first { it.isFile }

    val sample: JsonElement by lazy { BusNetwork.json.parseToJsonElement(file("metro-response-sample.json").readText()) }
    val plaintext: JsonElement get() = BusNetwork.json.parseToJsonElement(sample.s("plaintext"))

    /** 分支用例修改原有明文后重新封装；成功解密的基准始终是原始密文。 */
    fun envelope(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sample.s("secret").toByteArray(Charsets.UTF_8).copyOf(16), "AES"))
        val hex = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        return JsonObject(mapOf("responseData" to JsonPrimitive(hex))).toString()
    }
}
