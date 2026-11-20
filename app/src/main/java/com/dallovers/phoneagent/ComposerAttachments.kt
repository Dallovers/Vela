package com.dallovers.phoneagent

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun SnapshotImage(store: DeviceAttachments, digest: String, label: String, small: Boolean) {
    val image by produceState<android.graphics.Bitmap?>(null, digest, small) {
        value = withContext(Dispatchers.IO) { runCatching {
            val bytes = store.bytes(digest)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = if (small) 8 else 1 })
        }.getOrNull() }
    }
    val bounds = if (small) Modifier.size(42.dp) else Modifier.fillMaxWidth().heightIn(max = 350.dp)
    image?.let { Image(it.asImageBitmap(), label, bounds.clip(RoundedCornerShape(8.dp)),
        contentScale = if (small) ContentScale.Crop else ContentScale.Fit) } ?: Box(bounds) {
        if (small) AgentGlyph("image") else Text("正在读取图片…")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPicker(controller: AgentController, workspace: String, open: Boolean, dismiss: () -> Unit,
                           attachments: List<TextAttachment>, onChange: (List<TextAttachment>) -> Unit,
                           onWorkspaceFiles: () -> Unit, onMore: () -> Unit, onVoice: () -> Unit, onBusy: (Boolean) -> Unit,
                           onError: (String) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(attachments)
    val allowed by rememberUpdatedState(enabled)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun import(uris: List<Uri>, camera: File? = null) {
        if (uris.isEmpty() || !allowed) { camera?.delete(); return }
        onBusy(true)
        scope.launch {
            try {
                require(current.size + uris.size <= TextAttachment.MAX_FILES) { "每条消息最多 4 个附件" }
                val additions = withContext(Dispatchers.IO) { uris.map { controller.media.import(it, workspace, camera?.name) } }
                if (allowed) {
                    val result = current + additions
                    require(result.distinctBy { it.name }.size == result.size) { "已有同名附件，请先移除或换个文件名" }
                    TextAttachment.validateBatch(result, workspace)
                    onChange(result)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "添加失败" else "文件无法打开，请检查是否损坏、加密或超过50 MB") }
            finally { camera?.delete(); onBusy(false) }
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(TextAttachment.MAX_FILES)) { import(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val photo = cameraPath?.let(::File)
        cameraPath = null
        if (ok && photo != null) import(listOf(FileProvider.getUriForFile(context, context.packageName + ".camera", photo)), photo)
        else photo?.delete()
    }
    if (open) ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("添加图片或文件", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("相册" to "image", "拍照" to "camera", "文件" to "file", "听写" to "mic").forEach { (title, icon) ->
                    Surface(onClick = {
                        dismiss()
                        runCatching {
                            when (icon) {
                                "image" -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                "file" -> files.launch(arrayOf("*/*"))
                                "mic" -> onVoice()
                                else -> {
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val photo = File.createTempFile("拍照-", ".jpg", dir)
                                    cameraPath = photo.path
                                    camera.launch(FileProvider.getUriForFile(context, context.packageName + ".camera", photo))
                                }
                            }
                        }.onFailure { cameraPath?.let { File(it).delete() }; cameraPath = null; onError("手机上没有可用的$title 应用") }
                    }, enabled = enabled && (icon == "mic" || current.size < TextAttachment.MAX_FILES), modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
                        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val shade = when (icon) {
                                "image" -> MaterialTheme.colorScheme.primaryContainer
                                "camera" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                            Surface(shape = RoundedCornerShape(16.dp), color = shade) {
                                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { AgentGlyph(icon) }
                            }
                            Text(title, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Text("最多 4 个附件，单文件最多 50 MB。PDF 按页读取，文档和表格分段解析。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row { TextButton(onClick = { dismiss(); onWorkspaceFiles() }, enabled = enabled) { Text("引用工作区文件") }
                TextButton(onClick = { dismiss(); onMore() }) { Text("更多操作") } }
        }
    }
}
