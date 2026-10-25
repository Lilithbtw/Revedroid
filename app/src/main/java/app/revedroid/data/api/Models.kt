package app.revedroid.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Every Cloudreve v4 route answers HTTP 200 with this envelope; failures are
 * signalled through [code] only. `data` stays raw because its shape depends on
 * the code (e.g. a 2FA session id string when login answers 203).
 */
@Serializable
data class ApiEnvelope(
    val code: Int = 0,
    val data: JsonElement? = null,
    val msg: String = "",
    val error: String? = null,
    @SerialName("correlation_id") val correlationId: String? = null,
)

object ApiCode {
    const val SUCCESS = 0
    const val CONTINUE = 203
    const val LOGIN_REQUIRED = 401
    const val PERMISSION_DENIED = 403
    const val NOT_FOUND = 404
    const val TWO_FA_ERROR = 40022
    const val LOGIN_SESSION_NOT_EXIST = 40023
    const val CAPTCHA_ERROR = 40026
    const val CAPTCHA_REFRESH_NEEDED = 40027
    const val CREDENTIAL_INVALID = 40020
    const val OBJECT_EXISTED = 40004
}

@Serializable
data class Token(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("access_expires") val accessExpires: String,
    @SerialName("refresh_expires") val refreshExpires: String,
) {
    val accessExpiresAt: Instant get() = parseRfc3339(accessExpires)
    val refreshExpiresAt: Instant get() = parseRfc3339(refreshExpires)
}

private fun parseRfc3339(value: String): Instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrDefault(Instant.EPOCH)

@Serializable
data class Group(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class User(
    val id: String,
    val email: String? = null,
    val nickname: String = "",
    val avatar: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val anonymous: Boolean = false,
    val group: Group? = null,
)

@Serializable
data class LoginResponse(
    val user: User,
    val token: Token,
)

@Serializable
data class TwoFactorLoginRequest(
    val otp: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class Capacity(
    val total: Long = 0,
    val used: Long = 0,
)

@Serializable
data class SiteConfig(
    val title: String? = null,
    val logo: String? = null,
    @SerialName("login_captcha") val loginCaptcha: Boolean = false,
    @SerialName("captcha_type") val captchaType: String? = null,
    @SerialName("captcha_ReCaptchaKey") val reCaptchaKey: String? = null,
    @SerialName("turnstile_site_id") val turnstileSiteId: String? = null,
    @SerialName("captcha_cap_instance_url") val capInstanceUrl: String? = null,
    @SerialName("captcha_cap_site_key") val capSiteKey: String? = null,
    @SerialName("captcha_cap_asset_server") val capAssetServer: String? = null,
    @SerialName("register_enabled") val registerEnabled: Boolean = false,
    @SerialName("thumb_exts") val thumbExts: List<String>? = null,
)

@Serializable
data class CaptchaImage(
    val ticket: String,
    val image: String,
)

object FileType {
    const val FILE = 0
    const val FOLDER = 1
}

@Serializable
data class CloudFile(
    val type: Int,
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val size: Long = 0,
    val metadata: Map<String, String>? = null,
    val path: String,
    val shared: Boolean = false,
    val owned: Boolean = true,
) {
    val isFolder: Boolean get() = type == FileType.FOLDER
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val updatedAtInstant: Instant? get() = updatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}

@Serializable
data class StoragePolicy(
    val id: String,
    val name: String = "",
    val type: String = "",
    @SerialName("max_size") val maxSize: Long = 0,
    val relay: Boolean = false,
)

@Serializable
data class Pagination(
    val page: Int = 0,
    @SerialName("page_size") val pageSize: Int = 0,
    @SerialName("total_items") val totalItems: Int? = null,
    @SerialName("next_token") val nextToken: String? = null,
    @SerialName("is_cursor") val isCursor: Boolean = false,
)

@Serializable
data class NavigatorProps(
    @SerialName("max_page_size") val maxPageSize: Int = 0,
    @SerialName("order_by_options") val orderByOptions: List<String> = emptyList(),
    @SerialName("order_direction_options") val orderDirectionOptions: List<String> = emptyList(),
)

@Serializable
data class ListResponse(
    val files: List<CloudFile> = emptyList(),
    val pagination: Pagination = Pagination(),
    val props: NavigatorProps = NavigatorProps(),
    val parent: CloudFile? = null,
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
    @SerialName("context_hint") val contextHint: String? = null,
)

@Serializable
data class ThumbResponse(
    val url: String,
    val expires: String? = null,
)

@Serializable
data class CreateFileRequest(
    val uri: String,
    val type: String,
    @SerialName("err_on_conflict") val errOnConflict: Boolean = true,
)

@Serializable
data class RenameRequest(
    val uri: String,
    @SerialName("new_name") val newName: String,
)

@Serializable
data class UrisRequest(
    val uris: List<String>,
)

@Serializable
data class FileUrlRequest(
    val uris: List<String>,
    val download: Boolean = true,
)

@Serializable
data class EntityUrl(
    val url: String,
)

@Serializable
data class FileUrlResponse(
    val urls: List<EntityUrl> = emptyList(),
    val expires: String? = null,
)

@Serializable
data class ShareCreateRequest(
    val uri: String,
)

@Serializable
data class UploadSessionRequest(
    val uri: String,
    val size: Long,
    @SerialName("policy_id") val policyId: String,
    @SerialName("last_modified") val lastModified: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
data class UploadCredential(
    @SerialName("session_id") val sessionId: String,
    @SerialName("chunk_size") val chunkSize: Long = 0,
    @SerialName("upload_urls") val uploadUrls: List<String>? = null,
    val credential: String? = null,
    val uri: String? = null,
    @SerialName("storage_policy") val storagePolicy: StoragePolicy? = null,
)

@Serializable
data class DeleteUploadSessionRequest(
    val id: String,
    val uri: String,
)
