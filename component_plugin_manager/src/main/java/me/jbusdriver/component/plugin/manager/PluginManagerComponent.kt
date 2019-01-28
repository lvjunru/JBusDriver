package me.jbusdriver.component.plugin.manager

import android.app.Activity
import android.os.Environment
import com.billy.cc.core.component.CC
import com.billy.cc.core.component.CCResult
import com.billy.cc.core.component.IComponent
import com.google.gson.JsonObject
import me.jbusdriver.base.GSON
import me.jbusdriver.base.IO_Worker
import me.jbusdriver.base.JBusManager
import me.jbusdriver.base.KLog
import me.jbusdriver.base.common.C
import me.jbusdriver.common.bean.plugin.PluginBean
import me.jbusdriver.common.bean.plugin.Plugins
import java.io.File
import java.nio.channels.FileChannel
import java.security.MessageDigest


class PluginManagerComponent : IComponent {

    private val MD5 by lazy { MessageDigest.getInstance("MD5") }
    private val pluginsDir by lazy {
        File(Environment.getExternalStorageDirectory().absolutePath + File.separator +
                JBusManager.context.applicationContext.packageName + File.separator + "plugins" + File.separator)
    }


    override fun getName() = C.Components.PluginManager

    override fun onCall(cc: CC): Boolean {
        try {
            when (val action = cc.actionName) {
                "plugins.init" -> {
                    // it.third?.internal?.takeIf { it.isNotEmpty() }?.let { plugins ->
                    //
                    //             }
                    val plugins = GSON.fromJson(cc.getParamItem<JsonObject>("plugins"), Plugins::class.java)
                            ?: error("need param plugins ")
                    val context = cc.context as? Activity ?: error("context as Activity")
                    //后续操作
                    IO_Worker.schedule {
                        initPlugins(context, plugins ,cc.callId)
                    }
                    return true
                }
                else -> {
                    CC.sendCCResult(cc.callId, CCResult.error("not config action $action for $cc"))
                }
            }
        } catch (e: Exception) {
            KLog.w("$cc call error $e")
            CC.sendCCResult(cc.callId, CCResult.error(e.message))
        }

        return false
    }

    private fun initPlugins(activity: Activity, plugins: Plugins ,callId:String) {
        if (!pluginsDir.exists()) pluginsDir.mkdirs()
        plugins.internal.takeIf { it.isNotEmpty() }?.let {
            val need = checkPluginNeedUpdate(it)
            validateDownload(activity,need)
        }
        CC.sendCCResult(callId, CCResult.success())
    }

    /**
     * plugin not download
     */
    private fun validateDownload(activity: Activity ,plugins: List<PluginBean>) {
        val donws = mutableListOf<PluginBean>()
        plugins.forEach { plugin ->
            try {
                //set dir
                val eTag = plugin.eTag.trim()
                val fileName = "${plugin.name}-${plugin.versionName}-${plugin.versionCode}-$eTag.apk"
                val file = File(pluginsDir, fileName)

                if (file.exists()) {
                    MD5.reset()
                    val byteBuffer = file.inputStream().channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    MD5.update(byteBuffer)
                    val hex = MD5.digest().joinToString(separator = "") { b ->
                        Integer.toHexString(b.toInt() and 0xff).padStart(2, '0')
                    }.trim()
                    if (eTag.equals(hex, true)) {
                        //下载完成了
                        checkInstall(activity , plugin , file)
                    } else {
                        //需要重新下载
                        donws.add(plugin)
                    }
                } else {
                    donws.add(plugin)
                }
            } catch (e: Exception) {
                KLog.w("validateDownload error $e")
            }
        }
        //donwload
        if (donws.isNotEmpty()) {
            downloadPlugins(donws)
        }

    }

    /**
     * 检查是否需要更新
     * @param plugins :api返回的plugin 信息
     */
    private fun checkPluginNeedUpdate(plugins: List<PluginBean>): List<PluginBean> {
        val allInstalled = allPluginsInComps()
        return plugins.filter { pl ->
            val installPlugin = allInstalled.find {
                it.name == pl.name
            } ?: return@filter true
            installPlugin.versionCode < pl.versionCode
        }
    }

    private fun downloadPlugins(unChecks: Iterable<PluginBean>) {
        KLog.i("downloadPlugins $unChecks")
    }

    private fun checkInstall(activity: Activity , plugin: PluginBean, pluginFile: File) {
        KLog.i("checkInstall -> $plugin")
        KLog.i("checkInstall Plugin_Maps -> $Plugin_Maps")
        val where = Plugin_Maps.filter{ it.value.find { it.name == plugin.name } != null }.keys
        where.forEach {
            CC.obtainBuilder(it)
                    .setActionName("plugins.install")
                    .addParam("path" , pluginFile.absolutePath)
                    .setContext(activity)
                    .build().callAsync()
        }
    }


    companion object {

        private val Plugin_Maps = mutableMapOf<String, List<PluginBean>>()

        fun allPluginsInComps(): List<PluginBean> {
            return com.wlqq.phantom.library.utils.ReflectUtils.getAllFieldsList(C.Components::class.java).mapNotNull {
                try {
                    if (it.type != String::class.java) return@mapNotNull null
                    val name = it.get(C.Components::class.java)?.toString()
                            ?: return@mapNotNull null
                    CC.obtainBuilder(name)
                            .setActionName("plugins.all")
                            .build().call()
                            .getDataItem<List<PluginBean>>("plugins")?.apply {
                                Plugin_Maps[name] = this
                            }
                } catch (e: Exception) {
                    null
                }

            }.flatten()
        }

    }
}