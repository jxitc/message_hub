package com.jxitc.messagehub.domain.service

import com.jxitc.messagehub.domain.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯函数：附件规则（上限/类型/压缩参数/展示格式/type 与 sender 取值）。 */
class AttachmentPolicyTest {

    // ---------------------------------------------------------------- 压缩参数（硬要求）

    @Test
    fun compressionParameters_matchTheFrozenContract() {
        assertEquals(1_048_576L, AttachmentPolicy.DEFAULT_MAX_BYTES)
        assertEquals(2048, AttachmentPolicy.MAX_IMAGE_LONG_EDGE)
        assertEquals(listOf(80, 70, 60, 50), AttachmentPolicy.JPEG_QUALITY_LADDER)
    }

    @Test
    fun defaultAllowedTypes_matchTheFrozenContract() {
        assertEquals(
            listOf(
                "image/png", "image/jpeg", "image/gif", "image/webp",
                "application/pdf", "text/plain"
            ),
            AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES
        )
    }

    // ---------------------------------------------------------------- MIME 归一与判定

    @Test
    fun normalizeMime_stripsParametersAndLowercases() {
        assertEquals("image/jpeg", AttachmentPolicy.normalizeMime("IMAGE/JPEG; charset=binary"))
        assertEquals("text/plain", AttachmentPolicy.normalizeMime("text/plain"))
        assertEquals("", AttachmentPolicy.normalizeMime(null))
        assertEquals("", AttachmentPolicy.normalizeMime("   "))
    }

    @Test
    fun normalizeMime_mapsAliases() {
        assertEquals("image/jpeg", AttachmentPolicy.normalizeMime("image/jpg"))
        assertEquals("image/png", AttachmentPolicy.normalizeMime("image/x-png"))
        assertEquals("application/pdf", AttachmentPolicy.normalizeMime("application/x-pdf"))
        assertEquals("text/plain", AttachmentPolicy.normalizeMime("text/markdown"))
    }

    @Test
    fun isImage_coversTheFourImageTypes() {
        assertTrue(AttachmentPolicy.isImage("image/png"))
        assertTrue(AttachmentPolicy.isImage("image/jpeg"))
        assertTrue(AttachmentPolicy.isImage("image/gif"))
        assertTrue(AttachmentPolicy.isImage("image/webp"))
        assertTrue(AttachmentPolicy.isImage("image/jpg"))
        assertFalse(AttachmentPolicy.isImage("application/pdf"))
        assertFalse(AttachmentPolicy.isImage("text/plain"))
    }

    @Test
    fun kindOf_splitsImagesFromEverythingElse() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("image/webp"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf("application/pdf"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf("text/plain"))
    }

    @Test
    fun mimeFromFileName_knownExtensions() {
        assertEquals("image/png", AttachmentPolicy.mimeFromFileName("shot.PNG"))
        assertEquals("image/jpeg", AttachmentPolicy.mimeFromFileName("a.jpeg"))
        assertEquals("application/pdf", AttachmentPolicy.mimeFromFileName("doc.pdf"))
        assertEquals("text/plain", AttachmentPolicy.mimeFromFileName("notes.md"))
        assertEquals("", AttachmentPolicy.mimeFromFileName("noextension"))
        assertEquals("", AttachmentPolicy.mimeFromFileName(null))
    }

    // ---------------------------------------------------------------- 允许类型预检

    @Test
    fun isAcceptableMime_acceptsContractTypes() {
        val allowed = AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES
        assertTrue(AttachmentPolicy.isAcceptableMime("image/png", "a.png", allowed))
        assertTrue(AttachmentPolicy.isAcceptableMime("application/pdf", "a.pdf", allowed))
        assertTrue(AttachmentPolicy.isAcceptableMime("text/plain", "a.txt", allowed))
    }

    @Test
    fun isAcceptableMime_acceptsTextSubtypesBecauseServerSniffsContent() {
        assertTrue(
            AttachmentPolicy.isAcceptableMime(
                "text/csv", "表.csv", AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES
            )
        )
    }

    @Test
    fun isAcceptableMime_rejectsOtherTypes() {
        val allowed = AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES
        assertFalse(AttachmentPolicy.isAcceptableMime("video/mp4", "a.mp4", allowed))
        assertFalse(AttachmentPolicy.isAcceptableMime("application/zip", "a.zip", allowed))
    }

    @Test
    fun isAcceptableMime_fallsBackToExtensionWhenMimeIsBlank() {
        assertTrue(
            AttachmentPolicy.isAcceptableMime("", "a.pdf", AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES)
        )
        assertFalse(
            AttachmentPolicy.isAcceptableMime("", "a.zip", AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES)
        )
    }

    @Test
    fun isAcceptableMime_usesContractFallbackWhenServerSendsNoList() {
        assertTrue(AttachmentPolicy.isAcceptableMime("image/png", "a.png", emptyList()))
        assertFalse(AttachmentPolicy.isAcceptableMime("image/tiff", "a.tiff", emptyList()))
    }

    @Test
    fun isDangerousExtension_blocksServerSideRejects() {
        assertTrue(AttachmentPolicy.isDangerousExtension("evil.html"))
        assertTrue(AttachmentPolicy.isDangerousExtension("evil.SVG"))
        assertTrue(AttachmentPolicy.isDangerousExtension("a.js"))
        assertFalse(AttachmentPolicy.isDangerousExtension("ok.pdf"))
        assertFalse(AttachmentPolicy.isDangerousExtension("ok.txt"))
    }

