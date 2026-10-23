package app.parabol.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface XtreamApi {

    @GET("player_api.php")
    suspend fun login(
        @Query("username") username: String,
        @Query("password") password: String
    ): LoginResponse

    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams"
    ): List<LiveStream>

    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams"
    ): List<VodStream>

    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series"
    ): List<SeriesStream>

    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("series_id") seriesId: Int,
        @Query("action") action: String = "get_series_info"
    ): SeriesInfoResponse

    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: Int,
        @Query("limit") limit: Int = 2,
        @Query("action") action: String = "get_short_epg"
    ): EpgResponse

    @GET("player_api.php")
    suspend fun getFullEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("stream_id") streamId: Int,
        @Query("action") action: String = "get_simple_data_table"
    ): EpgResponse

    companion object {
        fun create(baseUrl: String): XtreamApi {
            if (DemoServer.isDemo(baseUrl)) return DemoXtreamApi()
            val normalized = baseUrl.trim().trimEnd('/') + "/"
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(XtreamApi::class.java)
        }
    }
}
