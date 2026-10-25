package app.revedroid.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Header that tells [app.revedroid.data.net.AuthInterceptor] to leave the request anonymous. */
const val NO_AUTH_HEADER = "X-Revedroid-No-Auth"
private const val NO_AUTH = "$NO_AUTH_HEADER: 1"

/**
 * The subset of `/api/v4/` the app uses. The base URL already ends in `api/v4/`,
 * so paths here are relative to it.
 */
interface CloudreveApi {
    @Headers(NO_AUTH)
    @GET("site/config/{section}")
    suspend fun siteConfig(
        @Path("section") section: String,
    ): ApiEnvelope

    @Headers(NO_AUTH)
    @GET("site/captcha")
    suspend fun captcha(): ApiEnvelope

    @Headers(NO_AUTH)
    @POST("session/token")
    suspend fun login(
        @Body body: JsonObject,
    ): ApiEnvelope

    @Headers(NO_AUTH)
    @POST("session/token/2fa")
    suspend fun login2fa(
        @Body body: TwoFactorLoginRequest,
    ): ApiEnvelope

    @Headers(NO_AUTH)
    @HTTP(method = "DELETE", path = "session/token", hasBody = true)
    suspend fun signOut(
        @Body body: RefreshTokenRequest,
    ): ApiEnvelope

    @GET("user/me")
    suspend fun me(): ApiEnvelope

    @GET("user/capacity")
    suspend fun capacity(): ApiEnvelope

    @GET("file")
    suspend fun listFiles(
        @Query("uri") uri: String,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("order_by") orderBy: String? = null,
        @Query("order_direction") orderDirection: String? = null,
        @Query("next_page_token") nextPageToken: String? = null,
    ): ApiEnvelope

    @GET("file/thumb")
    suspend fun thumb(
        @Query("uri") uri: String,
    ): ApiEnvelope

    @POST("file/create")
    suspend fun createFile(
        @Body body: CreateFileRequest,
    ): ApiEnvelope

    @POST("file/rename")
    suspend fun rename(
        @Body body: RenameRequest,
    ): ApiEnvelope

    @HTTP(method = "DELETE", path = "file", hasBody = true)
    suspend fun delete(
        @Body body: DeleteFilesRequest,
    ): ApiEnvelope

    @POST("file/restore")
    suspend fun restore(
        @Body body: UrisRequest,
    ): ApiEnvelope

    @HTTP(method = "DELETE", path = "file/trash")
    suspend fun emptyTrash(): ApiEnvelope

    @POST("file/url")
    suspend fun fileUrl(
        @Body body: FileUrlRequest,
    ): ApiEnvelope

    @PUT("share")
    suspend fun createShare(
        @Body body: ShareCreateRequest,
    ): ApiEnvelope

    @PUT("file/upload")
    suspend fun createUploadSession(
        @Body body: UploadSessionRequest,
    ): ApiEnvelope

    @POST("file/upload/{sessionId}/{index}")
    suspend fun uploadChunk(
        @Path("sessionId") sessionId: String,
        @Path("index") index: Int,
        @Body body: RequestBody,
    ): ApiEnvelope

    @HTTP(method = "DELETE", path = "file/upload", hasBody = true)
    suspend fun deleteUploadSession(
        @Body body: DeleteUploadSessionRequest,
    ): ApiEnvelope
}

@Serializable
data class DeleteFilesRequest(
    val uris: List<String>,
    @SerialName("skip_soft_delete") val skipSoftDelete: Boolean = false,
)

class ApiException(
    val code: Int,
    message: String,
) : Exception(message)

/** Serializer shared by Retrofit and manual envelope parsing. */
val CloudreveJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

fun ApiEnvelope.ensureOk(): ApiEnvelope {
    if (code != ApiCode.SUCCESS) throw toException()
    return this
}

fun ApiEnvelope.toException(): ApiException {
    val base = msg.ifBlank { fallbackMessage(code) }
    val detail = error?.takeIf { it.isNotBlank() && !base.contains(it) }
    return ApiException(code, if (detail != null) "$base ($detail)" else base)
}

inline fun <reified T> ApiEnvelope.decode(): T {
    ensureOk()
    return CloudreveJson.decodeFromJsonElement<T>(data ?: JsonNull)
}

private fun fallbackMessage(code: Int): String =
    when (code) {
        ApiCode.LOGIN_REQUIRED -> "You are not signed in"
        ApiCode.PERMISSION_DENIED -> "You don't have permission to do that"
        ApiCode.NOT_FOUND -> "Not found"
        ApiCode.CREDENTIAL_INVALID -> "Wrong email or password"
        ApiCode.TWO_FA_ERROR -> "The two-factor code is incorrect"
        ApiCode.CAPTCHA_ERROR -> "The captcha is incorrect"
        ApiCode.CAPTCHA_REFRESH_NEEDED -> "The captcha expired, please try again"
        ApiCode.OBJECT_EXISTED -> "An item with that name already exists"
        else -> "Server returned error $code"
    }