    // ---------------------------------------------------------------- type 选择

    @Test
    fun typeForMimeTypes_noteForTextAndImages() {
        assertEquals("NOTE", AttachmentPolicy.typeForMimeTypes(emptyList()))
        assertEquals("NOTE", AttachmentPolicy.typeForMimeTypes(listOf("image/png")))
        assertEquals("NOTE", AttachmentPolicy.typeForMimeTypes(listOf("image/jpeg", "image/webp")))
    }

    @Test
    fun typeForMimeTypes_documentWhenAnyNonImageIsPresent() {
        assertEquals("DOCUMENT", AttachmentPolicy.typeForMimeTypes(listOf("application/pdf")))
        assertEquals("DOCUMENT", AttachmentPolicy.typeForMimeTypes(listOf("image/png", "text/plain")))
    }

    // ---------------------------------------------------------------- sender

    @Test
    fun senderFromDeviceModel_stripsSpaces() {
        assertEquals("PHZ110", AttachmentPolicy.senderFromDeviceModel("PHZ110"))
        assertEquals("Pixel7", AttachmentPolicy.senderFromDeviceModel("Pixel 7"))
        assertEquals("OPPOA5", AttachmentPolicy.senderFromDeviceModel("  OPPO\tA5\n"))
    }

    @Test
    fun senderFromDeviceModel_neverReturnsBlank() {
        assertEquals("Android", AttachmentPolicy.senderFromDeviceModel(null))
        assertEquals("Android", AttachmentPolicy.senderFromDeviceModel(""))
        assertEquals("Android", AttachmentPolicy.senderFromDeviceModel("   "))
    }

    // ---------------------------------------------------------------- 展示格式

    @Test
    fun formatSize_isHumanReadable() {
        assertEquals("0 B", AttachmentPolicy.formatSize(0))
        assertEquals("512 B", AttachmentPolicy.formatSize(512))
        assertEquals("1 KB", AttachmentPolicy.formatSize(1024))
        assertEquals("1.5 KB", AttachmentPolicy.formatSize(1536))
        assertEquals("500 KB", AttachmentPolicy.formatSize(512_000))
        assertEquals("1 MB", AttachmentPolicy.formatSize(1_048_576))
        assertEquals("1.1 MB", AttachmentPolicy.formatSize(1_153_434))
        assertEquals("4.2 MB", AttachmentPolicy.formatSize(4_404_019))
    }

    @Test
    fun formatSize_exactlyOneMegabyteReadsAsOneMegabyte() {
        // 边界：契约上限正好 1MB，展示不该出现 "1024 KB"
        assertEquals("1 MB", AttachmentPolicy.formatSize(AttachmentPolicy.DEFAULT_MAX_BYTES))
    }

    // ---------------------------------------------------------------- 单次提交的总量预算

    @Test
    fun totalBudget_matchesTheServerRequestCeiling() {
        // 服务器：max_bytes * 4 + 256KB
        assertEquals(4L * 1_048_576 + 262_144, AttachmentPolicy.totalAttachmentBudget(1_048_576))
    }

    @Test
    fun fourFullSizeAttachmentsFitFiveDoNot() {
        val max = AttachmentPolicy.DEFAULT_MAX_BYTES
        assertFalse(AttachmentPolicy.totalSizeExceedsBudget(4 * max, max))
        assertTrue(AttachmentPolicy.totalSizeExceedsBudget(5 * max, max))
    }

    @Test
    fun totalBudget_scalesWithTheServerAdvertisedLimit() {
        val max = 5L * 1024 * 1024
        val budget = AttachmentPolicy.totalAttachmentBudget(max)
        assertEquals(4 * max + 262_144, budget)
        assertFalse(AttachmentPolicy.totalSizeExceedsBudget(4 * max, max))
        // 预算里那 256KB 是给 multipart 头部留的余量，正好用满也不算超
        assertFalse(AttachmentPolicy.totalSizeExceedsBudget(budget, max))
        assertTrue(AttachmentPolicy.totalSizeExceedsBudget(budget + 1, max))
    }

    @Test
    fun maxImagePicks_staysInsideTheBudget() {
        // 每张最多 max_bytes → 4 张刚好在预算内；这也是跟服务器对齐过的数字
        assertEquals(4, AttachmentPolicy.MAX_IMAGE_PICKS)
        assertFalse(
            AttachmentPolicy.totalSizeExceedsBudget(
                AttachmentPolicy.MAX_IMAGE_PICKS * AttachmentPolicy.DEFAULT_MAX_BYTES,
                AttachmentPolicy.DEFAULT_MAX_BYTES
            )
        )
    }

    // ---------------------------------------------------------------- 文件选择器筛选项
    @Test
    fun filePickerMimeTypes_usesServerListWithoutImages() {
        val types = AttachmentPolicy.filePickerMimeTypes(AttachmentPolicy.DEFAULT_ALLOWED_MIME_TYPES)
        assertEquals(listOf("application/pdf", "text/plain", "text/*"), types)
    }

    @Test
    fun filePickerMimeTypes_keepsServerAdditions() {
        val types = AttachmentPolicy.filePickerMimeTypes(
            listOf("image/png", "application/pdf", "application/msword")
        )
        assertEquals(listOf("application/pdf", "application/msword"), types)
    }

    @Test
    fun filePickerMimeTypes_hasAFallbackWhenServerListIsEmpty() {
        assertEquals(
            listOf("application/pdf", "text/plain"),
            AttachmentPolicy.filePickerMimeTypes(emptyList())
        )
    }
}
